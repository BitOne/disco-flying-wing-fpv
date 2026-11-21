import { Socket } from 'node:net';

/** Line client of fake-sc2's control socket: one command, one "ok"/"error" line. */
export class Control {
  private socket: Socket | null = null;
  private buffer = '';
  private waiters: Array<(line: string) => void> = [];

  async connect(port: number, host = '127.0.0.1'): Promise<void> {
    const socket = new Socket();
    await new Promise<void>((resolve, reject) => {
      socket.once('error', reject);
      socket.connect(port, host, () => {
        socket.off('error', reject);
        resolve();
      });
    });
    socket.setEncoding('utf8');
    socket.on('data', (chunk: string) => {
      this.buffer += chunk;
      let nl: number;
      while ((nl = this.buffer.indexOf('\n')) >= 0) {
        const line = this.buffer.slice(0, nl).trim();
        this.buffer = this.buffer.slice(nl + 1);
        this.waiters.shift()?.(line);
      }
    });
    socket.on('close', () => {
      this.socket = null;
      for (const w of this.waiters.splice(0)) w('error closed');
    });
    this.socket = socket;
  }

  get connected(): boolean {
    return this.socket !== null;
  }

  async send(command: string, timeoutMs = 3000): Promise<string> {
    const socket = this.socket;
    if (!socket) throw new Error('control socket not connected');
    return await new Promise<string>((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`no reply to "${command}"`)), timeoutMs);
      this.waiters.push((line) => {
        clearTimeout(timer);
        if (line.startsWith('ok')) resolve(line);
        else reject(new Error(`fake-sc2 "${command}": ${line}`));
      });
      socket.write(`${command}\n`);
    });
  }
}
