/**
 * Minimal MAVLink (v1 and v2) codec: the messages the bridge needs between
 * ArduPilot SITL and fake-sc2, nothing else. Field offsets follow MAVLink's
 * wire order (fields sorted by type size); v2 payloads may be truncated
 * (trailing zeros) and are zero-extended before decoding.
 */

/** CRC_EXTRA seeds and full payload lengths of the messages used. */
const MESSAGES: Record<number, { extra: number; length: number }> = {
  0: { extra: 50, length: 9 }, // HEARTBEAT
  1: { extra: 124, length: 31 }, // SYS_STATUS
  11: { extra: 89, length: 6 }, // SET_MODE
  24: { extra: 24, length: 30 }, // GPS_RAW_INT (v1 part)
  30: { extra: 39, length: 28 }, // ATTITUDE
  40: { extra: 230, length: 4 }, // MISSION_REQUEST (v1 part)
  44: { extra: 221, length: 4 }, // MISSION_COUNT (v1 part)
  47: { extra: 153, length: 3 }, // MISSION_ACK (v1 part)
  51: { extra: 196, length: 4 }, // MISSION_REQUEST_INT (v1 part)
  73: { extra: 38, length: 37 }, // MISSION_ITEM_INT (v1 part)
  33: { extra: 104, length: 28 }, // GLOBAL_POSITION_INT
  66: { extra: 148, length: 6 }, // REQUEST_DATA_STREAM
  74: { extra: 20, length: 20 }, // VFR_HUD
  76: { extra: 152, length: 33 }, // COMMAND_LONG
  77: { extra: 143, length: 3 }, // COMMAND_ACK (v1 part)
  242: { extra: 104, length: 52 }, // HOME_POSITION (v1 part)
  253: { extra: 83, length: 51 }, // STATUSTEXT (v1 part)
};

export const MSG = {
  HEARTBEAT: 0,
  SYS_STATUS: 1,
  SET_MODE: 11,
  GPS_RAW_INT: 24,
  ATTITUDE: 30,
  GLOBAL_POSITION_INT: 33,
  MISSION_REQUEST: 40,
  MISSION_COUNT: 44,
  MISSION_ACK: 47,
  MISSION_REQUEST_INT: 51,
  MISSION_ITEM_INT: 73,
  REQUEST_DATA_STREAM: 66,
  VFR_HUD: 74,
  COMMAND_LONG: 76,
  COMMAND_ACK: 77,
  HOME_POSITION: 242,
  STATUSTEXT: 253,
} as const;

/** X.25 / MCRF4XX checksum used by MAVLink. */
export function crc16(bytes: Uint8Array, crc = 0xffff): number {
  for (const b of bytes) {
    let tmp = (b ^ (crc & 0xff)) & 0xff;
    tmp = (tmp ^ (tmp << 4)) & 0xff;
    crc = ((crc >> 8) ^ (tmp << 8) ^ (tmp << 3) ^ (tmp >> 4)) & 0xffff;
  }
  return crc;
}

export interface Message {
  id: number;
  system: number;
  component: number;
  payload: Buffer; // zero-extended to the full length when known
}

/** Splits a byte stream into checked MAVLink messages (v1 and v2). */
export class Parser {
  private buf = Buffer.alloc(0);

  push(chunk: Buffer): Message[] {
    this.buf = Buffer.concat([this.buf, chunk]);
    const out: Message[] = [];
    for (;;) {
      const start = this.buf.findIndex((b) => b === 0xfe || b === 0xfd);
      if (start < 0) {
        this.buf = Buffer.alloc(0);
        return out;
      }
      if (start > 0) this.buf = this.buf.subarray(start);
      const v2 = this.buf[0] === 0xfd;
      const header = v2 ? 10 : 6;
      if (this.buf.length < header) return out;
      const len = this.buf[1] as number;
      const signed = v2 && ((this.buf[2] as number) & 1) !== 0;
      const total = header + len + 2 + (signed ? 13 : 0);
      if (this.buf.length < total) return out;
      const frame = this.buf.subarray(0, total);
      this.buf = this.buf.subarray(total);
      const id = v2 ? frame.readUIntLE(7, 3) : (frame[5] as number);
      const info = MESSAGES[id];
      if (!info) continue; // unknown message: its CRC can't be checked
      let crc = crc16(frame.subarray(1, header + len));
      crc = crc16(Uint8Array.of(info.extra), crc);
      if (crc !== frame.readUInt16LE(header + len)) {
        // not a frame (or corrupted): resync after this magic byte
        this.buf = Buffer.concat([frame.subarray(1), this.buf]);
        continue;
      }
      const payload = Buffer.alloc(Math.max(info.length, len));
      frame.copy(payload, 0, header, header + len);
      out.push({
        id,
        system: v2 ? (frame[5] as number) : (frame[3] as number),
        component: v2 ? (frame[6] as number) : (frame[4] as number),
        payload,
      });
    }
  }
}

/** Encodes a MAVLink v1 frame (sent by the bridge as system 255, GCS). */
export function encode(id: number, payload: Buffer, seq: number, system = 255, component = 190): Buffer {
  const info = MESSAGES[id];
  if (!info) throw new Error(`unknown MAVLink message ${id}`);
  const frame = Buffer.alloc(6 + payload.length + 2);
  frame[0] = 0xfe;
  frame[1] = payload.length;
  frame[2] = seq & 0xff;
  frame[3] = system;
  frame[4] = component;
  frame[5] = id;
  payload.copy(frame, 6);
  let crc = crc16(frame.subarray(1, 6 + payload.length));
  crc = crc16(Uint8Array.of(info.extra), crc);
  frame.writeUInt16LE(crc, 6 + payload.length);
  return frame;
}

/** COMMAND_LONG payload. */
export function commandLong(command: number, params: readonly number[], target = 1, targetComponent = 1): Buffer {
  const p = Buffer.alloc(33);
  for (let i = 0; i < 7; i++) p.writeFloatLE(params[i] ?? 0, i * 4);
  p.writeUInt16LE(command, 28);
  p[30] = target;
  p[31] = targetComponent;
  p[32] = 0;
  return p;
}

/** REQUEST_DATA_STREAM payload (all streams at rateHz). */
export function requestStreams(rateHz: number, target = 1, targetComponent = 1): Buffer {
  const p = Buffer.alloc(6);
  p.writeUInt16LE(rateHz, 0);
  p[2] = target;
  p[3] = targetComponent;
  p[4] = 0; // MAV_DATA_STREAM_ALL
  p[5] = 1; // start
  return p;
}

// Decoded views of the received messages (units converted to SI).

export function heartbeat(p: Buffer) {
  return { customMode: p.readUInt32LE(0), type: p[4] as number, baseMode: p[6] as number, armed: ((p[6] as number) & 0x80) !== 0 };
}

export function sysStatus(p: Buffer) {
  return { voltage: p.readUInt16LE(14) / 1000, batteryRemaining: p.readInt8(30) };
}

export function gpsRaw(p: Buffer) {
  return { fixType: p[28] as number, satellites: p[29] as number };
}

export function attitude(p: Buffer) {
  return { roll: p.readFloatLE(4), pitch: p.readFloatLE(8), yaw: p.readFloatLE(12) };
}

export function globalPosition(p: Buffer) {
  return {
    lat: p.readInt32LE(4) / 1e7,
    lon: p.readInt32LE(8) / 1e7,
    alt: p.readInt32LE(12) / 1000,
    relativeAlt: p.readInt32LE(16) / 1000,
    vx: p.readInt16LE(20) / 100,
    vy: p.readInt16LE(22) / 100,
    vz: p.readInt16LE(24) / 100,
  };
}

export function vfrHud(p: Buffer) {
  return { airspeed: p.readFloatLE(0), groundspeed: p.readFloatLE(4), alt: p.readFloatLE(8), climb: p.readFloatLE(12) };
}

export function homePosition(p: Buffer) {
  return { lat: p.readInt32LE(0) / 1e7, lon: p.readInt32LE(4) / 1e7, alt: p.readInt32LE(8) / 1000 };
}

export function statusText(p: Buffer): string {
  const end = p.indexOf(0, 1);
  return p.subarray(1, end < 0 ? 51 : Math.min(end, 51)).toString('latin1');
}

export function commandAck(p: Buffer) {
  return { command: p.readUInt16LE(0), result: p[2] as number };
}

/** MISSION_COUNT payload. */
export function missionCount(count: number, target = 1, targetComponent = 1): Buffer {
  const p = Buffer.alloc(4);
  p.writeUInt16LE(count, 0);
  p[2] = target;
  p[3] = targetComponent;
  return p;
}

/** Sequence number asked by MISSION_REQUEST(_INT). */
export function missionRequestSeq(p: Buffer): number {
  return p.readUInt16LE(0);
}

/** MISSION_ITEM_INT payload, global relative-altitude frame. */
export function missionItemInt(
  seq: number,
  command: number,
  lat: number,
  lon: number,
  alt: number,
  params: readonly number[] = [],
  target = 1,
  targetComponent = 1,
): Buffer {
  const p = Buffer.alloc(37);
  for (let i = 0; i < 4; i++) p.writeFloatLE(params[i] ?? 0, i * 4);
  p.writeInt32LE(Math.round(lat * 1e7), 16);
  p.writeInt32LE(Math.round(lon * 1e7), 20);
  p.writeFloatLE(alt, 24);
  p.writeUInt16LE(seq, 28);
  p.writeUInt16LE(command, 30);
  p[32] = target;
  p[33] = targetComponent;
  p[34] = 3; // MAV_FRAME_GLOBAL_RELATIVE_ALT
  p[35] = 0;
  p[36] = 1; // autocontinue
  return p;
}
