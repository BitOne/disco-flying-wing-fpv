/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { connect, type Socket } from 'node:net';
import { mkdtemp, readFile, writeFile, mkdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { listLine, realPath, startFtp, virtualPath } from '../src/ftp.ts';

test('paths stay inside the root', () => {
  assert.equal(virtualPath('/', '../../etc/passwd'), '/etc/passwd');
  assert.equal(virtualPath('/a/b', '../c'), '/a/c');
  assert.equal(virtualPath('/a', '/x/./y'), '/x/y');
  assert.equal(realPath('/srv/ftp', '/etc/passwd'), '/srv/ftp/etc/passwd');
});

test('list lines parse like the Disco\'s', () => {
  const line = listLine('Disco_x.mp4', 1234, new Date(), false);
  // arsdk-ng's list parser and commons-net's Unix parser read this layout
  assert.match(line, /^-rw-r--r-- +1 0 +0 +1234 [A-Z][a-z]{2} [ \d]\d \d\d:\d\d Disco_x\.mp4$/);
  assert.match(listLine('media', 0, new Date(Date.UTC(2015, 1, 1)), true), /^drwxr-xr-x .* Feb +1 +2015 media$/);
});

/** A tiny FTP client: one reply per call. */
class Client {
  private buffer = '';
  private waiting: ((line: string) => void)[] = [];
  private readonly socket: Socket;
  constructor(socket: Socket) {
    this.socket = socket;
    socket.setEncoding('latin1');
    socket.on('data', (c: string) => {
      this.buffer += c;
      let i: number;
      while ((i = this.buffer.indexOf('\r\n')) >= 0) {
        const line = this.buffer.slice(0, i);
        this.buffer = this.buffer.slice(i + 2);
        this.waiting.shift()?.(line);
      }
    });
  }
  next(): Promise<string> { return new Promise((ok) => this.waiting.push(ok)); }
  async send(line: string): Promise<string> {
    const r = this.next();
    this.socket.write(line + '\r\n');
    return r;
  }
  async pasv(): Promise<number> {
    const r = await this.send('PASV');
    const m = /\((\d+),(\d+),(\d+),(\d+),(\d+),(\d+)\)/.exec(r);
    assert.ok(m, r);
    return Number(m[5]) * 256 + Number(m[6]);
  }
}

async function withServer(fn: (c: Client, root: string, port: number) => Promise<void>): Promise<void> {
  const root = await mkdtemp(join(tmpdir(), 'disco-ftp-'));
  const server = await startFtp({ host: '127.0.0.1', port: 0, root });
  const address = server.address();
  assert.ok(address && typeof address !== 'string');
  const socket = connect(address.port, '127.0.0.1');
  const c = new Client(socket);
  assert.match(await c.next(), /^220/);
  try {
    await fn(c, root, address.port);
  } finally {
    socket.destroy();
    server.close();
  }
}

function readAll(port: number): Promise<string> {
  return new Promise((ok) => {
    const s = connect(port, '127.0.0.1');
    let out = '';
    s.setEncoding('latin1');
    s.on('data', (d: string) => { out += d; });
    s.on('close', () => ok(out));
  });
}

test('login, list, store, retrieve, delete', async () => {
  await withServer(async (c, root) => {
    await mkdir(join(root, 'internal_000/Disco/media'), { recursive: true });
    await writeFile(join(root, 'internal_000/Disco/media/Disco_a.jpg'), 'JPEG');
    assert.match(await c.send('USER anonymous'), /^331/);
    assert.match(await c.send('PASS x'), /^230/);
    assert.match(await c.send('TYPE I'), /^200/);
    // LIST of a path, data connection opened after the command
    let port = await c.pasv();
    assert.match(await c.send('LIST /internal_000/Disco/media'), /^150/);
    const listing = await readAll(port);
    assert.match(await c.next(), /^226/);
    assert.match(listing, / 4 .* Disco_a\.jpg\r\n$/);
    // STOR, data connection opened before the command
    port = await c.pasv();
    const up = connect(port, '127.0.0.1');
    await new Promise((ok) => up.on('connect', ok));
    const r = c.send('STOR /flightPlan.mavlink');
    assert.match(await r, /^150/);
    up.end('QGC WPL 120\n');
    assert.match(await c.next(), /^226/);
    assert.equal(await readFile(join(root, 'flightPlan.mavlink'), 'utf8'), 'QGC WPL 120\n');
    assert.match(await c.send('SIZE /flightPlan.mavlink'), /^213 12$/);
    // RETR with a restart offset
    assert.match(await c.send('REST 4'), /^350/);
    port = await c.pasv();
    assert.match(await c.send('RETR internal_000/Disco/media/Disco_a.jpg'), /^150/);
    assert.equal(await readAll(port), '');
    assert.match(await c.next(), /^226/);
    port = await c.pasv();
    assert.match(await c.send('RETR internal_000/Disco/media/Disco_a.jpg'), /^150/);
    assert.equal(await readAll(port), 'JPEG');
    assert.match(await c.next(), /^226/);
    // no way out of the root
    assert.match(await c.send('CWD ../../..'), /^250/);
    assert.match(await c.send('PWD'), /^257 "\/"/);
    assert.match(await c.send('RETR ../../etc/passwd'), /^550/);
    assert.match(await c.send('DELE /internal_000/Disco/media/Disco_a.jpg'), /^250/);
    assert.match(await c.send('DELE /internal_000/Disco/media/Disco_a.jpg'), /^550/);
    assert.match(await c.send('EPSV'), /^229 EPSV ok \(\|\|\|\d+\|\)$/);
    assert.match(await c.send('QUIT'), /^221/);
  });
});
