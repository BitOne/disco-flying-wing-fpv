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

  async adb(args: readonly string[], timeoutMs?: number): Promise<Buffer> {
    const { stdout } = await exec(
      'docker',
      ['exec', this.container, 'adb', '-s', this.serial, ...args],
      timeoutMs,
    );
    return stdout;
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
  }

  async screenshot(path: string): Promise<void> {
    await writeFile(path, await this.adb(['exec-out', 'screencap', '-p']));
  }

  async dumpUi(): Promise<UiNode[]> {
    // uiautomator occasionally fails while the UI is changing: retry.
    for (let attempt = 1; ; attempt++) {
      try {
        await this.shell('uiautomator dump /sdcard/disco-fpv-ui.xml');
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

  /** Taps the element whose text equals `text`, else the first one containing it. */
  async tapText(text: string): Promise<void> {
    const nodes = await this.dumpUi();
    const node =
      nodes.find((n) => n.text === text || n.contentDesc === text) ??
      nodes.find((n) => n.text.includes(text) || n.contentDesc.includes(text));
    if (!node) throw new Error(`no element with text "${text}" on screen`);
    const { x1, y1, x2, y2 } = node.bounds;
    await this.tap((x1 + x2) / 2, (y1 + y2) / 2);
  }

  /** Taps the element whose resource id is `id` or ends with ":id/<id>". */
  async tapId(id: string): Promise<void> {
    const nodes = await this.dumpUi();
    const node = nodes.find((n) => n.resourceId === id || n.resourceId.endsWith(`:id/${id}`));
    if (!node) throw new Error(`no element with id "${id}" on screen`);
    const { x1, y1, x2, y2 } = node.bounds;
    await this.tap((x1 + x2) / 2, (y1 + y2) / 2);
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
