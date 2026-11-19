import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as mav from '../src/mavlink.ts';
import { actionFor, approachPoint, batteryPercent, flyingState, Mode, returningHome, type PlaneState } from '../src/model.ts';

test('crc16 matches the MAVLink reference value', () => {
  // X.25 of "123456789" is 0x6F91
  assert.equal(mav.crc16(Buffer.from('123456789')), 0x6f91);
});

test('encode then parse round-trips a COMMAND_LONG', () => {
  const frame = mav.encode(mav.MSG.COMMAND_LONG, mav.commandLong(400, [1, 21196]), 7, 1, 1);
  const msgs = new mav.Parser().push(Buffer.concat([Buffer.from([0x00, 0x11]), frame]));
  assert.equal(msgs.length, 1);
  const m = msgs[0] as mav.Message;
  assert.equal(m.id, mav.MSG.COMMAND_LONG);
  assert.equal(m.payload.readUInt16LE(28), 400);
  assert.equal(m.payload.readFloatLE(4), 21196);
});

test('parser handles split frames, v2 truncated payloads and bad CRCs', () => {
  // v2 ATTITUDE with a truncated payload (trailing zeros dropped)
  const payload = Buffer.alloc(28);
  payload.writeFloatLE(0.5, 4);
  const len = 8;
  const header = Buffer.from([0xfd, len, 0, 0, 3, 1, 1, 30, 0, 0]);
  let crc = mav.crc16(Buffer.concat([header.subarray(1), payload.subarray(0, len)]));
  crc = mav.crc16(Uint8Array.of(39), crc);
  const crcBuf = Buffer.alloc(2);
  crcBuf.writeUInt16LE(crc);
  const frame = Buffer.concat([header, payload.subarray(0, len), crcBuf]);
  const parser = new mav.Parser();
  assert.equal(parser.push(frame.subarray(0, 5)).length, 0);
  const msgs = parser.push(frame.subarray(5));
  assert.equal(msgs.length, 1);
  assert.equal(mav.attitude((msgs[0] as mav.Message).payload).roll, 0.5);
  const bad = Buffer.from(frame);
  bad[bad.length - 1] = (bad[bad.length - 1] as number) ^ 0xff;
  assert.equal(new mav.Parser().push(bad).length, 0);
});

const s = (o: Partial<PlaneState>): PlaneState => ({
  armed: true, mode: Mode.LOITER, relativeAlt: 50, groundspeed: 12, landing: false, ...o,
});

test('flying states follow the Disco semantics', () => {
  assert.equal(flyingState(s({ armed: false, relativeAlt: 0 })), 'landed');
  assert.equal(flyingState(s({ armed: false, relativeAlt: 30 })), 'emergency');
  assert.equal(flyingState(s({ mode: Mode.TAKEOFF, relativeAlt: 0, groundspeed: 0 })), 'motor_ramping');
  assert.equal(flyingState(s({ mode: Mode.TAKEOFF, relativeAlt: 10 })), 'takingoff');
  assert.equal(flyingState(s({ mode: Mode.LOITER })), 'hovering');
  assert.equal(flyingState(s({ mode: Mode.RTL })), 'flying');
  assert.equal(flyingState(s({ mode: Mode.AUTO, landing: true })), 'landing');
  assert.equal(returningHome(s({ mode: Mode.RTL })), true);
  assert.equal(returningHome(s({ mode: Mode.LOITER })), false);
});

test('app piloting events become plane actions', () => {
  assert.deepEqual(actionFor('takeoff', s({ armed: false })), { kind: 'takeoff' });
  assert.equal(actionFor('takeoff', s({})), null);
  assert.deepEqual(actionFor('home_start', s({})), { kind: 'mode', mode: Mode.RTL });
  assert.deepEqual(actionFor('home_stop', s({})), { kind: 'mode', mode: Mode.LOITER });
  assert.deepEqual(actionFor('landing', s({})), { kind: 'land' });
  assert.deepEqual(actionFor('emergency', s({})), { kind: 'disarm' });
});

test('battery percent from voltage when not reported', () => {
  assert.equal(batteryPercent(64, 0), 64);
  assert.equal(batteryPercent(-1, 12.6), 100);
  assert.equal(batteryPercent(-1, 10.5), 0);
});

test('approach point is on the plane side of home, at the distance asked', () => {
  const home = { lat: 21.2665, lon: -157.8205 };
  const east = approachPoint(home, { lat: 21.2665, lon: -157.81 }, 250);
  assert.ok(Math.abs(east.lat - home.lat) < 1e-9);
  assert.ok(east.lon > home.lon);
  const metres = (east.lon - home.lon) * 111_320 * Math.cos((home.lat * Math.PI) / 180);
  assert.ok(Math.abs(metres - 250) < 0.01);
  const above = approachPoint(home, home, 250);
  assert.ok(above.lat > home.lat);
});
