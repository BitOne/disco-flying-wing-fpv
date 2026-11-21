/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 *
 * The fake Disco's FTP servers, on the address fake-sc2 gives GroundSdk's
 * "drone" host (127.0.0.2 by default), with the Disco's ports:
 *   21  <root>/data, the Disco's /data/ftp: internal_000/Disco/media,
 *       thumb, academy, and internal_000/gps_data (GPS ephemeris uploads)
 *   51  <root>/update, firmware update: the .plf the updater uploads
 *   61  <root>/flightplan: the MAVLink file the app uploads before Mavlink.Start
 * Everything lives under one directory shared with fake-sc2 (it writes the
 * media and flight data there, and reads the flight plans).
 *
 *   node src/main.ts --root <dir> [--host 127.0.0.2] [--base-port 0]
 *                    [--pasv-address 192.168.42.1]
 *
 * --base-port N serves on N+21, N+51, N+61 instead (tests, no root).
 */
import { chown, mkdir, stat } from 'node:fs/promises';
import { startFtp } from './ftp.ts';

function arg(name: string, fallback: string): string {
  const i = process.argv.indexOf(name);
  return i >= 0 && process.argv[i + 1] !== undefined ? process.argv[i + 1]! : fallback;
}

const root = arg('--root', '/fs');
const host = arg('--host', '127.0.0.2');
const base = Number(arg('--base-port', '0'));
// what the Disco answers to PASV: its own address on its Wi-Fi
const pasvAddress = arg('--pasv-address', '192.168.42.1');

await mkdir(root, { recursive: true });
const rootStat = await stat(root);
const owner = { uid: rootStat.uid, gid: rootStat.gid };
const log = (line: string): void => console.log(line);

// separate trees, as on the Disco: data (its /data/ftp), update, flightplan
const services: [number, string][] = [
  [21, `${root}/data`],
  [51, `${root}/update`],
  [61, `${root}/flightplan`],
];
for (const dir of ['data/internal_000/Disco/media', 'data/internal_000/Disco/thumb',
  'data/internal_000/Disco/academy', 'data/internal_000/gps_data', 'update', 'flightplan']) {
  let path = root;
  for (const part of dir.split('/')) {
    path += `/${part}`;
    await mkdir(path, { recursive: true });
    // the rig user's, not root's (this runs as root to bind port 21)
    await chown(path, owner.uid, owner.gid).catch(() => {});
  }
}
for (const [port, dir] of services) {
  await startFtp({ host, port: base + port, root: dir, owner, log, pasvAddress });
  log(`disco-ftp listening on ${host}:${base + port} for ${dir}`);
}
