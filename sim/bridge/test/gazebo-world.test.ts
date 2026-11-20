/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { CLEAR_RADIUS, KINDS, fields, ground, groundModel, kindModel, layerObj, layout, rect, rng, slug, world } from '../src/gazebo-world.ts';

test('the layout is the same on every run', () => {
  assert.deepEqual(layout(), layout());
  const r = rng(1);
  const a = [r(), r()];
  const s = rng(1);
  assert.deepEqual(a, [s(), s()]);
});

test('the take-off area is clear and names are unique', () => {
  const objects = layout();
  assert.ok(objects.length > 150);
  for (const o of objects) assert.ok(Math.hypot(o.x, o.y) > CLEAR_RADIUS, o.name);
  assert.equal(new Set(objects.map((o) => o.name)).size, objects.length);
});

test('the world has the Disco, the camera system and Fuel models', () => {
  const sdf = world(21.2665, -157.8205, 3);
  assert.match(sdf, /model:\/\/disco_zephyr/);
  assert.match(sdf, /gz-sim-sensors-system/);
  assert.match(sdf, /<latitude_deg>21.2665<\/latitude_deg>/);
  assert.match(sdf, /model:\/\/fuel_oak_tree/);
});

test('every placed model has a wrapper with Fuel files and PBR textures', () => {
  const kinds = new Set(KINDS.map((k) => k.model));
  for (const o of layout()) assert.ok(kinds.has(o.model), o.model);
  const oak = kindModel(KINDS.find((k) => k.model === 'Oak tree')!);
  assert.match(oak, /openrobotics\/models\/oak%20tree\/7\/files\/meshes\/oak_tree.dae/);
  assert.match(oak, /<albedo_map>[^<]*branch_diffuse.png<\/albedo_map>/);
  assert.match(oak, /<submesh><name>Bark<\/name><\/submesh>/);
  assert.equal(slug('Gas Station'), 'fuel_gas_station');
});

test('the ground is textured layers of flat meshes with repeating UVs', () => {
  const layers = ground();
  const meadow = layers.find((l) => l.name === 'meadow')!;
  const obj = layerObj(meadow);
  assert.match(obj, /^v -2000.00 -2000.00 0$/m);
  assert.match(obj, /^vt -200.0000 -200.0000$/m);
  assert.match(obj, /^f 1\/1\/1 2\/2\/1 3\/3\/1$/m);
  const sdf = groundModel();
  for (const l of layers) assert.match(sdf, new RegExp(`meshes/${l.name}.obj`));
  assert.match(sdf, /<albedo_map>\/opt\/disco-textures\/Grass004.jpg<\/albedo_map>/);
  assert.ok(fields().length > 40);
  assert.ok(new Set(fields().map((f) => f.surface)).size >= 4);
  assert.match(world(0, 0, 0), /model:\/\/disco_ground/);
});

test('polygons are drawn facing up whatever their winding', () => {
  const cw = layerObj({ name: 't', surface: 'dirt', tile: 1, z: 0, polys: [rect(0, 0, 2, 2).reverse()] });
  // the first triangle turns counter-clockwise seen from above
  const v = [...cw.matchAll(/^v (\S+) (\S+)/gm)].map((m) => [Number(m[1]), Number(m[2])]);
  const [a, b, c] = [v[0]!, v[1]!, v[2]!];
  assert.ok((b[0]! - a[0]!) * (c[1]! - a[1]!) - (b[1]! - a[1]!) * (c[0]! - a[0]!) > 0);
});
