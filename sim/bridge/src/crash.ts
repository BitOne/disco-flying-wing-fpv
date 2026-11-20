/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 *
 * Test tool: crashes the simulated Disco, nose first, as a loss of control
 * would. On SITL's second MAVLink port (SERIAL1, the bridge has the first):
 * ArduPlane's MANUAL mode, full down elevator and full throttle until the
 * ground (or --seconds). The bridge then sees the impact (crash detection,
 * model.ts) and tells fake-sc2 what a Disco does: its motor stops, and a
 * hard impact throws the battery out (the link is lost).
 *
 * usage: node src/crash.ts [--sitl 127.0.0.1:5762] [--seconds 20]
 */
import { connect } from 'node:net';
import * as mav from './mavlink.ts';

const args = process.argv.slice(2);
const opt = (name: string, def: string): string => {
  const i = args.indexOf(name);
  return i >= 0 && args[i + 1] ? (args[i + 1] as string) : def;
};
const [host, port] = opt('--sitl', '127.0.0.1:5762').split(':') as [string, string];
const seconds = Number(opt('--seconds', '20'));

const MANUAL = 0;
const DO_SET_MODE = 176;
const sock = connect(Number(port), host);
let seq = 0;
const send = (id: number, payload: Buffer) => sock.write(mav.encode(id, payload, seq++ & 0xff));

sock.on('connect', () => {
  console.log(`crash: MANUAL, nose down, full throttle for up to ${seconds} s`);
  send(mav.MSG.COMMAND_LONG, mav.commandLong(DO_SET_MODE, [1, MANUAL]));
  // elevator full down (low RC2, see sticksToRc), throttle full
  const sticks = [1500, 1000, 2000, 1500];
  const timer = setInterval(() => send(mav.MSG.RC_CHANNELS_OVERRIDE, mav.rcOverride(sticks)), 100);
  setTimeout(() => {
    clearInterval(timer);
    send(mav.MSG.RC_CHANNELS_OVERRIDE, mav.rcOverride([0, 0, 0, 0])); // back to the radio
    setTimeout(() => sock.end(), 200);
  }, seconds * 1000);
});
sock.on('error', (e) => {
  console.error('crash: SITL', e.message);
  process.exit(1);
});
