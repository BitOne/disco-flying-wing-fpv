import { writeFile } from 'node:fs/promises';
import { exec, sleep } from './exec.ts';
import { parseUiDump, textsOf, type UiNode } from './uidump.ts';

/** Drives the emulator running in the `disco-fpv-emu` container (make emu). */
export class Emulator {
  readonly container: string;
  readonly serial: string;

  constructor(container = 'disco-fpv-emu', serial = 'emulator-5554') {
    this.container = container;
    this.serial = serial;
  }

  /**
   * Runs an adb command; on failure, waits for the device and tries once
   * more: adbd restarts when Android changes its USB functions, e.g. while
   * the emulated SkyController is plugged in again (USB mode).
   */
  async adb(args: readonly string[], timeoutMs?: number): Promise<Buffer> {
    const run = async (): Promise<Buffer> =>
      (await exec('docker', ['exec', this.container, 'adb', '-s', this.serial, ...args], timeoutMs)).stdout;
    try {
      return await run();
    } catch {
      await exec('docker', ['exec', this.container, 'adb', '-s', this.serial, 'wait-for-device'], 30_000)
        .catch(() => undefined);
      return await run();
    }
  }

  async shell(command: string): Promise<string> {
    return (await this.adb(['shell', command])).toString('utf8').trim();
  }

  async isBooted(): Promise<boolean> {
    try {
      return (await this.shell('getprop sys.boot_completed')) === '1';
    } catch {
      return false;
    }
  }

  /** Starts the app as the launcher does (so resumeApp() finds its task). */
  async startApp(pkg: string, activity: string): Promise<void> {
    await this.shell(
      `am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000 -n ${pkg}/${activity}`,
    );
  }

  /** Brings the app's task back to the front, as the launcher icon does. */
  async resumeApp(pkg: string): Promise<void> {
    await this.shell(`monkey -p ${pkg} -c android.intent.category.LAUNCHER 1`);
  }

  /** Fixed screen rotation: 0 portrait, 1 landscape (auto-rotate off). */
  async rotate(rotation: 0 | 1): Promise<void> {
    await this.shell(`settings put system accelerometer_rotation 0; settings put system user_rotation ${rotation}`);
  }

  async home(): Promise<void> {
    await this.shell('input keyevent KEYCODE_HOME');
  }

  /** Clears the crash and event logs (start of a scenario). */
  async clearProblems(): Promise<void> {
    await this.shell('logcat -c -b crash -b events');
  }

  /** Crashes and ANRs of `pkg` since clearProblems(), one line each. */
  async problems(pkg: string): Promise<string[]> {
    const log = await this.shell('logcat -d -b crash -b events');
    return log.split('\n').filter((line) =>
      (line.includes('am_crash') || line.includes('am_anr') || line.includes('Process: ')) &&
      line.includes(pkg));
  }

  async stopApp(pkg: string): Promise<void> {
    await this.shell(`am force-stop ${pkg}`);
    // a system screen the app opened (the file picker) outlives it: a
    // scenario failing there would leave it over every later one
    await this.shell('am force-stop com.google.android.documentsui; am force-stop com.android.documentsui');
  }

  async screenshot(path: string): Promise<void> {
    await writeFile(path, await this.adb(['exec-out', 'screencap', '-p']));
  }

  async dumpUi(): Promise<UiNode[]> {
    // uiautomator occasionally fails while the UI is changing: retry.
    for (let attempt = 1; ; attempt++) {
      try {
        // uiautomator prints "ERROR: could not get idle state." and exits 0
        // without writing when the UI never settles (live data): never read
        // a previous dump in that case
        const out = await this.shell('rm -f /sdcard/disco-fpv-ui.xml; uiautomator dump /sdcard/disco-fpv-ui.xml');
        if (out.includes('ERROR')) throw new Error(`uiautomator: ${out}`);
        const xml = (await this.adb(['exec-out', 'cat', '/sdcard/disco-fpv-ui.xml'])).toString('utf8');
        return parseUiDump(xml);
      } catch (error) {
        if (attempt >= 3) throw error;
        await sleep(500);
      }
    }
  }

  async screenSize(): Promise<{ width: number; height: number }> {
    const m = /(\d+)x(\d+)/.exec(await this.shell('wm size'));
    return { width: Number(m?.[1] ?? 1080), height: Number(m?.[2] ?? 2400) };
  }

  async tap(x: number, y: number): Promise<void> {
    await this.shell(`input tap ${Math.round(x)} ${Math.round(y)}`);
  }

  /**
   * Taps the element whose text equals `text`, else the first one
   * containing it; not on screen, it scrolls down to look for it (long
   * screens such as the settings), at most `maxScrolls` times, then from
   * the top again (it may be above).
   */
  async tapText(text: string, maxScrolls = 6): Promise<void> {
    const { height } = await this.screenSize();
    for (let i = 0; ; i++) {
      if (maxScrolls > 0 && i === maxScrolls + 1) await this.scrollToTop();
      const nodes = await this.dumpUi();
      const node =
        nodes.find((n) => n.text === text || n.contentDesc === text) ??
        nodes.find((n) => n.text.includes(text) || n.contentDesc.includes(text));
      if (node) {
        const { x1, y1, x2, y2 } = node.bounds;
        await this.tap((x1 + x2) / 2, (y1 + y2) / 2);
        return;
      }
      if (maxScrolls === 0 || i >= 2 * maxScrolls + 1) throw new Error(`no element with text "${text}" on screen`);
      await this.swipe(height * 0.75, height * 0.4);
      await sleep(300);
    }
  }

  /**
   * Taps the element whose resource id is `id` or ends with ":id/<id>",
   * `times` times: one UI dump, then the taps (a dump takes seconds and
   * loads the device: a hundred of them stalled the headset view's GL
   * thread in the emulator).
   */
  async tapId(id: string, times = 1): Promise<void> {
    const nodes = await this.dumpUi();
    const node = nodes.find((n) => n.resourceId === id || n.resourceId.endsWith(`:id/${id}`));
    if (!node) throw new Error(`no element with id "${id}" on screen`);
    const { x1, y1, x2, y2 } = node.bounds;
    for (let i = 0; i < times; i++) {
      await this.tap((x1 + x2) / 2, (y1 + y2) / 2);
      if (i + 1 < times) await sleep(150);
    }
  }

  async back(): Promise<void> {
    await this.shell('input keyevent 4');
  }

  private async swipe(fromY: number, toY: number): Promise<void> {
    const { width } = await this.screenSize();
    await this.shell(`input swipe ${width / 2} ${fromY} ${width / 2} ${toY} 400`);
  }

  async scrollToTop(): Promise<void> {
    const { height } = await this.screenSize();
    for (let i = 0; i < 8; i++) await this.swipe(height * 0.35, height * 0.9);
  }

  /**
   * Collects the texts of a scrolling screen: dumps, scrolls down, repeats
   * until no new text appears (or maxPages), then scrolls back to the top.
   */
  async collectTexts(scroll: boolean, maxPages = 10): Promise<string[]> {
    const seen = new Set<string>();
    const ordered: string[] = [];
    const { height } = await this.screenSize();
    for (let page = 0; page < (scroll ? maxPages : 1); page++) {
      let added = 0;
      for (const t of textsOf(await this.dumpUi())) {
        if (!seen.has(t)) {
          seen.add(t);
          ordered.push(t);
          added++;
        }
      }
      if (!scroll || (page > 0 && added === 0)) break;
      await this.swipe(height * 0.75, height * 0.4);
      await sleep(300);
    }
    if (scroll) await this.scrollToTop();
    return ordered;
  }
}
