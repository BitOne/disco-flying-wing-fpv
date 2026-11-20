/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 *
 * Reads the clock code that the fake camera draws in each frame
 * (docker/video-src/video-clock.py, CLOCK_CODE=1) back from a screenshot:
 * a row of 26 equal square blocks, a white and a black reference, then the
 * wall clock in milliseconds modulo 2^24, most significant bit first, white
 * for 1. The app may scale and crop the video anyhow: the row is found by
 * its shape (26 blocks of one width, white then black first).
 */

export const BITS = 24;
const BLOCKS = BITS + 2;
export const MODULO = 2 ** BITS;

/** A screenshot as 8-bit luminance, row by row. */
export interface Luma {
  width: number;
  height: number;
  data: Uint8Array;
}

/**
 * Luminance from Android's raw `screencap` output (no -p): a header of
 * width, height, pixel format (1 = RGBA_8888) and, since Android 9, the
 * colour space (4 little-endian 32-bit words), then RGBA pixels.
 */
export function lumaFromScreencap(raw: Buffer): Luma {
  const width = raw.readUInt32LE(0);
  const height = raw.readUInt32LE(4);
  const format = raw.readUInt32LE(8);
  if (format !== 1) throw new Error(`screencap pixel format ${format}, expected RGBA_8888`);
  const header = raw.length - width * height * 4;
  if (header !== 12 && header !== 16) throw new Error(`screencap: unexpected size ${raw.length}`);
  const data = new Uint8Array(width * height);
  for (let i = 0, p = header; i < data.length; i++, p += 4) {
    data[i] = ((raw[p]! * 3 + raw[p + 1]! * 6 + raw[p + 2]!) / 10) | 0;
  }
  return { width, height, data };
}

/** Luminance from a decoded PNG (RGB or RGBA). */
export function lumaFromImage(img: { width: number; height: number; channels: 3 | 4; data: Uint8Array }): Luma {
  const data = new Uint8Array(img.width * img.height);
  for (let i = 0, p = 0; i < data.length; i++, p += img.channels) {
    data[i] = ((img.data[p]! * 3 + img.data[p + 1]! * 6 + img.data[p + 2]!) / 10) | 0;
  }
  return { width: img.width, height: img.height, data };
}

/** Where the code row is: its left end, block width and centre row. */
export interface CodeRow {
  x: number;
  y: number;
  block: number;
}

/** Runs of dark and light pixels along row [y]: [start, length, light]. */
function runs(img: Luma, y: number): Array<[number, number, boolean]> {
  const out: Array<[number, number, boolean]> = [];
  const row = y * img.width;
  let start = 0;
  let light = img.data[row]! >= 128;
  for (let x = 1; x <= img.width; x++) {
    const l = x < img.width ? img.data[row + x]! >= 128 : !light;
    if (l !== light) {
      out.push([start, x - start, light]);
      start = x;
      light = l;
    }
  }
  return out;
}

/**
 * The code row along [y], if one is there: from a light run followed by a
 * dark one of the same width w, the following runs (up to 26 w in all) must
 * all be whole multiples of w, and the code must end with a run boundary.
 */
export function findInRow(img: Luma, y: number): CodeRow | null {
  const r = runs(img, y);
  for (let i = 0; i + 1 < r.length; i++) {
    const [s0, w0, l0] = r[i]!;
    const w1 = r[i + 1]![1];
    if (!l0) continue;
    // the black reference may have merged with the zero bits after it (n
    // blocks), the white one with light pixels before it: try the widest
    // block first, a narrower one would fit any code of the wider
    for (let n = 1; n <= BITS + 1; n++) {
      const block = w1 / n;
      if (block < 8) break;
      if (w0 < block * 0.8) continue; // edges blur: a little narrower is fine
      const x0 = s0 + w0 - block;
      const end = x0 + BLOCKS * block;
      if (end > img.width + 1) continue;
      if (fits(r, i + 2, x0 + block + w1, end, block)) return { x: x0, y, block };
    }
  }
  return null;
}

/** Whether runs from [j] on, starting at [x], are whole blocks up to [end]. */
function fits(r: Array<[number, number, boolean]>, j: number, x: number, end: number, block: number): boolean {
  let count = 0;
  while (x < end - block / 2) {
    const run = r[j];
    if (!run) return false;
    const n = Math.round(run[1] / block);
    // the last block may merge with what follows: overshoot allowed there;
    // the others end on a block boundary, within a few pixels whatever
    // their length (long uniform areas of the picture don't)
    const last = x + run[1] >= end - block / 2;
    if (!last && (n < 1 || Math.abs(run[1] - n * block) > Math.max(3, block * 0.12))) return false;
    x += run[1];
    j++;
    count++;
  }
  // a time has a few transitions (all but a few in a thousand codes): at
  // least 4 runs after the references
  return count >= 4;
}

/** Finds the code row: the middle of the rows where the shape matches. */
export function findCode(img: Luma): CodeRow | null {
  const hits: CodeRow[] = [];
  for (let y = 0; y < img.height; y += 2) {
    const c = findInRow(img, y);
    if (c) hits.push(c);
  }
  if (hits.length === 0) return null;
  // the longest band of consecutive hits with the same geometry
  let best: CodeRow[] = [];
  let band: CodeRow[] = [];
  for (const h of hits) {
    const prev = band[band.length - 1];
    if (prev && h.y - prev.y <= 4 && Math.abs(h.x - prev.x) < prev.block / 3) band.push(h);
    else band = [h];
    if (band.length > best.length) best = band.slice();
  }
  if (best.length < 3) return null;
  return best[Math.floor(best.length / 2)]!;
}

/** [img] turned a quarter turn, clockwise or not. */
export function rotate(img: Luma, clockwise: boolean): Luma {
  const { width: w, height: h } = img;
  const data = new Uint8Array(w * h);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      // clockwise: (x, y) goes to (h - 1 - y, x) in an h-wide image
      const nx = clockwise ? h - 1 - y : y;
      const ny = clockwise ? x : w - 1 - x;
      data[ny * h + nx] = img.data[y * w + x]!;
    }
  }
  return { width: h, height: w, data };
}

/**
 * The times read in a screenshot taken in any orientation (the emulator's
 * own screenshots are of the portrait framebuffer, whatever the app's): a
 * wrong orientation reads the code backwards and may still look like one,
 * so all readings are returned; the right one is close to the capture time.
 */
export function readScreen(img: Luma): number[] {
  const out: number[] = [];
  for (const view of [img, rotate(img, true), rotate(img, false)]) {
    const at = findCode(view);
    if (at) out.push(readCode(view, at));
  }
  return out;
}

/** Of [readings], the one closest before [time], or null (none within [maxMs]). */
export function closest(readings: number[], time: number, maxMs = 5000): number | null {
  let best: number | null = null;
  for (const r of readings) {
    const d = clockDiff(time, r);
    if (d > -1000 && d < maxMs && (best === null || Math.abs(d) < Math.abs(clockDiff(time, best)))) best = r;
  }
  return best;
}

/** The time in the code (ms modulo 2^24), sampled at the blocks' centres. */
export function readCode(img: Luma, at: CodeRow): number {
  const sample = (k: number): number => {
    const cx = Math.round(at.x + (k + 0.5) * at.block);
    let sum = 0;
    let n = 0;
    const half = Math.max(1, Math.floor(at.block / 4));
    for (let dy = -half; dy <= half; dy++) {
      for (let dx = -half; dx <= half; dx++) {
        const x = cx + dx;
        const y = at.y + dy;
        if (x >= 0 && x < img.width && y >= 0 && y < img.height) {
          sum += img.data[y * img.width + x]!;
          n++;
        }
      }
    }
    return sum / n;
  };
  const threshold = (sample(0) + sample(1)) / 2;
  let ms = 0;
  for (let i = 0; i < BITS; i++) ms = ms * 2 + (sample(i + 2) > threshold ? 1 : 0);
  return ms;
}

/** [later] minus [earlier] on the 2^24 ms clock, in (-2^23, 2^23]. */
export function clockDiff(later: number, earlier: number): number {
  let d = (later - earlier) % MODULO;
  if (d > MODULO / 2) d -= MODULO;
  if (d <= -MODULO / 2) d += MODULO;
  return d;
}
