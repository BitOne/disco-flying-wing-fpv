import { test } from 'node:test';
import assert from 'node:assert/strict';
import { decodePng } from '../src/png.ts';
import { lumaStats } from '../src/imagecheck.ts';
import { tile } from '../src/tileserver.ts';

test('test tiles are 256 px checkerboards the PNG decoder reads back', () => {
  const img = decodePng(tile(17, 67600, 46900));
  assert.equal(img.width, 256);
  assert.equal(img.height, 256);
  const stats = lumaStats(img, [0, 0, 1, 1]);
  assert.ok(stats.stddev > 60, `stddev ${stats.stddev}`);
});
