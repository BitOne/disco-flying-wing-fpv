/**
 * Local map tile server for the end-to-end tests (the app's custom tile
 * URL points at it through the emulator's host address, 10.0.2.2): serves
 * /{z}/{x}/{y}.png as a 256 x 256 high-contrast checkerboard, no real map
 * service involved. /stats answers "served <n> tiles". /delay/<ms> makes
 * every tile wait that long from then on (a slow network: time for a test
 * to stop a download half way); /delay/0 is back to full speed.
 */
import { createServer, type Server } from 'node:http';
import { deflateSync } from 'node:zlib';

const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  return t;
})();

function crc32(buf: Uint8Array): number {
  let c = 0xffffffff;
  for (const b of buf) c = (CRC_TABLE[(c ^ b) & 0xff] as number) ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function chunk(type: string, data: Uint8Array): Buffer {
  const head = Buffer.alloc(8);
  head.writeUInt32BE(data.length, 0);
  head.write(type, 4, 'ascii');
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(Buffer.concat([head.subarray(4), data])), 0);
  return Buffer.concat([head, data, crc]);
}

/** An RGB PNG, [pixel] giving each pixel's colour. */
export function encodePng(width: number, height: number, pixel: (x: number, y: number) => [number, number, number]): Buffer {
  const raw = Buffer.alloc((width * 3 + 1) * height);
  for (let y = 0; y < height; y++) {
    const row = y * (width * 3 + 1);
    raw[row] = 0; // filter: none
    for (let x = 0; x < width; x++) {
      const [r, g, b] = pixel(x, y);
      raw[row + 1 + x * 3] = r;
      raw[row + 2 + x * 3] = g;
      raw[row + 3 + x * 3] = b;
    }
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 2; // RGB
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(raw)),
    chunk('IEND', new Uint8Array(0)),
  ]);
}

/** A test tile: white and dark squares of 32 px, tinted by the tile. */
export function tile(z: number, x: number, y: number): Buffer {
  const tint = ((x + y + z) % 3) * 60;
  return encodePng(256, 256, (px, py) =>
    ((px >> 5) + (py >> 5)) % 2 === 0 ? [255, 255 - tint, 255] : [20, 20 + tint, 60]);
}

export function startTileServer(port: number): { server: Server; served: () => number } {
  let served = 0;
  let delayMs = 0;
  const server = createServer((req, res) => {
    const m = /^\/(\d+)\/(\d+)\/(\d+)\.png$/.exec(req.url ?? '');
    const d = /^\/delay\/(\d+)$/.exec(req.url ?? '');
    if (m) {
      const body = tile(Number(m[1]), Number(m[2]), Number(m[3]));
      setTimeout(() => {
        served++;
        res.writeHead(200, { 'Content-Type': 'image/png' });
        res.end(body);
      }, delayMs);
    } else if (d) {
      delayMs = Number(d[1]);
      res.writeHead(200, { 'Content-Type': 'text/plain' });
      res.end(`delay ${delayMs} ms`);
    } else if (req.url === '/stats') {
      res.writeHead(200, { 'Content-Type': 'text/plain' });
      res.end(`served ${served} tiles`);
    } else {
      res.writeHead(404);
      res.end();
    }
  });
  server.on('error', () => {}); // port taken: another runner serves tiles
  server.listen(port, '0.0.0.0');
  return { server, served: () => served };
}
