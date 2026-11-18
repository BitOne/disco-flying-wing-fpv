import { readFile } from 'node:fs/promises';
import { parse } from 'yaml';

/** One scenario step; each YAML step is a map with exactly one of these keys. */
export type Step =
  | { kind: 'send'; commands: string[] }
  | { kind: 'wait'; ms: number }
  | { kind: 'restart_app' }
  | { kind: 'tap_text'; text: string }
  | { kind: 'back' }
  | { kind: 'expect_texts'; texts: string[]; timeoutMs: number; scroll: boolean }
  | { kind: 'screenshot'; name: string }
  | {
      kind: 'expect_video';
      region: [number, number, number, number];
      minContrast: number;
      minChange: number;
      intervalMs: number;
      timeoutMs: number;
    };

export interface Scenario {
  name: string;
  description: string;
  app: { package: string; activity: string };
  steps: Step[];
}

const isRecord = (v: unknown): v is Record<string, unknown> =>
  typeof v === 'object' && v !== null && !Array.isArray(v);

function stringList(value: unknown, where: string): string[] {
  const list = typeof value === 'string' ? [value] : value;
  if (!Array.isArray(list) || list.length === 0 || !list.every((s) => typeof s === 'string')) {
    throw new Error(`${where}: expected a string or a non-empty list of strings`);
  }
  return list;
}

function parseStep(raw: unknown, index: number): Step {
  const where = `step ${index + 1}`;
  if (typeof raw === 'string') {
    if (raw === 'restart_app') return { kind: 'restart_app' };
    if (raw === 'back') return { kind: 'back' };
    throw new Error(`${where}: unknown step "${raw}"`);
  }
  if (!isRecord(raw)) throw new Error(`${where}: expected a map`);
  const keys = Object.keys(raw).filter((k) => !['timeout', 'scroll'].includes(k));
  if (keys.length !== 1) throw new Error(`${where}: expected exactly one action, got ${keys.join(', ') || 'none'}`);
  const key = keys[0] as string;
  const value = raw[key];
  switch (key) {
    case 'send':
      return { kind: 'send', commands: stringList(value, where) };
    case 'wait':
      if (typeof value !== 'number' || value < 0) throw new Error(`${where}: wait expects milliseconds`);
      return { kind: 'wait', ms: value };
    case 'tap_text':
      if (typeof value !== 'string') throw new Error(`${where}: tap_text expects a string`);
      return { kind: 'tap_text', text: value };
    case 'expect_texts': {
      const timeout = raw['timeout'] ?? 10_000;
      if (typeof timeout !== 'number') throw new Error(`${where}: timeout must be milliseconds`);
      return {
        kind: 'expect_texts',
        texts: stringList(value, where),
        timeoutMs: timeout,
        scroll: raw['scroll'] === true,
      };
    }
    case 'expect_video': {
      if (!isRecord(value)) throw new Error(`${where}: expect_video expects a map`);
      const region = value['region'] ?? [0.25, 0.25, 0.75, 0.75];
      if (
        !Array.isArray(region) || region.length !== 4 ||
        !region.every((v) => typeof v === 'number' && v >= 0 && v <= 1)
      ) {
        throw new Error(`${where}: region must be [left, top, right, bottom] fractions in 0..1`);
      }
      const num = (key: string, fallback: number): number => {
        const v = value[key] ?? fallback;
        if (typeof v !== 'number' || v < 0) throw new Error(`${where}: ${key} must be a non-negative number`);
        return v;
      };
      const timeout = raw['timeout'] ?? 10_000;
      if (typeof timeout !== 'number') throw new Error(`${where}: timeout must be milliseconds`);
      return {
        kind: 'expect_video',
        region: region as [number, number, number, number],
        minContrast: num('min_contrast', 20),
        minChange: num('min_change', 0.002),
        intervalMs: num('interval', 1000),
        timeoutMs: timeout,
      };
    }
    case 'screenshot':
      if (typeof value !== 'string' || !/^[\w-]+$/.test(value)) {
        throw new Error(`${where}: screenshot expects a name made of letters, digits, - or _`);
      }
      return { kind: 'screenshot', name: value };
    default:
      throw new Error(`${where}: unknown action "${key}"`);
  }
}

export function parseScenario(text: string): Scenario {
  const doc: unknown = parse(text);
  if (!isRecord(doc)) throw new Error('scenario: expected a map at top level');
  const { name, description, app, steps } = doc;
  if (typeof name !== 'string') throw new Error('scenario: missing "name"');
  if (!isRecord(app) || typeof app['package'] !== 'string' || typeof app['activity'] !== 'string') {
    throw new Error('scenario: "app" needs "package" and "activity"');
  }
  if (!Array.isArray(steps) || steps.length === 0) throw new Error('scenario: "steps" must be a non-empty list');
  return {
    name,
    description: typeof description === 'string' ? description : '',
    app: { package: app['package'], activity: app['activity'] },
    steps: steps.map(parseStep),
  };
}

export async function loadScenario(path: string): Promise<Scenario> {
  try {
    return parseScenario(await readFile(path, 'utf8'));
  } catch (error) {
    throw new Error(`${path}: ${(error as Error).message}`);
  }
}
