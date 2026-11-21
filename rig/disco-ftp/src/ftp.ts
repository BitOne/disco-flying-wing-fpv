/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 *
 * A small FTP server playing the Disco's: anonymous, passive mode only,
 * the commands GroundSdk's FTP client (Apache commons-net) and FreeFlight
 * send. Each server serves one directory; paths never leave it.
 */
import { createServer, type Server, type Socket } from 'node:net';
import { createReadStream, createWriteStream } from 'node:fs';
import { chown, mkdir, readdir, rename, rm, rmdir, stat, unlink } from 'node:fs/promises';
import { posix, resolve, sep } from 'node:path';

export interface FtpOptions {
  /** Address to listen on (control and data). */
  host: string;
  port: number;
  /** Directory served as "/". */
  root: string;
  /** Owner given to created files and directories (the rig user, not root). */
  owner?: { uid: number; gid: number };
  log?: (line: string) => void;
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** One "ls -l" line, as busybox's ftpd on the Disco writes it. */
export function listLine(name: string, size: number, mtime: Date, dir: boolean): string {
  const now = new Date();
  const sixMonths = 182 * 24 * 3600 * 1000;
  const day = String(mtime.getUTCDate()).padStart(2, ' ');
  const when = Math.abs(now.getTime() - mtime.getTime()) < sixMonths
    ? `${String(mtime.getUTCHours()).padStart(2, '0')}:${String(mtime.getUTCMinutes()).padStart(2, '0')}`
    : String(mtime.getUTCFullYear()).padStart(5, ' ');
  return `${dir ? 'drwxr-xr-x' : '-rw-r--r--'}    1 0        0        ${String(size).padStart(8, ' ')} ` +
    `${MONTHS[mtime.getUTCMonth()]} ${day} ${when} ${name}`;
}

/** A client path (absolute or relative to cwd) to a virtual absolute path, ".." kept inside "/". */
export function virtualPath(cwd: string, arg: string): string {
  const p = posix.normalize(posix.isAbsolute(arg) ? arg : posix.join(cwd, arg));
  const parts: string[] = [];
  for (const part of p.split('/')) {
    if (part === '' || part === '.') continue;
    if (part === '..') parts.pop();
    else parts.push(part);
  }
  return '/' + parts.join('/');
}

export function realPath(root: string, virtual: string): string {
  const base = resolve(root);
  const full = resolve(base, '.' + virtual);
  if (full !== base && !full.startsWith(base + sep)) throw new Error('outside the root');
  return full;
}

class Session {
  private cwd = '/';
  private buffer = '';
  private passive: Server | undefined;
  private pendingData: Socket | undefined;
  private dataWaiters: ((s: Socket) => void)[] = [];
  private restOffset = 0;
  private renameFrom: string | undefined;
  private queue: Promise<void> = Promise.resolve();

  private readonly control: Socket;
  private readonly opts: FtpOptions;

  constructor(control: Socket, opts: FtpOptions) {
    this.control = control;
    this.opts = opts;
    control.setEncoding('latin1');
    control.on('data', (chunk: string) => this.onData(chunk));
    control.on('error', () => {});
    control.on('close', () => this.closePassive());
    this.reply(220, 'Operation successful');
  }

  private log(line: string): void {
    this.opts.log?.(`ftp:${this.opts.port} ${line}`);
  }

  private reply(code: number, text: string): void {
    if (!this.control.destroyed) this.control.write(`${code} ${text}\r\n`);
  }

  private onData(chunk: string): void {
    this.buffer += chunk;
    let i: number;
    while ((i = this.buffer.indexOf('\n')) >= 0) {
      const line = this.buffer.slice(0, i).replace(/\r$/, '');
      this.buffer = this.buffer.slice(i + 1);
      // one command at a time, in order
      this.queue = this.queue.then(() => this.command(line)).catch((e: Error) => {
        this.log(`error ${e.message}`);
        this.reply(550, 'Error');
      });
    }
  }

  private closePassive(): void {
    this.passive?.close();
    this.passive = undefined;
    this.pendingData?.destroy();
    this.pendingData = undefined;
  }

  private openPassive(): Promise<number> {
    this.closePassive();
    return new Promise((ok, fail) => {
      const server = createServer((socket) => {
        const waiter = this.dataWaiters.shift();
        if (waiter) waiter(socket);
        else this.pendingData = socket;
      });
      server.on('error', fail);
      server.listen(0, this.opts.host, () => {
        const address = server.address();
        if (address === null || typeof address === 'string') return fail(new Error('no port'));
        ok(address.port);
      });
      this.passive = server;
    });
  }

  private dataSocket(): Promise<Socket> {
    if (this.pendingData) {
      const s = this.pendingData;
      this.pendingData = undefined;
      return Promise.resolve(s);
    }
    if (!this.passive) return Promise.reject(new Error('no PASV'));
    return new Promise((ok, fail) => {
      const timer = setTimeout(() => fail(new Error('no data connection')), 10_000);
      this.dataWaiters.push((s) => { clearTimeout(timer); ok(s); });
    });
  }

  private async own(path: string): Promise<void> {
    if (this.opts.owner) await chown(path, this.opts.owner.uid, this.opts.owner.gid).catch(() => {});
  }

  private real(arg: string): string {
    return realPath(this.opts.root, virtualPath(this.cwd, arg));
  }

  private async command(line: string): Promise<void> {
    const space = line.indexOf(' ');
    const verb = (space < 0 ? line : line.slice(0, space)).toUpperCase();
    const arg = space < 0 ? '' : line.slice(space + 1);
    this.log(verb === 'PASS' ? 'PASS ***' : line);
    switch (verb) {
      case 'USER': return this.reply(331, 'Specify password');
      case 'PASS': return this.reply(230, 'Login successful');
      case 'SYST': return this.reply(215, 'UNIX Type: L8');
      case 'FEAT': return this.reply(211, 'No features');
      case 'OPTS': case 'TYPE': case 'MODE': case 'STRU': case 'NOOP':
        return this.reply(200, 'Operation successful');
      case 'PWD': case 'XPWD': return this.reply(257, `"${this.cwd}"`);
      case 'CWD': case 'CDUP': {
        const target = virtualPath(this.cwd, verb === 'CDUP' ? '..' : arg || '/');
        const s = await stat(realPath(this.opts.root, target)).catch(() => undefined);
        if (!s?.isDirectory()) return this.reply(550, 'No such directory');
        this.cwd = target;
        return this.reply(250, 'Operation successful');
      }
      case 'PASV': {
        const port = await this.openPassive();
        const h = this.opts.host.split('.').join(',');
        return this.reply(227, `Entering Passive Mode (${h},${port >> 8},${port & 255})`);
      }
      case 'EPSV': {
        const port = await this.openPassive();
        // libmux's FTP proxy looks for this exact text
        return this.reply(229, `EPSV ok (|||${port}|)`);
      }
      case 'REST':
        this.restOffset = Math.max(0, Number.parseInt(arg, 10) || 0);
        return this.reply(350, 'Restart position accepted');
      case 'SIZE': case 'MDTM': {
        const s = await stat(this.real(arg)).catch(() => undefined);
        if (!s?.isFile()) return this.reply(550, 'No such file');
        if (verb === 'SIZE') return this.reply(213, String(s.size));
        const d = s.mtime.toISOString().replace(/[-:T]/g, '').slice(0, 14);
        return this.reply(213, d);
      }
      case 'LIST': case 'NLST': return this.list(verb, arg);
      case 'RETR': return this.retrieve(arg);
      case 'STOR': case 'APPE': return this.store(arg, verb === 'APPE');
      case 'DELE': {
        await unlink(this.real(arg)).then(() => this.reply(250, 'Operation successful'),
          () => this.reply(550, 'No such file'));
        return;
      }
      case 'RMD': case 'XRMD': {
        await rmdir(this.real(arg)).then(() => this.reply(250, 'Operation successful'),
          () => this.reply(550, 'Cannot remove'));
        return;
      }
      case 'MKD': case 'XMKD': {
        const path = this.real(arg);
        await mkdir(path).then(async () => { await this.own(path); this.reply(257, `"${virtualPath(this.cwd, arg)}"`); },
          () => this.reply(550, 'Cannot create'));
        return;
      }
      case 'RNFR': {
        const s = await stat(this.real(arg)).catch(() => undefined);
        if (!s) return this.reply(550, 'No such file');
        this.renameFrom = this.real(arg);
        return this.reply(350, 'Ready for RNTO');
      }
      case 'RNTO': {
        const from = this.renameFrom;
        this.renameFrom = undefined;
        if (!from) return this.reply(503, 'RNFR first');
        await rename(from, this.real(arg)).then(() => this.reply(250, 'Operation successful'),
          () => this.reply(550, 'Cannot rename'));
        return;
      }
      case 'QUIT':
        this.reply(221, 'Goodbye');
        this.control.end();
        return;
      default:
        return this.reply(502, 'Command not implemented');
    }
  }

  private async list(verb: string, arg: string): Promise<void> {
    // "LIST -la path": options dropped
    const path = arg.split(' ').filter((a) => !a.startsWith('-')).join(' ');
    const full = this.real(path || '.');
    const s = await stat(full).catch(() => undefined);
    let lines: string[] = [];
    if (s?.isDirectory()) {
      const names = (await readdir(full)).sort();
      for (const name of names) {
        const e = await stat(`${full}/${name}`).catch(() => undefined);
        if (!e) continue;
        lines.push(verb === 'NLST' ? name : listLine(name, e.size, e.mtime, e.isDirectory()));
      }
    } else if (s) {
      const name = posix.basename(virtualPath(this.cwd, path));
      lines = [verb === 'NLST' ? name : listLine(name, s.size, s.mtime, false)];
    }
    this.reply(150, 'Here comes the directory listing');
    const data = await this.dataSocket();
    data.end(lines.map((l) => l + '\r\n').join(''), 'latin1');
    await new Promise<void>((ok) => data.on('close', () => ok()));
    this.closePassive();
    this.reply(226, 'Operation successful');
  }

  private async retrieve(arg: string): Promise<void> {
    const full = this.real(arg);
    const s = await stat(full).catch(() => undefined);
    if (!s?.isFile()) {
      this.restOffset = 0;
      return this.reply(550, 'No such file');
    }
    const start = this.restOffset;
    this.restOffset = 0;
    this.reply(150, `Opening BINARY connection for ${posix.basename(arg)} (${s.size} bytes)`);
    const data = await this.dataSocket();
    await new Promise<void>((ok) => {
      const input = createReadStream(full, { start });
      input.on('error', () => data.destroy());
      data.on('close', () => ok());
      input.pipe(data);
    });
    this.closePassive();
    this.reply(226, 'Operation successful');
  }

  private async store(arg: string, append: boolean): Promise<void> {
    const full = this.real(arg);
    const start = this.restOffset;
    this.restOffset = 0;
    this.reply(150, 'Ok to send data');
    const data = await this.dataSocket();
    const out = createWriteStream(full, append ? { flags: 'a' } : start > 0 ? { flags: 'r+', start } : {});
    const ok = await new Promise<boolean>((done) => {
      out.on('error', () => { data.destroy(); done(false); });
      out.on('finish', () => done(true));
      data.pipe(out);
    });
    await this.own(full);
    this.closePassive();
    if (ok) this.log(`stored ${virtualPath(this.cwd, arg)}`);
    this.reply(ok ? 226 : 550, ok ? 'Operation successful' : 'Cannot write');
  }
}

/** Starts one server; resolves once it listens. */
export function startFtp(opts: FtpOptions): Promise<Server> {
  return new Promise((ok, fail) => {
    const server = createServer((socket) => new Session(socket, opts));
    server.on('error', fail);
    server.listen(opts.port, opts.host, () => ok(server));
  });
}

/** Removes a tree (tests, resets). */
export async function clear(path: string): Promise<void> {
  await rm(path, { recursive: true, force: true });
}
