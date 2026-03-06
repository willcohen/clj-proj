#!/usr/bin/env node

/**
 * Multi-worker benchmark for proj-wasm coordinate transformations.
 *
 * Tests actual parallelism by creating multiple contexts (round-robined to
 * workers), each with its own transformer + coord array, then firing all
 * transforms concurrently via Promise.all.
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
 * Create a "task" on a specific context: context + transformer + coord array,
 * all bound to the same worker via round-robin context assignment.
 */
async function createTask(proj, coords) {
  const context = await proj.context_create({ network: false });
  const transformer = await proj.proj_create_crs_to_crs({
    source_crs: "EPSG:4326",
    target_crs: "EPSG:3857",
    context: context
  });
  assert(transformer, "transformer should be truthy");

  // coord_array with 3 args: (n, dims, opts-or-object-with-worker-idx)
  // Passing transformer routes the allocation to the same worker
  const coordArray = await proj.coord_array(COORDS_PER_CONTEXT, 4, transformer);
  await proj.set_coords_BANG_(coordArray, coords);

  return { context, transformer, coordArray };
}

/**
 * Test A: Concurrent batch transforms.
 * Creates numTasks independent transform pipelines, fires them all at once.
 */
async function benchConcurrentTransforms(proj, numTasks, coordSets) {
  // Setup: create all tasks (contexts round-robin to workers)
  const tasks = [];
  for (let i = 0; i < numTasks; i++) {
    tasks.push(await createTask(proj, coordSets[i]));
  }

  const PJ_FWD = proj.PJ_FWD || 1;

  // Timed section: fire all transforms concurrently
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

  // Read back first coord from each task for verification
  const spotChecks = [];
  for (const t of tasks) {
    const c = await proj.get_coord_array(t.coordArray, 0);
    spotChecks.push([c[0], c[1]]);
  }

  return { elapsed, spotChecks, totalCoords: numTasks * COORDS_PER_CONTEXT };
}

/**
 * Test B: Concurrent CRS creation.
 * Fires many proj_create_crs_to_crs calls at once across workers.
 */
async function benchConcurrentCRSCreation(proj, numOps) {
  // Create contexts first (round-robined)
  const contexts = [];
  for (let i = 0; i < numOps; i++) {
    contexts.push(await proj.context_create({ network: false }));
  }

  // Timed section: concurrent CRS creation
  const start = performance.now();
  const transformers = await Promise.all(contexts.map(ctx =>
    proj.proj_create_crs_to_crs({
      source_crs: "EPSG:4326",
      target_crs: "EPSG:3857",
      context: ctx
    })
  ));
  const elapsed = performance.now() - start;

  // Verify all succeeded
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

      const mode = proj.get_worker_mode ? proj.get_worker_mode() : 'unknown';
      const numTasks = 16; // fixed task count -- same work across all worker counts

      // Generate coords per task
      const coordSets = [];
      for (let i = 0; i < numTasks; i++) {
        coordSets.push(generateRandomCoords(COORDS_PER_CONTEXT));
      }

      const result = await benchConcurrentTransforms(proj, numTasks, coordSets);
      transformTimings[workerCount] = { ...result, numTasks, mode };

      console.log(`  ${workerCount} worker(s) [${mode}]: ${numTasks} tasks x ${COORDS_PER_CONTEXT} coords = ${result.totalCoords} total in ${result.elapsed.toFixed(1)}ms`);

      // Spot check: transformed coords should be in Web Mercator range
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

      const mode = proj.get_worker_mode ? proj.get_worker_mode() : 'unknown';
      const numOps = 20; // fixed op count

      const result = await benchConcurrentCRSCreation(proj, numOps);
      crsTimings[workerCount] = { ...result, mode };

      console.log(`  ${workerCount} worker(s) [${mode}]: ${numOps} CRS creations in ${result.elapsed.toFixed(1)}ms`);

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
