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
