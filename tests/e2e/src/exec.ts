import { execFile } from 'node:child_process';

export interface ExecResult {
  stdout: Buffer;
  stderr: string;
}

/** Runs a command without a shell; rejects with stderr on a non-zero exit. */
export function exec(
  cmd: string,
  args: readonly string[],
  timeoutMs = 60_000,
): Promise<ExecResult> {
  return new Promise((resolve, reject) => {
    execFile(
      cmd,
      args,
      { encoding: 'buffer', maxBuffer: 64 * 1024 * 1024, timeout: timeoutMs },
      (error, stdout, stderr) => {
        const err = stderr.toString('utf8');
        if (error) {
          reject(new Error(`${cmd} ${args.join(' ')} failed: ${error.message}\n${err}`));
          return;
        }
        resolve({ stdout, stderr: err });
      },
    );
  });
}

export const sleep = (ms: number): Promise<void> =>
  new Promise((resolve) => setTimeout(resolve, ms));
