import type { Image } from './png.ts';

/** Region as fractions of the image size: [left, top, right, bottom], 0..1. */
export type Region = readonly [number, number, number, number];

function bounds(img: Image, region: Region): { x1: number; y1: number; x2: number; y2: number } {
  const [l, t, r, b] = region;
  const x1 = Math.max(0, Math.floor(l * img.width));
  const y1 = Math.max(0, Math.floor(t * img.height));
  const x2 = Math.min(img.width, Math.ceil(r * img.width));
  const y2 = Math.min(img.height, Math.ceil(b * img.height));
  if (x2 <= x1 || y2 <= y1) throw new Error(`empty region ${region.join(',')}`);
  return { x1, y1, x2, y2 };
}

function luma(img: Image, x: number, y: number): number {
  const i = (y * img.width + x) * img.channels;
  return 0.299 * (img.data[i] ?? 0) + 0.587 * (img.data[i + 1] ?? 0) + 0.114 * (img.data[i + 2] ?? 0);
}

/** Luminance mean and standard deviation (0..255) over a region. */
export function lumaStats(img: Image, region: Region): { mean: number; stddev: number } {
  const { x1, y1, x2, y2 } = bounds(img, region);
  let sum = 0;
  let sum2 = 0;
  let n = 0;
  for (let y = y1; y < y2; y++) {
    for (let x = x1; x < x2; x++) {
      const v = luma(img, x, y);
      sum += v;
      sum2 += v * v;
      n++;
    }
  }
  const mean = sum / n;
  return { mean, stddev: Math.sqrt(Math.max(0, sum2 / n - mean * mean)) };
}

/**
 * Fraction (0..1) of pixels of a region whose luminance differs by more than
 * `threshold`. Images must have the same size (callers retry otherwise, e.g.
 * during a screen rotation).
 */
export function changedFraction(a: Image, b: Image, region: Region, threshold = 16): number {
  if (a.width !== b.width || a.height !== b.height) {
    throw new Error(`image sizes differ: ${a.width}x${a.height} vs ${b.width}x${b.height}`);
  }
  const { x1, y1, x2, y2 } = bounds(a, region);
  let changed = 0;
  let n = 0;
  for (let y = y1; y < y2; y++) {
    for (let x = x1; x < x2; x++) {
      if (Math.abs(luma(a, x, y) - luma(b, x, y)) > threshold) changed++;
      n++;
    }
  }
  return changed / n;
}

/**
 * Side-by-side check: finds the horizontal shift (fraction of the width,
 * searched in [minShift, maxShift]) that best maps `region` (the left eye)
 * onto the rest of the image (the right eye), and returns it with the mean
 * absolute luminance difference (0..255) at that shift. Pixels are sampled
 * every `step` pixels, in the region and in the shift.
 */
export function stereoMatch(
  img: Image,
  region: Region,
  minShift: number,
  maxShift: number,
  step = 2,
): { shift: number; diff: number } {
  const { x1, y1, x2, y2 } = bounds(img, region);
  const lumas = new Float32Array(img.width * img.height);
  for (let y = y1; y < y2; y += step) {
    for (let x = x1; x < img.width; x++) lumas[y * img.width + x] = luma(img, x, y);
  }
  let best = { shift: 0, diff: Infinity };
  const d1 = Math.ceil(minShift * img.width);
  const d2 = Math.min(Math.floor(maxShift * img.width), img.width - x2);
  for (let dx = d1; dx <= d2; dx += step) {
    let sum = 0;
    let n = 0;
    for (let y = y1; y < y2; y += step) {
      const row = y * img.width;
      for (let x = x1; x < x2; x += step) {
        sum += Math.abs((lumas[row + x] as number) - (lumas[row + x + dx] as number));
        n++;
      }
    }
    const diff = sum / n;
    if (diff < best.diff) best = { shift: dx / img.width, diff };
  }
  if (best.diff === Infinity) throw new Error(`no shift in [${minShift}, ${maxShift}] fits region ${region.join(',')}`);
  return best;
}
