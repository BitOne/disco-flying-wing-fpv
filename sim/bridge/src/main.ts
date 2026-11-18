/**
 * Simulated Disco bridge: ArduPilot SITL (ArduPlane, flying wing) drives
 * fake-sc2, which the app talks to as a SkyController 2 with a Disco.
 *
 *   SITL MAVLink (TCP) --telemetry--> fake-sc2 control socket --> app (ARSDK)
 *   app piloting commands --> fake-sc2 events --> MAVLink modes/commands
 *
 * The camera is FlightGear following SITL (docker/flightgear), streamed to
 * fake-sc2's video input like the test pattern.
 *
 * SITL sends a FlightGear packet (native-fdm) on every physics step, far
 * faster than FlightGear renders; FlightGear reads one per frame and falls
 * behind. The bridge keeps only the latest packet (SITL -> UDP 5503) and
 * forwards it to FlightGear (UDP 5504) at the camera frame rate.
 *
 * usage: node src/main.ts [--sitl 127.0.0.1:5760] [--ctrl-port 4322]
 *        [--fg-in 5503] [--fg-out 5504] [--fg-rate 15] [--auto-takeoff]
 */
import { createSocket } from 'node:dgram';
import { Socket } from 'node:net';
import { Control } from './control.ts';
import * as mav from './mavlink.ts';
import { actionFor, batteryPercent, flyingState, Mode, returningHome, type PlaneState } from './model.ts';

const args = process.argv.slice(2);
const opt = (name: string, def: string): string => {
  const i = args.indexOf(name);
  return i >= 0 && args[i + 1] ? (args[i + 1] as string) : def;
};
const [sitlHost, sitlPort] = opt('--sitl', '127.0.0.1:5760').split(':') as [string, string];
const ctrlPort = Number(opt('--ctrl-port', '4322'));
const autoTakeoff = args.includes('--auto-takeoff');
const fgIn = Number(opt('--fg-in', '5503'));
const fgOut = Number(opt('--fg-out', '5504'));
const fgRate = Number(opt('--fg-rate', '15'));

/** Latest FlightGear packet from SITL, forwarded at the camera frame rate. */
function relayFlightGear(): void {
  const sock = createSocket('udp4');
  let latest: Buffer | null = null;
  sock.on('message', (msg) => {
    latest = msg;
  });
  sock.bind(fgIn, '127.0.0.1');
  setInterval(() => {
    if (latest) sock.send(latest, fgOut, '127.0.0.1');
  }, 1000 / fgRate);
}

/** TKOFF_ALT in sim/disco.parm. */
const TAKEOFF_ALT = 50;

const log = (...m: unknown[]) => console.log(new Date().toISOString().slice(11, 19), ...m);
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

// latest SITL state
const plane: PlaneState = { armed: false, mode: Mode.MANUAL, relativeAlt: 0, groundspeed: 0, landing: false };
let attitude = { roll: 0, pitch: 0, yaw: 0 };
let position: ReturnType<typeof mav.globalPosition> | null = null;
let hud = { airspeed: 0, groundspeed: 0 };
let gps = { fixType: 0, satellites: 0 };
let battery = 100;
let home: { lat: number; lon: number; alt: number } | null = null;
let heartbeatSeen = false;
let ekfReady = false;

let seq = 0;
let sitl: Socket | null = null;
function sendMav(id: number, payload: Buffer): void {
  sitl?.write(mav.encode(id, payload, seq++));
}

// mission upload for a landing at home (answered from the message handler)
let pendingMission: Buffer[] | null = null;

function onMessage(m: mav.Message): void {
  if (m.system !== 1) return;
  switch (m.id) {
    case mav.MSG.HEARTBEAT: {
      if (m.component !== 1) return;
      const h = mav.heartbeat(m.payload);
      plane.armed = h.armed;
      plane.mode = h.customMode;
      if (!heartbeatSeen) {
        heartbeatSeen = true;
        log('SITL heartbeat, mode', h.customMode);
        sendMav(mav.MSG.REQUEST_DATA_STREAM, mav.requestStreams(10));
      }
      if (!plane.armed) plane.landing = false;
      break;
    }
    case mav.MSG.ATTITUDE:
      attitude = mav.attitude(m.payload);
      break;
    case mav.MSG.GLOBAL_POSITION_INT:
      position = mav.globalPosition(m.payload);
      plane.relativeAlt = position.relativeAlt;
      break;
    case mav.MSG.VFR_HUD:
      hud = mav.vfrHud(m.payload);
      plane.groundspeed = hud.groundspeed;
      break;
    case mav.MSG.GPS_RAW_INT:
      gps = mav.gpsRaw(m.payload);
      break;
    case mav.MSG.SYS_STATUS: {
      const s = mav.sysStatus(m.payload);
      battery = batteryPercent(s.batteryRemaining, s.voltage);
      break;
    }
    case mav.MSG.HOME_POSITION:
      home = mav.homePosition(m.payload);
      break;
    case mav.MSG.STATUSTEXT: {
      const text = mav.statusText(m.payload);
      log('SITL:', text);
      if (/EKF.*(using GPS|origin set)|ready to fly/i.test(text)) ekfReady = true;
      break;
    }
    case mav.MSG.MISSION_REQUEST:
    case mav.MSG.MISSION_REQUEST_INT: {
      const n = mav.missionRequestSeq(m.payload);
      const item = pendingMission?.[n];
      if (item) sendMav(mav.MSG.MISSION_ITEM_INT, item);
      break;
    }
    case mav.MSG.MISSION_ACK:
      if (pendingMission) {
        pendingMission = null;
        log('landing mission uploaded, mode AUTO');
        setMode(Mode.AUTO);
      }
      break;
    case mav.MSG.COMMAND_ACK: {
      const a = mav.commandAck(m.payload);
      if (a.result !== 0) log('command', a.command, 'refused:', a.result);
      break;
    }
  }
}

const MAV_CMD = { DO_SET_MODE: 176, COMPONENT_ARM_DISARM: 400, NAV_LAND: 21, NAV_WAYPOINT: 16 };

function setMode(mode: number): void {
  sendMav(mav.MSG.COMMAND_LONG, mav.commandLong(MAV_CMD.DO_SET_MODE, [1, mode]));
}

function arm(on: boolean, force = false): void {
  sendMav(mav.MSG.COMMAND_LONG, mav.commandLong(MAV_CMD.COMPONENT_ARM_DISARM, [on ? 1 : 0, force ? 21196 : 0]));
}

async function takeoff(): Promise<void> {
  log('take-off: mode TAKEOFF, arm');
  setMode(Mode.TAKEOFF);
  await sleep(300);
  arm(true, true);
}

function land(): void {
  if (!home) {
    log('landing: no home yet, returning home instead');
    setMode(Mode.RTL);
    return;
  }
  log('landing: uploading a landing at home');
  plane.landing = true;
  pendingMission = [
    mav.missionItemInt(0, MAV_CMD.NAV_WAYPOINT, home.lat, home.lon, 0),
    mav.missionItemInt(1, MAV_CMD.NAV_LAND, home.lat, home.lon, 0),
  ];
  sendMav(mav.MSG.MISSION_COUNT, mav.missionCount(pendingMission.length));
}

async function connectSitl(): Promise<void> {
  for (;;) {
    try {
      const s = new Socket();
      await new Promise<void>((resolve, reject) => {
        s.once('error', reject);
        s.connect(Number(sitlPort), sitlHost, () => {
          s.off('error', reject);
          resolve();
        });
      });
      const parser = new mav.Parser();
      s.on('data', (chunk: Buffer) => {
        for (const m of parser.push(chunk)) onMessage(m);
      });
      s.on('close', () => {
        log('SITL connection closed');
        process.exit(1);
      });
      sitl = s;
      log(`connected to SITL ${sitlHost}:${sitlPort}`);
      return;
    } catch {
      await sleep(1000);
    }
  }
}

async function main(): Promise<void> {
  relayFlightGear();
  await connectSitl();
  let ctrl = new Control();
  // (re)connects to fake-sc2 and hands it the flight dynamics; fake-sc2 may
  // be restarted while the simulation runs
  const ensureCtrl = async (): Promise<void> => {
    while (!ctrl.connected) {
      try {
        ctrl = new Control();
        await ctrl.connect(ctrlPort);
        await ctrl.send('sim external');
        log(`connected to fake-sc2 control on ${ctrlPort}`);
      } catch {
        await sleep(1000);
      }
    }
  };
  await ensureCtrl();

  let tick = 0;
  let autoDone = !autoTakeoff;
  let lastHomeSent = '';
  let lastRth = false;
  let loiterAsked = false;
  for (;;) {
    await sleep(100);
    tick++;
    if (!heartbeatSeen) continue;
    const cmds: string[] = [];
    cmds.push(`attitude ${attitude.roll.toFixed(4)} ${attitude.pitch.toFixed(4)} ${attitude.yaw.toFixed(4)}`);
    if (position) {
      cmds.push(`altitude ${position.relativeAlt.toFixed(2)}`);
      cmds.push(`speed ${position.vx.toFixed(2)} ${position.vy.toFixed(2)} ${position.vz.toFixed(2)}`);
    }
    cmds.push(`airspeed ${hud.airspeed.toFixed(2)}`);
    cmds.push(`flying_state ${flyingState(plane)}`);
    if (tick % 5 === 0) {
      if (gps.fixType >= 3 && position) {
        cmds.push(`position ${position.lat.toFixed(7)} ${position.lon.toFixed(7)} ${position.alt.toFixed(1)}`);
      } else {
        cmds.push('gps_lost');
      }
      cmds.push(`satellites ${gps.satellites}`);
    }
    if (tick % 10 === 0) {
      cmds.push(`battery ${battery}`);
      if (home) {
        const h = `home ${home.lat.toFixed(7)} ${home.lon.toFixed(7)} ${home.alt.toFixed(1)}`;
        if (h !== lastHomeSent) {
          cmds.push(h);
          lastHomeSent = h;
        }
      }
    }
    const rth = returningHome(plane);
    if (rth !== lastRth || tick % 20 === 0) {
      cmds.push(`rth ${rth ? 1 : 0}`);
      lastRth = rth;
    }
    try {
      for (const c of cmds) await ctrl.send(c);
      if (tick % 2 === 0) {
        const reply = await ctrl.send('events');
        for (const e of reply.split(/\s+/).slice(1)) {
          const action = actionFor(e, plane);
          log('app:', e, '->', action ? JSON.stringify(action) : 'nothing');
          if (!action) continue;
          if (action.kind === 'takeoff') await takeoff();
          else if (action.kind === 'mode') setMode(action.mode);
          else if (action.kind === 'land') land();
          else if (action.kind === 'disarm') arm(false, true);
        }
      }
    } catch (error) {
      log('fake-sc2:', (error as Error).message);
      await ensureCtrl();
    }
    // like the Disco: once at take-off altitude, circle there
    if (plane.armed && plane.mode === Mode.TAKEOFF && plane.relativeAlt >= TAKEOFF_ALT - 2 && !loiterAsked) {
      loiterAsked = true;
      log('take-off altitude reached: circling (LOITER)');
      setMode(Mode.LOITER);
    }
    if (plane.mode !== Mode.TAKEOFF) loiterAsked = false;
    if (!autoDone && ekfReady && gps.fixType >= 3) {
      autoDone = true;
      await sleep(2000);
      await takeoff();
    }
  }
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
