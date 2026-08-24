#!/usr/bin/env node
// Copyright (c) 2024, 2025, 2026 Will Cohen
//
// Part of clj-proj, under the MIT License.
// See LICENSE for license information.
// SPDX-License-Identifier: MIT

/**
 * Multi-worker benchmark for proj-wasm coordinate transformations.
 *
 * Measures parallelism: multiple contexts (round-robined to workers), each
 * with its own transformer and coord array, and all transforms fired
 * concurrently through Promise.all.
 *
 * Run with: node --test test/js/benchmark.test.mjs
 */

import { test, describe } from 'node:test';
import assert from 'node:assert';

const COORDS_PER_CONTEXT = 100000;
const WORKER_COUNTS = [1, 2, 4];

function generateRandomCoords(n) {
  const coords = [];
  for (let i = 0; i < n; i++) {
    const lat = 25 + Math.random() * 24;   // 25-49 N
    const lon = -125 + Math.random() * 57;  // -125 to -68 W
    coords.push([lat, lon, 0, 0]);
  }
  return coords;
}

/**
 * Creates one task: a context, a transformer, and a coord array, all bound
 * to the same worker through round-robin context assignment.
 */
async function createTask(proj, coords) {
  const context = await proj.context_create({ network: false });
  const transformer = await proj.proj_create_crs_to_crs({
    source_crs: "EPSG:4326",
    target_crs: "EPSG:3857",
    context: context
  });
  assert(transformer, "transformer should be truthy");

  // The third coord_array argument routes the allocation to the
  // transformer's worker.
  const coordArray = await proj.coord_array(COORDS_PER_CONTEXT, 4, transformer);
  await proj.set_coords_BANG_(coordArray, coords);

  return { context, transformer, coordArray };
}

async function benchConcurrentTransforms(proj, numTasks, coordSets) {
  const tasks = [];
  for (let i = 0; i < numTasks; i++) {
    tasks.push(await createTask(proj, coordSets[i]));
  }

  const PJ_FWD = proj.PJ_FWD || 1;

  const start = performance.now();
  await Promise.all(tasks.map(t =>
    proj.proj_trans_array({
      p: t.transformer,
      direction: PJ_FWD,
      n: COORDS_PER_CONTEXT,
      coord: t.coordArray
    })
  ));
  const elapsed = performance.now() - start;

  const spotChecks = [];
  for (const t of tasks) {
    const c = await proj.get_coord_array(t.coordArray, 0);
    spotChecks.push([c[0], c[1]]);
  }

  return { elapsed, spotChecks, totalCoords: numTasks * COORDS_PER_CONTEXT };
}

async function benchConcurrentCRSCreation(proj, numOps) {
  const contexts = [];
  for (let i = 0; i < numOps; i++) {
    contexts.push(await proj.context_create({ network: false }));
  }

  const start = performance.now();
  const transformers = await Promise.all(contexts.map(ctx =>
    proj.proj_create_crs_to_crs({
      source_crs: "EPSG:4326",
      target_crs: "EPSG:3857",
      context: ctx
    })
  ));
  const elapsed = performance.now() - start;

  for (const t of transformers) {
    assert(t, "CRS creation should return truthy transformer");
  }

  return { elapsed, count: numOps };
}

describe('Multi-worker benchmark', () => {
  const transformTimings = {};
  const crsTimings = {};

  for (const workerCount of WORKER_COUNTS) {
    test(`concurrent transforms with ${workerCount} worker(s)`, async () => {
      const proj = await import('../../src/cljc/net/willcohen/proj/dist/proj.mjs');

      if (proj.shutdown) await proj.shutdown();
      await proj.init(null, { workers: workerCount });

      const numTasks = 16; // same total work for each worker count

      const coordSets = [];
      for (let i = 0; i < numTasks; i++) {
        coordSets.push(generateRandomCoords(COORDS_PER_CONTEXT));
      }

      const result = await benchConcurrentTransforms(proj, numTasks, coordSets);
      transformTimings[workerCount] = { ...result, numTasks };

      console.log(`  ${workerCount} worker(s): ${numTasks} tasks x ${COORDS_PER_CONTEXT} coords = ${result.totalCoords} total in ${result.elapsed.toFixed(1)}ms`);

      for (const [x, y] of result.spotChecks) {
        assert(Math.abs(x) > 1000, `X should be in Mercator range: ${x}`);
        assert(Math.abs(y) > 1000, `Y should be in Mercator range: ${y}`);
      }

      if (proj.shutdown) await proj.shutdown();
    });
  }

  for (const workerCount of WORKER_COUNTS) {
    test(`concurrent CRS creation with ${workerCount} worker(s)`, async () => {
      const proj = await import('../../src/cljc/net/willcohen/proj/dist/proj.mjs');

      if (proj.shutdown) await proj.shutdown();
      await proj.init(null, { workers: workerCount });

      const numOps = 20;

      const result = await benchConcurrentCRSCreation(proj, numOps);
      crsTimings[workerCount] = { ...result };

      console.log(`  ${workerCount} worker(s): ${numOps} CRS creations in ${result.elapsed.toFixed(1)}ms`);

      if (proj.shutdown) await proj.shutdown();
    });
  }

  test('report summary', () => {
    const transformCounts = Object.keys(transformTimings).map(Number).sort((a, b) => a - b);
    const crsCounts = Object.keys(crsTimings).map(Number).sort((a, b) => a - b);

    if (transformCounts.length > 0) {
      console.log('\n  === Concurrent Transform Benchmark ===');
      console.log(`  ${COORDS_PER_CONTEXT} coords per task`);
      console.log('  --------------------------');
      const tBase = transformTimings[transformCounts[0]].elapsed;
      for (const w of transformCounts) {
        const t = transformTimings[w];
        const speedup = tBase / t.elapsed;
        const perCoord = (t.elapsed / t.totalCoords * 1000).toFixed(1);
        console.log(`  ${w} worker(s): ${t.numTasks} tasks, ${t.elapsed.toFixed(1)}ms (${speedup.toFixed(2)}x, ${perCoord}us/coord)`);
      }
    }

    if (crsCounts.length > 0) {
      console.log('\n  === Concurrent CRS Creation Benchmark ===');
      console.log('  --------------------------');
      const cBase = crsTimings[crsCounts[0]].elapsed;
      for (const w of crsCounts) {
        const c = crsTimings[w];
        const speedup = cBase / c.elapsed;
        const perOp = (c.elapsed / c.count).toFixed(1);
        console.log(`  ${w} worker(s): ${c.count} ops, ${c.elapsed.toFixed(1)}ms (${speedup.toFixed(2)}x, ${perOp}ms/op)`);
      }
    }

    console.log('  --------------------------');
    assert(true);
  });
});
