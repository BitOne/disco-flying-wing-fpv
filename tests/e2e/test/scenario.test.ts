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
  - tap: [0.5, 0.25]
  - tap_id: take_off_land_btn
  - tap_id: lens_left_minus
    times: 3
  - expect_texts: [a, b]
    scroll: true
    timeout: 500
  - expect_texts: c
  - screenshot: shot-1
  - expect_fake: hovering
    timeout: 2000
  - expect_fake: connected
    query: ping
  - expect_image: { region: [0, 0, 0.5, 0.5] }
  - expect_image: { region: [0, 0, 0.5, 0.5], max_contrast: 5 }
  - home
  - resume_app
  - rotate: landscape
  - shell: echo hi
  - expect_stereo: { region: [0.1, 0.2, 0.45, 0.8], max_diff: 5 }
`);
  assert.deepEqual(s.steps, [
    { kind: 'send', commands: ['battery 50'] },
    { kind: 'send', commands: ['altitude 1', 'airspeed 2'] },
    { kind: 'wait', ms: 100 },
    { kind: 'restart_app' },
    { kind: 'back' },
    { kind: 'tap_text', text: 'DRONES' },
    { kind: 'tap', x: 0.5, y: 0.25 },
    { kind: 'tap_id', id: 'take_off_land_btn', times: 1 },
    { kind: 'tap_id', id: 'lens_left_minus', times: 3 },
    { kind: 'expect_texts', texts: ['a', 'b'], timeoutMs: 500, scroll: true },
    { kind: 'expect_texts', texts: ['c'], timeoutMs: 10000, scroll: false },
    { kind: 'screenshot', name: 'shot-1' },
    { kind: 'expect_fake', query: 'state', contains: 'hovering', timeoutMs: 2000 },
    { kind: 'expect_fake', query: 'ping', contains: 'connected', timeoutMs: 10000 },
    { kind: 'expect_image', region: [0, 0, 0.5, 0.5], minContrast: 20, maxContrast: Infinity, timeoutMs: 10000 },
    { kind: 'expect_image', region: [0, 0, 0.5, 0.5], minContrast: 0, maxContrast: 5, timeoutMs: 10000 },
    { kind: 'home' },
    { kind: 'resume_app' },
    { kind: 'rotate', to: 'landscape' },
    { kind: 'shell', command: 'echo hi' },
    {
      kind: 'expect_stereo', region: [0.1, 0.2, 0.45, 0.8], minShift: 0.3, maxShift: 0.6,
      maxDiff: 5, minContrast: 20, timeoutMs: 10000,
    },
  ]);
});

test('rejects unknown actions and malformed steps', () => {
  assert.throws(() => parseScenario(`${base}steps:\n  - fly: now\n`), /unknown action "fly"/);
  assert.throws(() => parseScenario(`${base}steps:\n  - send: a\n    wait: 1\n`), /exactly one action/);
  assert.throws(() => parseScenario(`${base}steps:\n  - wait: soon\n`), /milliseconds/);
  assert.throws(() => parseScenario(`${base}steps:\n  - screenshot: ../x\n`), /screenshot expects a name/);
  assert.throws(() => parseScenario(`${base}steps: []\n`), /non-empty/);
  assert.throws(() => parseScenario(`${base}steps:\n  - tap: [0.5, 2]\n`), /fractions/);
  assert.throws(() => parseScenario(`name: t\nsteps: [back]\n`), /"app" needs/);
});

test('the committed scenarios parse', async () => {
  const text = await readFile(new URL('../../scenarios/demo-disco-telemetry.yaml', import.meta.url), 'utf8');
  const s = parseScenario(text);
  assert.equal(s.app.package, 'com.parrot.drone.groundsdkdemo');
  assert.ok(s.steps.length > 3);
});
