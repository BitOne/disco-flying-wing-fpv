/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { BITS, clockDiff, closest, findCode, type Luma, MODULO, readCode, readScreen, rotate } from '../src/clockcode.ts';

/** A screen with the code drawn at (x, y), blocks of [block] px, over noise. */
function screen(ms: number, x: number, y: number, block: number, width = 1200, height = 500): Luma {
  const data = new Uint8Array(width * height);
  let seed = 7;
  for (let i = 0; i < data.length; i++) {
    seed = (seed * 1103515245 + 12345) & 0x7fffffff;
    data[i] = 60 + (seed % 140); // mid-grey noise: no long clean runs
  }
  const bits = [1, 0];
  for (let i = BITS - 1; i >= 0; i--) bits.push((ms >> i) & 1);
  bits.forEach((b, k) => {
    for (let yy = y; yy < y + block; yy++) {
      for (let xx = Math.round(x + k * block); xx < Math.round(x + (k + 1) * block); xx++) {
        data[yy * width + xx] = b ? 235 : 16;
      }
    }
  });
  return { width, height, data };
}

test('finds and reads the code at any scale and place', () => {
  for (const [ms, x, y, block] of [
    [123456, 40, 100, 28],
    [MODULO - 0x0f0f1, 10, 300, 40.5],
    [0x555555, 100, 50, 30],
    [0x3a5c91, 33, 200, 17],
  ] as const) {
    const img = screen(ms, x, y, block);
    const at = findCode(img);
    assert.ok(at, `code at ${x},${y} block ${block}`);
    assert.ok(Math.abs(at.block - block) < 1.5);
    assert.equal(readCode(img, at), ms);
  }
});

test('no code, no row', () => {
  const img = screen(5, 0, 0, 1);
  img.data.fill(128);
  assert.equal(findCode(img), null);
});

test('clock differences wrap around 2^24 ms', () => {
  assert.equal(clockDiff(100, 50), 50);
  assert.equal(clockDiff(10, MODULO - 20), 30);
  assert.equal(clockDiff(MODULO - 20, 10), -30);
});

test('reads the code from a screenshot of the portrait framebuffer', () => {
  const img = screen(0x2b7e15, 60, 120, 24);
  assert.equal(closest(readScreen(rotate(img, true)), 0x2b7e15 + 150), 0x2b7e15);
  assert.equal(closest(readScreen(rotate(img, false)), 0x2b7e15 + 150), 0x2b7e15);
  assert.equal(closest([0x2b7e15], 0x2b7e15 + 60_000), null);
});
