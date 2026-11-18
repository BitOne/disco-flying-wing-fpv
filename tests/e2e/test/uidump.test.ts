import { test } from 'node:test';
import assert from 'node:assert/strict';
import { missingTexts, parseUiDump, textsOf } from '../src/uidump.ts';

const xml = `<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node index="0" text="" resource-id="" class="android.widget.FrameLayout" bounds="[0,0][1080,2400]">
    <node index="0" text="DRONES" resource-id="" content-desc="" bounds="[405,323][610,420]" />
    <node index="1" text="Take off altitude" resource-id="com.x:id/label" bounds="[54,1500][600,1550]" />
    <node index="2" text="42.50 m" resource-id="com.x:id/value" bounds="[800,1500][1026,1550]" />
    <node index="3" text="A &amp; B &quot;q&quot;" content-desc="desc" bounds="[0,0][10,10]" />
  </node>
</hierarchy>`;

test('parseUiDump extracts text, ids and bounds', () => {
  const nodes = parseUiDump(xml);
  assert.equal(nodes.length, 5);
  const drones = nodes.find((n) => n.text === 'DRONES');
  assert.deepEqual(drones?.bounds, { x1: 405, y1: 323, x2: 610, y2: 420 });
  assert.equal(nodes.find((n) => n.text === '42.50 m')?.resourceId, 'com.x:id/value');
});

test('parseUiDump decodes XML entities', () => {
  assert.ok(textsOf(parseUiDump(xml)).includes('A & B "q"'));
});

test('textsOf keeps non-empty texts and content descriptions in order', () => {
  assert.deepEqual(textsOf(parseUiDump(xml)), ['DRONES', 'Take off altitude', '42.50 m', 'A & B "q"', 'desc']);
});

test('missingTexts matches substrings', () => {
  const texts = ['Take off altitude', '42.50 m', 'Location[null 45.188500,5.724500 alt=250.0]'];
  assert.deepEqual(missingTexts(texts, ['42.50', '45.188500,5.724500']), []);
  assert.deepEqual(missingTexts(texts, ['73 %', '42.50 m']), ['73 %']);
});
