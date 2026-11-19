/**
 * Mapping between ArduPlane's state and the Disco's (ARSDK) flying states,
 * and the actions the bridge takes for the app's piloting commands.
 * Independent of I/O (unit-tested).
 */

/** ArduPlane flight modes (custom_mode in HEARTBEAT). */
export const Mode = {
  MANUAL: 0,
  CIRCLE: 1,
  FBWA: 5,
  FBWB: 6,
  AUTO: 10,
  RTL: 11,
  LOITER: 12,
  TAKEOFF: 13,
  GUIDED: 15,
} as const;

export interface PlaneState {
  armed: boolean;
  mode: number;
  relativeAlt: number; // m
  groundspeed: number; // m/s
  landing: boolean; // a landing the bridge started is in progress
}

/** fake-sc2 flying_state for a plane state (Disco semantics: circling is "hovering"). */
export function flyingState(s: PlaneState): string {
  if (!s.armed) return s.relativeAlt > 2 ? 'emergency' : 'landed';
  if (s.landing) return 'landing';
  if (s.mode === Mode.TAKEOFF) return s.relativeAlt < 1 && s.groundspeed < 2 ? 'motor_ramping' : 'takingoff';
  if (s.mode === Mode.LOITER || s.mode === Mode.CIRCLE) return 'hovering';
  return 'flying';
}

/** Return home in progress, for NavigateHomeStateChanged. */
export function returningHome(s: PlaneState): boolean {
  return s.armed && s.mode === Mode.RTL && !s.landing;
}

/** What to do for a piloting event reported by fake-sc2 (`events`). */
export type Action =
  | { kind: 'takeoff' }
  | { kind: 'mode'; mode: number }
  | { kind: 'land' }
  | { kind: 'disarm' };

export function actionFor(event: string, s: PlaneState): Action | null {
  switch (event) {
    case 'takeoff':
    case 'usertakeoff':
      return s.armed ? null : { kind: 'takeoff' };
    case 'landing':
      return s.armed ? { kind: 'land' } : null;
    case 'emergency':
      return s.armed ? { kind: 'disarm' } : null;
    case 'home_start':
      return s.armed ? { kind: 'mode', mode: Mode.RTL } : null;
    case 'home_stop':
      return s.armed ? { kind: 'mode', mode: Mode.LOITER } : null;
    case 'circle': // the loiter button: circle where it is
      return s.armed ? { kind: 'mode', mode: Mode.LOITER } : null;
    default:
      return null;
  }
}

/** Battery percent from the pack voltage when the autopilot reports none (3S LiPo). */
export function batteryPercent(remaining: number, voltage: number): number {
  if (remaining >= 0 && remaining <= 100) return remaining;
  const perCell = voltage / 3;
  return Math.max(0, Math.min(100, Math.round(((perCell - 3.5) / (4.2 - 3.5)) * 100)));
}

/**
 * Approach point for a landing at home: [distance] m from home towards
 * [from] (where the plane is), so it glides in on a straight final
 * (flat-earth approximation, fine at these distances).
 */
export function approachPoint(
  home: { lat: number; lon: number },
  from: { lat: number; lon: number },
  distance: number,
): { lat: number; lon: number } {
  const mPerDegLat = 111_320;
  const mPerDegLon = 111_320 * Math.cos((home.lat * Math.PI) / 180);
  let north = (from.lat - home.lat) * mPerDegLat;
  let east = (from.lon - home.lon) * mPerDegLon;
  const d = Math.hypot(north, east);
  if (d < 1) {
    north = 1; // on top of home: approach from the north
    east = 0;
  } else {
    north /= d;
    east /= d;
  }
  return { lat: home.lat + (north * distance) / mPerDegLat, lon: home.lon + (east * distance) / mPerDegLon };
}

/** The pilot's sticks as fake-sc2 reports them (`pcmd`): percent. */
export interface Sticks {
  on: boolean;
  roll: number;
  pitch: number; // positive: stick forward, nose down (ARSDK)
  gaz: number;
}

export function parseSticks(reply: string): Sticks | null {
  const m = /^ok (\d) (-?\d+) (-?\d+) (-?\d+)/.exec(reply.trim());
  if (!m) return null;
  return { on: m[1] === '1', roll: Number(m[2]), pitch: Number(m[3]), gaz: Number(m[4]) };
}

/**
 * RC_CHANNELS_OVERRIDE values (PWM, channels 1 to 4) for the sticks in
 * ArduPlane's FBWB mode, which flies like the Disco: roll banks, the pitch
 * stick climbs or descends (centred: altitude held), throttle sets the speed.
 * Pitch stick back (negative ARSDK pitch) is a high RC2, as on a plane radio.
 */
export function sticksToRc(s: Sticks): [number, number, number, number] {
  const pwm = (v: number) => Math.round(1500 + Math.max(-100, Math.min(100, v)) * 5);
  return [pwm(s.roll), pwm(-s.pitch), pwm(s.gaz), 1500];
}

/** The Disco's circling settings, from fake-sc2's `disco` status. */
export interface Circling {
  ccw: boolean;
  altitude: number; // m
}

export function parseCircling(reply: string): Circling | null {
  const m = /circling=(cw|ccw),(\d+)/.exec(reply);
  if (!m) return null;
  return { ccw: m[1] === 'ccw', altitude: Number(m[2]) };
}

/**
 * ArduPlane parameters for the Disco's circling settings: take-off and
 * return altitudes, and the loiter direction (a negative radius circles
 * counter-clockwise).
 */
export function circlingParams(c: Circling, radius: number): Record<string, number> {
  const r = Math.abs(radius) * (c.ccw ? -1 : 1);
  return {
    TKOFF_ALT: c.altitude,
    ALT_HOLD_RTL: c.altitude * 100,
    WP_LOITER_RAD: r,
    RTL_RADIUS: r,
  };
}

/** The Disco's camera tilt in degrees (positive up), from `disco`. */
export function parseCameraTilt(reply: string): number | null {
  const m = /\btilt=(-?\d+)/.exec(reply);
  return m ? Number(m[1]) : null;
}

/** The Disco's camera pan in degrees (positive right), from `disco`. */
export function parseCameraPan(reply: string): number | null {
  const m = /\bpan=(-?\d+)/.exec(reply);
  return m ? Number(m[1]) : null;
}
