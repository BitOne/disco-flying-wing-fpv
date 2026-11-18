/**
 * End-to-end runner: plays YAML scenarios against fake-sc2 (control socket)
 * and the app on the emulator, then writes a summary.
 *
 * Usage: node src/run.ts [--out <dir>] [--ctrl-port <port>] <scenario.yaml>...
 * Exit code: 0 if every scenario passed, 1 otherwise, 2 on usage/setup error.
 */
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { basename, join, resolve } from 'node:path';
import { Emulator } from './emulator.ts';
import { sleep } from './exec.ts';
import { FakeControl } from './fake.ts';
import { changedFraction, lumaStats } from './imagecheck.ts';
import { decodePng } from './png.ts';
import { loadScenario, type Scenario, type Step } from './scenario.ts';
import { missingTexts, textsOf } from './uidump.ts';

interface StepResult {
  index: number;
  label: string;
  ok: boolean;
  ms: number;
  detail?: string;
}

interface ScenarioResult {
  file: string;
  name: string;
  ok: boolean;
  steps: StepResult[];
  error?: string;
}

function label(step: Step): string {
  switch (step.kind) {
    case 'send': return `send ${step.commands.join(' | ')}`;
    case 'wait': return `wait ${step.ms} ms`;
    case 'restart_app': return 'restart app';
    case 'tap_text': return `tap "${step.text}"`;
    case 'back': return 'back';
    case 'tap': return `tap at (${step.x}, ${step.y})`;
    case 'tap_id': return `tap #${step.id}`;
    case 'expect_texts': return `expect ${step.texts.map((t) => `"${t}"`).join(', ')}${step.scroll ? ' (scroll)' : ''}`;
    case 'screenshot': return `screenshot ${step.name}`;
    case 'expect_fake': return `expect fake "${step.query}" to report "${step.contains}"`;
    case 'expect_video':
      return `expect video in [${step.region.join(', ')}] (contrast >= ${step.minContrast}, change >= ${step.minChange})`;
  }
}

async function runStep(
  step: Step,
  scenario: Scenario,
  emu: Emulator,
  fake: FakeControl,
  dir: string,
): Promise<string | undefined> {
  switch (step.kind) {
    case 'send':
      for (const c of step.commands) await fake.send(c);
      return undefined;
    case 'wait':
      await sleep(step.ms);
      return undefined;
    case 'restart_app':
      await emu.stopApp(scenario.app.package);
      await emu.startApp(scenario.app.package, scenario.app.activity);
      return undefined;
    case 'tap_text':
    case 'tap_id': {
      // the element may not be there yet right after a screen change
      const deadline = Date.now() + 10_000;
      for (;;) {
        try {
          if (step.kind === 'tap_text') await emu.tapText(step.text);
          else await emu.tapId(step.id);
          await sleep(800);
          return undefined;
        } catch (error) {
          if (Date.now() > deadline) throw error;
          await sleep(500);
        }
      }
    }
    case 'tap': {
      // size in the current orientation, from a screenshot's PNG header
      const shot = join(dir, 'tap.png');
      await emu.screenshot(shot);
      const { width, height } = decodePng(await readFile(shot));
      await emu.tap(step.x * width, step.y * height);
      await sleep(800);
      return undefined;
    }
    case 'back':
      await emu.back();
      await sleep(500);
      return undefined;
    case 'expect_texts': {
      const deadline = Date.now() + step.timeoutMs;
      let missing: string[] = step.texts;
      while (missing.length > 0) {
        missing = missingTexts(await emu.collectTexts(step.scroll), step.texts);
        if (missing.length === 0) break;
        if (Date.now() > deadline) {
          throw new Error(`missing text(s): ${missing.map((m) => `"${m}"`).join(', ')}`);
        }
        await sleep(1000);
      }
      return undefined;
    }
    case 'expect_fake': {
      const deadline = Date.now() + step.timeoutMs;
      for (;;) {
        const reply = await fake.send(step.query);
        if (reply.includes(step.contains)) return undefined;
        if (Date.now() > deadline) throw new Error(`fake replied "${reply}"`);
        await sleep(300);
      }
    }
    case 'screenshot': {
      const path = join(dir, `${step.name}.png`);
      await emu.screenshot(path);
      return path;
    }
    case 'expect_video': {
      // Two screenshots `interval` apart: the region must have image content
      // (luminance spread) and change between them (live frames).
      const deadline = Date.now() + step.timeoutMs;
      const a = join(dir, 'video-check-a.png');
      const b = join(dir, 'video-check-b.png');
      for (;;) {
        await emu.screenshot(a);
        await sleep(step.intervalMs);
        await emu.screenshot(b);
        const imgA = decodePng(await readFile(a));
        const imgB = decodePng(await readFile(b));
        if (imgA.width !== imgB.width || imgA.height !== imgB.height) {
          // screen rotating between the two shots: measure again
          if (Date.now() > deadline) throw new Error('screen size kept changing');
          continue;
        }
        const { stddev, mean } = lumaStats(imgB, step.region);
        const change = changedFraction(imgA, imgB, step.region);
        const detail = `contrast ${stddev.toFixed(1)} (mean ${mean.toFixed(0)}), change ${(change * 100).toFixed(2)} %`;
        if (stddev >= step.minContrast && change >= step.minChange) return detail;
        if (Date.now() > deadline) throw new Error(`no live video: ${detail}`);
        await sleep(500);
      }
    }
  }
}

async function runScenario(file: string, outDir: string, emu: Emulator, fake: FakeControl): Promise<ScenarioResult> {
  const result: ScenarioResult = { file, name: basename(file), ok: false, steps: [] };
  let scenario: Scenario;
  try {
    scenario = await loadScenario(file);
  } catch (error) {
    result.error = (error as Error).message;
    return result;
  }
  result.name = scenario.name;
  const dir = join(outDir, basename(file).replace(/\.ya?ml$/, ''));
  await mkdir(dir, { recursive: true });

  for (const [index, step] of scenario.steps.entries()) {
    const started = Date.now();
    const entry: StepResult = { index: index + 1, label: label(step), ok: false, ms: 0 };
    result.steps.push(entry);
    try {
      const detail = await runStep(step, scenario, emu, fake, dir);
      entry.ok = true;
      if (detail) entry.detail = detail;
    } catch (error) {
      entry.detail = (error as Error).message;
      // keep evidence of the failure
      await emu.screenshot(join(dir, `failure-step-${index + 1}.png`)).catch(() => undefined);
      const texts = await emu.dumpUi().then(textsOf).catch(() => []);
      await writeFile(join(dir, `failure-step-${index + 1}-ui.txt`), texts.join('\n')).catch(() => undefined);
      break;
    } finally {
      entry.ms = Date.now() - started;
    }
  }
  result.ok = result.steps.length === scenario.steps.length && result.steps.every((s) => s.ok);
  return result;
}

function summary(results: readonly ScenarioResult[], outDir: string): string {
  const passed = results.filter((r) => r.ok).length;
  const lines = [
    `# e2e run ${basename(outDir)}`,
    '',
    `**${passed}/${results.length} scenarios passed**`,
    '',
  ];
  for (const r of results) {
    lines.push(`## ${r.ok ? 'PASS' : 'FAIL'}: ${r.name}`, '', `File: \`${r.file}\``, '');
    if (r.error) lines.push(`Error: ${r.error}`, '');
    for (const s of r.steps) {
      lines.push(`- ${s.ok ? 'ok  ' : 'FAIL'} ${s.index}. ${s.label} (${s.ms} ms)${s.detail ? `: ${s.detail}` : ''}`);
    }
    lines.push('');
  }
  return lines.join('\n');
}

async function main(): Promise<number> {
  const args = process.argv.slice(2);
  let outDir = resolve('out', `e2e-${new Date().toISOString().replace(/[:.]/g, '-')}`);
  let ctrlPort = 4322;
  const files: string[] = [];
  for (let i = 0; i < args.length; i++) {
    const arg = args[i] as string;
    if (arg === '--out') outDir = resolve(args[++i] ?? '');
    else if (arg === '--ctrl-port') ctrlPort = Number(args[++i]);
    else files.push(arg);
  }
  if (files.length === 0) {
    console.error('usage: node src/run.ts [--out <dir>] [--ctrl-port <port>] <scenario.yaml>...');
    return 2;
  }

  const emu = new Emulator();
  if (!(await emu.isBooted())) {
    console.error('emulator not running or not booted: run `make emu`');
    return 2;
  }
  const fake = new FakeControl();
  try {
    await fake.connect(ctrlPort);
    await fake.send('ping');
  } catch (error) {
    console.error(`fake-sc2 control socket unavailable on port ${ctrlPort}: run \`make fake\` (${(error as Error).message})`);
    return 2;
  }

  await mkdir(outDir, { recursive: true });
  const results: ScenarioResult[] = [];
  try {
    for (const file of files) {
      console.log(`== ${file}`);
      const r = await runScenario(file, outDir, emu, fake);
      for (const s of r.steps) console.log(`  ${s.ok ? 'ok  ' : 'FAIL'} ${s.index}. ${s.label}${s.ok ? '' : `: ${s.detail ?? ''}`}`);
      if (r.error) console.log(`  ERROR ${r.error}`);
      console.log(`  => ${r.ok ? 'PASS' : 'FAIL'}`);
      results.push(r);
    }
  } finally {
    fake.close();
  }

  const text = summary(results, outDir);
  await writeFile(join(outDir, 'summary.md'), text);
  await writeFile(join(outDir, 'results.json'), JSON.stringify(results, null, 2));
  console.log(`summary: ${join(outDir, 'summary.md')}`);
  return results.every((r) => r.ok) ? 0 : 1;
}

process.exitCode = await main();
