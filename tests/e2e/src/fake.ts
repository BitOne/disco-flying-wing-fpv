import { Socket } from 'node:net';

/**
 * Client of fake-sc2's control socket (127.0.0.1:4322 by default): one
 * command per line, one "ok ..." / "error ..." line back.
 */
export class FakeControl {
  private socket: Socket | null = null;
  private buffer = '';
  private waiters: Array<(line: string) => void> = [];

  async connect(port = 4322, host = '127.0.0.1'): Promise<void> {
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
    this.socket = socket;
  }

  /** Sends one command and returns the reply; throws on "error ...". */
  async send(command: string, timeoutMs = 3000): Promise<string> {
    const socket = this.socket;
    if (!socket) throw new Error('fake control socket not connected');
    const reply = await new Promise<string>((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`no reply to "${command}"`)), timeoutMs);
      this.waiters.push((line) => {
        clearTimeout(timer);
        resolve(line);
      });
      socket.write(`${command}\n`);
    });
    if (!reply.startsWith('ok')) throw new Error(`fake-sc2 "${command}": ${reply}`);
    return reply;
  }

  close(): void {
    this.socket?.destroy();
    this.socket = null;
  }
}
