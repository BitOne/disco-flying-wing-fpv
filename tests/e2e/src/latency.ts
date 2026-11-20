/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 *
 * Glass-to-glass video latency, CPU and memory of the app in front, on the
 * emulator: the fake camera draws the wall clock in each frame
 * (`make video VIDEO_ENV="-e CLOCK_CODE=1"`), screenshots are taken
 * between two timestamps, and the code read back from each is compared
 * with them. Two ways to take them (--method):
 * - console (default): the emulator's own screenshot (`adb emu screenrecord
 *   screenshot`), timed in the emulator's container, on the host clock like
 *   the camera; 120 to 170 ms each, the grab first, then PNG encoding;
 * - device: `screencap` in Android, timed by the device clock, whose
 *   offset from the host's is measured first; 260 to 330 ms each.
 *
 * usage: node src/latency.ts --package <pkg> [--samples 20] [--label x]
 *   [--method console|device] [--emu disco-fpv-emu] [--out dir]
 * Prints a JSON summary; with --out, also saves it and the decoded samples.
 */
import { execFile, spawn } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { promisify } from 'node:util';
import { clockDiff, closest, type Luma, lumaFromImage, lumaFromScreencap, MODULO, readScreen } from './clockcode.ts';
import { decodePng } from './png.ts';

const run = promisify(execFile);

function opt(name: string, def: string): string {
  const i = process.argv.indexOf(name);
  return i > 0 && process.argv[i + 1] ? process.argv[i + 1]! : def;
}

const pkg = opt('--package', '');
const samples = Number(opt('--samples', '20'));
const label = opt('--label', pkg);
const emu = opt('--emu', 'disco-fpv-emu');
const outDir = opt('--out', '');
const method = opt('--method', 'console');
if (!pkg) {
  console.error('usage: node src/latency.ts --package <pkg> [--samples 20] [--label x] [--out dir]');
  process.exit(2);
}

const adbArgs = ['exec', '-i', emu, 'adb', '-s', 'emulator-5554'];

/** An interactive `adb shell`, one command at a time, answered by lines. */
class Shell {
  private proc = spawn('docker', [...adbArgs, 'shell'], { stdio: ['pipe', 'pipe', 'inherit'] });
  private buf = '';
  private waiting: Array<(line: string) => void> = [];

  constructor() {
    this.proc.stdout.setEncoding('utf8');
    this.proc.stdout.on('data', (d: string) => {
      this.buf += d;
      let i: number;
      while ((i = this.buf.indexOf('\n')) >= 0) {
        const line = this.buf.slice(0, i).replace(/\r$/, '');
        this.buf = this.buf.slice(i + 1);
        this.waiting.shift()?.(line);
      }
    });
  }

  /** Sends [cmd]; resolves with the next [lines] output lines. */
  async send(cmd: string, lines = 1): Promise<string[]> {
    const out: Promise<string>[] = [];
    for (let k = 0; k < lines; k++) out.push(new Promise((res) => this.waiting.push(res)));
    this.proc.stdin.write(cmd + '\n');
    return Promise.all(out);
  }

  close(): void {
    this.proc.stdin.end();
  }
}

const median = (xs: number[]): number => {
  const s = [...xs].sort((a, b) => a - b);
  const m = s.length >> 1;
  return s.length % 2 ? s[m]! : (s[m - 1]! + s[m]!) / 2;
};

async function main(): Promise<void> {
  const sh = new Shell();
  await sh.send('echo ready');

  // device clock minus host clock, from the ping with the shortest round trip
  let best = { rtt: Infinity, offset: 0 };
  for (let k = 0; k < 30; k++) {
    const t0 = Date.now();
    const [d] = await sh.send('date +%s%3N');
    const t1 = Date.now();
    if (t1 - t0 < best.rtt) best = { rtt: t1 - t0, offset: Number(d) - (t0 + t1) / 2 };
  }

  // screenshots, each between two timestamps (host clock for console)
  const dir = method === 'device' ? '/data/local/tmp' : '/tmp';
  let lines: string[];
  if (method === 'device') {
    lines = await sh.send(
      `for i in $(seq 1 ${samples}); do date +%s%3N; screencap ${dir}/lat$i.raw; date +%s%3N; sleep 0.15; done`,
      samples * 2,
    );
  } else {
    const { stdout } = await run('docker', ['exec', emu, 'sh', '-c',
      `for i in $(seq 1 ${samples}); do date +%s%3N; adb -s emulator-5554 emu screenrecord screenshot ${dir}/lat$i.png >/dev/null; date +%s%3N; sleep 0.15; done`,
    ]);
    lines = stdout.trim().split('\n');
  }
  const offset = method === 'device' ? best.offset : 0;

  // the app's CPU over 10 s and its memory, while it streams
  const pid = (await sh.send(`pidof ${pkg} || echo none`))[0]!.trim().split(/\s+/)[0]!;
  const cpu = await sh.send(`top -b -n 2 -d 10 -p ${pid} | tail -1`);
  sh.close();
  const { stdout: meminfo } = await run('docker', [...adbArgs, 'shell', 'dumpsys', 'meminfo', pkg], {
    maxBuffer: 1 << 24,
  });

  const results: Array<{ i: number; code: number | null; lo: number | null; hi: number | null }> = [];
  for (let i = 1; i <= samples; i++) {
    let img: Luma;
    if (method === 'device') {
      const { stdout } = await run('docker', [...adbArgs, 'exec-out', 'cat', `${dir}/lat${i}.raw`], {
        encoding: 'buffer',
        maxBuffer: 1 << 26,
      });
      img = lumaFromScreencap(stdout);
    } else {
      const { stdout } = await run('docker', ['exec', emu, 'cat', `${dir}/lat${i}.png`], {
        encoding: 'buffer',
        maxBuffer: 1 << 26,
      });
      img = lumaFromImage(decodePng(stdout));
    }
    // the timestamps, in host time, on the 2^24 ms clock
    const before = (Number(lines[(i - 1) * 2]) - offset) % MODULO;
    const after = (Number(lines[(i - 1) * 2 + 1]) - offset) % MODULO;
    const code = closest(readScreen(img), after);
    if (code === null) {
      results.push({ i, code: null, lo: null, hi: null });
      continue;
    }
    results.push({ i, code, lo: clockDiff(before, code), hi: clockDiff(after, code) });
  }
  if (method === 'device') await run('docker', [...adbArgs, 'shell', `rm -f ${dir}/lat*.raw`]);
  else await run('docker', ['exec', emu, 'sh', '-c', `rm -f ${dir}/lat*.png`]);

  const ok = results.filter((r) => r.lo !== null && r.lo > -1000 && r.hi! < 10000);
  const pss = /TOTAL PSS:\s+(\d+)/.exec(meminfo)?.[1] ?? /TOTAL\s+(\d+)/.exec(meminfo)?.[1];
  const rss = /TOTAL RSS:\s+(\d+)/.exec(meminfo)?.[1];
  const section = (name: string): string | undefined =>
    new RegExp(`^\\s*${name}:\\s+(\\d+)`, 'm').exec(meminfo)?.[1];
  const summary = {
    label,
    package: pkg,
    method,
    clock: { offsetMs: Math.round(best.offset), pingRttMs: best.rtt },
    video: {
      samples,
      decoded: ok.length,
      // the capture happened between lo and hi after the frame left the camera
      latencyAfterMs: ok.length ? median(ok.map((r) => r.lo!)) : null,
      latencyBeforeMs: ok.length ? median(ok.map((r) => r.hi!)) : null,
      minAfterMs: ok.length ? Math.min(...ok.map((r) => r.lo!)) : null,
      maxAfterMs: ok.length ? Math.max(...ok.map((r) => r.lo!)) : null,
    },
    cpuPercent: Number(cpu[0]!.trim().split(/\s+/)[8] ?? NaN),
    memoryKb: {
      pss: pss ? Number(pss) : null,
      rss: rss ? Number(rss) : null,
      javaHeap: Number(section('Java Heap') ?? NaN),
      nativeHeap: Number(section('Native Heap') ?? NaN),
      graphics: Number(section('Graphics') ?? NaN),
      code: Number(section('Code') ?? NaN),
    },
  };
  console.log(JSON.stringify(summary, null, 2));
  if (outDir) {
    mkdirSync(outDir, { recursive: true });
    writeFileSync(join(outDir, `latency-${label}.json`), JSON.stringify({ summary, results, meminfo }, null, 2));
  }
}

await main();
