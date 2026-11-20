/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 *
 * Telemetry latency of the app in front: how long a change on the fake
 * Disco (its altitude, from the fake's control socket) takes to show on the
 * screen. The emulator's own screenshots run back to back on the host clock
 * while the value changes at a known host time; the first one whose
 * [region] (landscape pixels, where the app shows the value) differs from
 * before the change bounds the delay: after the previous screenshot's grab,
 * before this one's.
 *
 * usage: node src/telemetry-latency.ts --region x,y,w,h [--trials 10]
 *   [--label x] [--ctrl-port 4322] [--emu disco-fpv-emu] [--out dir]
 */
import { execFile } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { connect } from 'node:net';
import { join } from 'node:path';
import { promisify } from 'node:util';
import { type Luma, lumaFromImage, lumaFromScreencap, rotate } from './clockcode.ts';
import { decodePng } from './png.ts';

const run = promisify(execFile);
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

function opt(name: string, def: string): string {
  const i = process.argv.indexOf(name);
  return i > 0 && process.argv[i + 1] ? process.argv[i + 1]! : def;
}

const [rx, ry, rw, rh] = opt('--region', '').split(',').map(Number);
const trials = Number(opt('--trials', '10'));
const label = opt('--label', 'app');
const ctrlPort = Number(opt('--ctrl-port', '4322'));
const emu = opt('--emu', 'disco-fpv-emu');
const outDir = opt('--out', '');
if (rh === undefined) {
  console.error('usage: node src/telemetry-latency.ts --region x,y,w,h [--trials 10] [--label x]');
  process.exit(2);
}

/** Sends one line to the fake's control socket; resolves at the send time. */
function control(line: string): Promise<number> {
  return new Promise((resolve, reject) => {
    const s = connect(ctrlPort, '127.0.0.1', () => {
      const t = Date.now();
      s.end(line + '\n');
      resolve(t);
    });
    s.on('error', reject);
  });
}

async function consoleShot(path: string): Promise<Luma> {
  const { stdout } = await run('docker', ['exec', emu, 'cat', path], { encoding: 'buffer', maxBuffer: 1 << 26 });
  return lumaFromImage(decodePng(stdout));
}

/** Mean absolute difference of two images over the region. */
function regionDiff(a: Luma, b: Luma): number {
  let sum = 0;
  for (let y = ry!; y < ry! + rh!; y++) {
    for (let x = rx!; x < rx! + rw!; x++) sum += Math.abs(a.data[y * a.width + x]! - b.data[y * b.width + x]!);
  }
  return sum / (rw! * rh!);
}

async function main(): Promise<void> {
  // which way the portrait framebuffer turns into the landscape screen
  const { stdout: raw } = await run('docker', ['exec', emu, 'adb', '-s', 'emulator-5554', 'exec-out', 'screencap'], {
    encoding: 'buffer',
    maxBuffer: 1 << 26,
  });
  const screen = lumaFromScreencap(raw);
  await run('docker', ['exec', emu, 'adb', '-s', 'emulator-5554', 'emu', 'screenrecord', 'screenshot', '/tmp/tl0.png']);
  const portrait = await consoleShot('/tmp/tl0.png');
  const cw = rotate(portrait, true);
  const ccw = rotate(portrait, false);
  const clockwise = regionDiff(cw, screen) < regionDiff(ccw, screen);

  const results: Array<{ trial: number; afterMs: number | null; beforeMs: number | null }> = [];
  let value = 50;
  for (let t = 1; t <= trials; t++) {
    await control(`altitude ${value}`);
    await sleep(2000);
    const shots = 18;
    const capture = run('docker', ['exec', emu, 'sh', '-c',
      `for i in $(seq 1 ${shots}); do date +%s%3N; adb -s emulator-5554 emu screenrecord screenshot /tmp/tl$i.png >/dev/null; date +%s%3N; done`,
    ]);
    await sleep(600);
    value = value === 50 ? 87 : 50;
    const changedAt = await control(`altitude ${value}`);
    const lines = (await capture).stdout.trim().split('\n').map(Number);
    const imgs: Luma[] = [];
    for (let i = 1; i <= shots; i++) imgs.push(rotate(await consoleShot(`/tmp/tl${i}.png`), clockwise));
    // the first screenshot taken before the change is the reference
    let ref = -1;
    for (let i = 0; i < shots; i++) if (lines[i * 2 + 1]! < changedAt) ref = i;
    let first = -1;
    if (ref >= 0) {
      for (let i = ref + 1; i < shots; i++) {
        if (regionDiff(imgs[i]!, imgs[ref]!) > 6) {
          first = i;
          break;
        }
      }
    }
    // shown after the previous screenshot started, before this one ended
    results.push(
      first > 0
        ? { trial: t, afterMs: lines[(first - 1) * 2]! - changedAt, beforeMs: lines[first * 2 + 1]! - changedAt }
        : { trial: t, afterMs: null, beforeMs: null },
    );
  }
  await run('docker', ['exec', emu, 'sh', '-c', 'rm -f /tmp/tl*.png']);
  const ok = results.filter((r) => r.afterMs !== null);
  const med = (xs: number[]) => {
    const s = [...xs].sort((a, b) => a - b);
    return s.length ? s[s.length >> 1]! : null;
  };
  const summary = {
    label,
    trials,
    measured: ok.length,
    medianAfterMs: med(ok.map((r) => Math.max(0, r.afterMs!))),
    medianBeforeMs: med(ok.map((r) => r.beforeMs!)),
    results,
  };
  console.log(JSON.stringify(summary, null, 2));
  if (outDir) {
    mkdirSync(outDir, { recursive: true });
    writeFileSync(join(outDir, `telemetry-${label}.json`), JSON.stringify(summary, null, 2));
  }
}

await main();
