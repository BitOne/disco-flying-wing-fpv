import { test } from 'node:test';
import assert from 'node:assert/strict';
import { deflateSync } from 'node:zlib';
import { changedFraction, lumaStats, stereoMatch } from '../src/imagecheck.ts';
import { decodePng, type Image } from '../src/png.ts';

// CRC-32 for PNG chunks.
const CRC_TABLE = Array.from({ length: 256 }, (_, n) => {
  let c = n;
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  return c >>> 0;
});
function crc32(buf: Buffer): number {
  let c = 0xffffffff;
  for (const b of buf) c = (CRC_TABLE[(c ^ b) & 0xff] as number) ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}
function chunk(type: string, body: Buffer): Buffer {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(body.length);
  const tb = Buffer.concat([Buffer.from(type, 'latin1'), body]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(tb));
  return Buffer.concat([len, tb, crc]);
}

/** Encodes RGBA pixels, cycling through the 5 PNG row filters. */
function encodePng(img: Image): Buffer {
  const { width, height, channels, data } = img;
  const stride = width * channels;
  const rows: number[] = [];
  const at = (y: number, x: number): number => (y < 0 || x < 0 ? 0 : (data[y * stride + x] ?? 0));
  for (let y = 0; y < height; y++) {
    const filter = y % 5;
    rows.push(filter);
    for (let x = 0; x < stride; x++) {
      const v = at(y, x);
      const left = at(y, x - channels);
      const up = at(y - 1, x);
      const upLeft = at(y - 1, x - channels);
      const p = left + up - upLeft;
      const pa = Math.abs(p - left), pb = Math.abs(p - up), pc = Math.abs(p - upLeft);
      const paeth = pa <= pb && pa <= pc ? left : pb <= pc ? up : upLeft;
      const pred = [0, left, up, (left + up) >> 1, paeth][filter] as number;
      rows.push((v - pred) & 0xff);
    }
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;
  ihdr[9] = channels === 4 ? 6 : 2;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(Buffer.from(rows))),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

function image(width: number, height: number, pixel: (x: number, y: number) => [number, number, number]): Image {
  const data = new Uint8Array(width * height * 4);
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const [r, g, b] = pixel(x, y);
      data.set([r, g, b, 255], (y * width + x) * 4);
    }
  }
  return { width, height, channels: 4, data };
}

test('decodePng round-trips all row filters', () => {
  const src = image(13, 11, (x, y) => [(x * 37 + y * 11) & 255, (x * y * 7) & 255, (255 - x * 19) & 255]);
  const out = decodePng(encodePng(src));
  assert.equal(out.width, 13);
  assert.equal(out.height, 11);
  assert.equal(out.channels, 4);
  assert.deepEqual(Array.from(out.data), Array.from(src.data));
});

test('decodePng rejects non-PNG data', () => {
  assert.throws(() => decodePng(Buffer.from('hello world')), /not a PNG/);
});

test('lumaStats: uniform region has no contrast, stripes have', () => {
  const black = image(20, 20, () => [0, 0, 0]);
  assert.deepEqual(lumaStats(black, [0, 0, 1, 1]), { mean: 0, stddev: 0 });
  const stripes = image(20, 20, (x) => (x % 2 ? [255, 255, 255] : [0, 0, 0]));
  assert.ok(lumaStats(stripes, [0, 0, 1, 1]).stddev > 100);
});

test('changedFraction counts changed pixels in the region only', () => {
  const a = image(10, 10, () => [0, 0, 0]);
  const b = image(10, 10, (x, y) => (x < 5 && y < 5 ? [255, 255, 255] : [0, 0, 0]));
  assert.equal(changedFraction(a, b, [0, 0, 1, 1]), 0.25);
  assert.equal(changedFraction(a, b, [0.5, 0.5, 1, 1]), 0);
  assert.equal(changedFraction(a, a, [0, 0, 1, 1]), 0);
});

test('changedFraction refuses images of different sizes', () => {
  assert.throws(() => changedFraction(image(4, 4, () => [0, 0, 0]), image(4, 5, () => [0, 0, 0]), [0, 0, 1, 1]), /sizes differ/);
});

test('stereoMatch finds the shift between two identical eyes', () => {
  // left eye: a gradient blob at x 10..30; right eye: the same at x 55..75
  const blob = (x: number, y: number): [number, number, number] => {
    const v = (x * 13 + y * 7) & 255;
    return [v, 255 - v, v];
  };
  const img = image(100, 20, (x, y) => {
    if (x >= 10 && x < 30) return blob(x - 10, y);
    if (x >= 55 && x < 75) return blob(x - 55, y);
    return [0, 0, 0];
  });
  const m = stereoMatch(img, [0.05, 0, 0.35, 1], 0.3, 0.6, 1);
  assert.equal(m.shift, 0.45);
  assert.equal(m.diff, 0);
  // a different right eye does not match
  const other = image(100, 20, (x, y) => (x >= 10 && x < 30 ? blob(x - 10, y) : [0, 0, 0]));
  assert.ok(stereoMatch(other, [0.05, 0, 0.35, 1], 0.3, 0.6, 1).diff > 30);
});
