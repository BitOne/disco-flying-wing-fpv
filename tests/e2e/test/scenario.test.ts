import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { parseScenario } from '../src/scenario.ts';

const base = `name: t
app: { package: p, activity: .A }
`;

test('parses every step kind', () => {
  const s = parseScenario(`${base}steps:
  - send: battery 50
  - send: [altitude 1, airspeed 2]
  - wait: 100
  - restart_app
  - back
  - tap_text: DRONES
  - expect_texts: [a, b]
    scroll: true
    timeout: 500
  - expect_texts: c
  - screenshot: shot-1
`);
  assert.deepEqual(s.steps, [
    { kind: 'send', commands: ['battery 50'] },
    { kind: 'send', commands: ['altitude 1', 'airspeed 2'] },
    { kind: 'wait', ms: 100 },
    { kind: 'restart_app' },
    { kind: 'back' },
    { kind: 'tap_text', text: 'DRONES' },
    { kind: 'expect_texts', texts: ['a', 'b'], timeoutMs: 500, scroll: true },
    { kind: 'expect_texts', texts: ['c'], timeoutMs: 10000, scroll: false },
    { kind: 'screenshot', name: 'shot-1' },
  ]);
});

test('rejects unknown actions and malformed steps', () => {
  assert.throws(() => parseScenario(`${base}steps:\n  - fly: now\n`), /unknown action "fly"/);
  assert.throws(() => parseScenario(`${base}steps:\n  - send: a\n    wait: 1\n`), /exactly one action/);
  assert.throws(() => parseScenario(`${base}steps:\n  - wait: soon\n`), /milliseconds/);
  assert.throws(() => parseScenario(`${base}steps:\n  - screenshot: ../x\n`), /screenshot expects a name/);
  assert.throws(() => parseScenario(`${base}steps: []\n`), /non-empty/);
  assert.throws(() => parseScenario(`name: t\nsteps: [back]\n`), /"app" needs/);
});

test('the committed scenarios parse', async () => {
  const text = await readFile(new URL('../../scenarios/demo-disco-telemetry.yaml', import.meta.url), 'utf8');
  const s = parseScenario(text);
  assert.equal(s.app.package, 'com.parrot.drone.groundsdkdemo');
  assert.ok(s.steps.length > 3);
});
