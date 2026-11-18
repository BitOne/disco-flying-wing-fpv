/**
 * End-to-end runner: plays YAML scenarios against fake-sc2 (control socket)
 * and the app on the emulator, then writes a summary.
 *
 * Usage: node src/run.ts [--out <dir>] [--ctrl-port <port>] <scenario.yaml>...
 * Exit code: 0 if every scenario passed, 1 otherwise, 2 on usage/setup error.
 */
import { mkdir, writeFile } from 'node:fs/promises';
import { basename, join, resolve } from 'node:path';
import { Emulator } from './emulator.ts';
import { sleep } from './exec.ts';
import { FakeControl } from './fake.ts';
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
    case 'expect_texts': return `expect ${step.texts.map((t) => `"${t}"`).join(', ')}${step.scroll ? ' (scroll)' : ''}`;
    case 'screenshot': return `screenshot ${step.name}`;
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
    case 'tap_text': {
      // the element may not be there yet right after a screen change
      const deadline = Date.now() + 10_000;
      for (;;) {
        try {
          await emu.tapText(step.text);
          await sleep(800);
          return undefined;
        } catch (error) {
          if (Date.now() > deadline) throw error;
          await sleep(500);
        }
      }
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
    case 'screenshot': {
      const path = join(dir, `${step.name}.png`);
      await emu.screenshot(path);
      return path;
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
