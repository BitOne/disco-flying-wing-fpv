/** A visible UI element from a UIAutomator dump. */
export interface UiNode {
  text: string;
  contentDesc: string;
  resourceId: string;
  bounds: { x1: number; y1: number; x2: number; y2: number };
}

const ENTITIES: Record<string, string> = {
  '&amp;': '&',
  '&lt;': '<',
  '&gt;': '>',
  '&quot;': '"',
  '&apos;': "'",
};

function decode(value: string): string {
  return value
    .replace(/&#(\d+);/g, (_, code: string) => String.fromCodePoint(Number(code)))
    .replace(/&(amp|lt|gt|quot|apos);/g, (entity) => ENTITIES[entity] ?? entity);
}

function attr(node: string, name: string): string {
  const match = new RegExp(`\\s${name}="([^"]*)"`).exec(node);
  return match?.[1] !== undefined ? decode(match[1]) : '';
}

/** Parses `uiautomator dump` XML into nodes (only the attributes we use). */
export function parseUiDump(xml: string): UiNode[] {
  const nodes: UiNode[] = [];
  for (const match of xml.matchAll(/<node\s[^>]*>/g)) {
    const node = match[0];
    const b = /bounds="\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]"/.exec(node);
    if (!b) continue;
    nodes.push({
      text: attr(node, 'text'),
      contentDesc: attr(node, 'content-desc'),
      resourceId: attr(node, 'resource-id'),
      bounds: { x1: Number(b[1]), y1: Number(b[2]), x2: Number(b[3]), y2: Number(b[4]) },
    });
  }
  return nodes;
}

/** Non-empty texts and content descriptions, in screen order. */
export function textsOf(nodes: readonly UiNode[]): string[] {
  const out: string[] = [];
  for (const n of nodes) {
    if (n.text) out.push(n.text);
    if (n.contentDesc) out.push(n.contentDesc);
  }
  return out;
}

/** Returns the expected strings not found as a substring of any text. */
export function missingTexts(texts: readonly string[], expected: readonly string[]): string[] {
  return expected.filter((e) => !texts.some((t) => t.includes(e)));
}
