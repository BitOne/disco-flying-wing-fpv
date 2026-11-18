import { inflateSync } from 'node:zlib';

/** Decoded image: 8-bit RGB or RGBA pixels, row-major. */
export interface Image {
  width: number;
  height: number;
  channels: 3 | 4;
  data: Uint8Array;
}

const SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

function paeth(a: number, b: number, c: number): number {
  const p = a + b - c;
  const pa = Math.abs(p - a);
  const pb = Math.abs(p - b);
  const pc = Math.abs(p - c);
  if (pa <= pb && pa <= pc) return a;
  return pb <= pc ? b : c;
}

/**
 * Minimal PNG decoder for screenshots (adb screencap): 8-bit RGB/RGBA,
 * non-interlaced. Throws on anything else.
 */
export function decodePng(file: Uint8Array): Image {
  const buf = Buffer.from(file.buffer, file.byteOffset, file.byteLength);
  if (!buf.subarray(0, 8).equals(SIGNATURE)) throw new Error('not a PNG file');
  let width = 0;
  let height = 0;
  let channels: 3 | 4 = 4;
  const idat: Buffer[] = [];
  let offset = 8;
  while (offset + 8 <= buf.length) {
    const length = buf.readUInt32BE(offset);
    const type = buf.toString('latin1', offset + 4, offset + 8);
    const body = buf.subarray(offset + 8, offset + 8 + length);
    if (type === 'IHDR') {
      width = body.readUInt32BE(0);
      height = body.readUInt32BE(4);
      const bitDepth = body[8];
      const colorType = body[9];
      const interlace = body[12];
      if (bitDepth !== 8 || (colorType !== 2 && colorType !== 6) || interlace !== 0) {
        throw new Error(`unsupported PNG (bit depth ${bitDepth}, color type ${colorType}, interlace ${interlace})`);
      }
      channels = colorType === 6 ? 4 : 3;
    } else if (type === 'IDAT') {
      idat.push(body);
    } else if (type === 'IEND') {
      break;
    }
    offset += 12 + length;
  }
  if (width === 0 || height === 0) throw new Error('PNG without IHDR');

  const raw = inflateSync(Buffer.concat(idat));
  const stride = width * channels;
  const data = new Uint8Array(height * stride);
  for (let y = 0; y < height; y++) {
    const filter = raw[y * (stride + 1)];
    const src = y * (stride + 1) + 1;
    const dst = y * stride;
    for (let x = 0; x < stride; x++) {
      const value = raw[src + x] ?? 0;
      const left = x >= channels ? (data[dst + x - channels] ?? 0) : 0;
      const up = y > 0 ? (data[dst - stride + x] ?? 0) : 0;
      const upLeft = y > 0 && x >= channels ? (data[dst - stride + x - channels] ?? 0) : 0;
      let out: number;
      switch (filter) {
        case 0: out = value; break;
        case 1: out = value + left; break;
        case 2: out = value + up; break;
        case 3: out = value + ((left + up) >> 1); break;
        case 4: out = value + paeth(left, up, upLeft); break;
        default: throw new Error(`bad PNG filter ${filter} on row ${y}`);
      }
      data[dst + x] = out & 0xff;
    }
  }
  return { width, height, channels, data };
}
