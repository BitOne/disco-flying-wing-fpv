/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 *
 * Renders index.html in headless Chromium on the GPU, lets the tiles load,
 * then reports the WebGL renderer, the frame rate over 10 s and saves a
 * screenshot: the CesiumJS row of the simulator comparison (NOTES.md 25).
 *
 *   node --experimental-strip-types probe.ts <out.png> [lat lon alt]
 */
import { chromium } from 'playwright-core';
import { createServer } from 'node:http';
import { readFileSync } from 'node:fs';

const [out = '/tmp/cesium.png', lat = '45.815', lon = '4.818', alt = '50'] = process.argv.slice(2);
const html = readFileSync(new URL('./index.html', import.meta.url));
const server = createServer((_, res) => res.writeHead(200, { 'content-type': 'text/html' }).end(html));
await new Promise<void>((r) => server.listen(8093, '127.0.0.1', r));

const browser = await chromium.launch({
  executablePath: process.env.CHROMIUM,
  args: ['--use-angle=vulkan', '--enable-features=Vulkan', '--enable-gpu', '--ignore-gpu-blocklist',
    '--enable-unsafe-swiftshader=false'],
});
const page = await browser.newPage({ viewport: { width: 1280, height: 720 } });
page.on('pageerror', (e) => console.error('page error:', e.message));
await page.goto(`http://127.0.0.1:8093/?lat=${lat}&lon=${lon}&alt=${alt}`);
await page.waitForFunction(() => (window as any).probe?.ready, null, { timeout: 90000 });
await page.waitForTimeout(15000); // tiles
const f0 = await page.evaluate(() => (window as any).probe.frames);
await page.waitForTimeout(10000);
const probe = await page.evaluate(() => (window as any).probe);
console.log(JSON.stringify({ renderer: probe.renderer, buildings: probe.buildings, error: probe.error,
  fps: (probe.frames - f0) / 10 }));
await page.screenshot({ path: out });
await browser.close();
server.close();
