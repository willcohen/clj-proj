// @ts-check
const { test, expect } = require('@playwright/test');

const COORDS_PER_CONTEXT = 100000;

/**
 * Multi-worker browser benchmark for proj-wasm coordinate transformations.
 *
 * Tests actual parallelism by creating multiple contexts (round-robined to
 * workers), each with its own transformer + coord array, then firing all
 * transforms concurrently via Promise.all.
 *
 * Playwright's project matrix runs this against both servers automatically:
 *   - port 8080: single-threaded mode (no COOP/COEP)
 *   - port 8081: pthreads mode (COOP/COEP enabled)
 */
test.describe('Multi-worker browser benchmark', () => {
  test.setTimeout(180_000);

  test('concurrent transforms across worker counts', async ({ page, baseURL }) => {
    const isSharedMode = (baseURL || '').includes('8081');
    const modeName = isSharedMode ? 'shared' : 'isolated';

    page.on('console', msg => console.log(`[${modeName}]`, msg.type(), msg.text()));
    page.on('pageerror', error => console.log(`[${modeName}] error:`, error.message));

    await page.goto('/test/browser/test.html');
    await page.waitForFunction(() => window.proj !== undefined, { timeout: 30000 });

    const result = await page.evaluate(async ({ coordsPerContext }) => {
      const proj = window.proj;
      const hwConcurrency = navigator.hardwareConcurrency || 4;
      const workerCounts = [1, 2, 4].filter(n => n <= hwConcurrency);
      if (hwConcurrency > 4) workerCounts.push(hwConcurrency);

      function generateRandomCoords(n) {
        const coords = [];
        for (let i = 0; i < n; i++) {
          const lat = 25 + Math.random() * 24;
          const lon = -125 + Math.random() * 57;
          coords.push([lat, lon, 0, 0]);
        }
        return coords;
      }

      async function createTask(coords) {
        const context = await proj.context_create({ network: false });
        const transformer = await proj.proj_create_crs_to_crs({
          source_crs: "EPSG:4326",
          target_crs: "EPSG:3857",
          context: context
        });
        if (!transformer) throw new Error("Failed to create transformer");

        const coordArray = await proj.coord_array(coordsPerContext, 4, transformer);
        await proj.set_coords_BANG_(coordArray, coords);
        return { context, transformer, coordArray };
      }

      const transformResults = {};
      const crsResults = {};

      for (const workerCount of workerCounts) {
        try {
          const shutdownFn = proj.shutdown || proj.shutdown_BANG_;
          if (shutdownFn) await shutdownFn();

          const initFn = proj.init || proj.init_BANG_;
          await initFn(null, { workers: workerCount });

          const mode = proj.get_worker_mode ? proj.get_worker_mode() : 'unknown';
          const numTasks = 16; // fixed task count

          // --- Test A: Concurrent transforms ---
          const coordSets = [];
          for (let i = 0; i < numTasks; i++) {
            coordSets.push(generateRandomCoords(coordsPerContext));
          }

          const tasks = [];
          for (let i = 0; i < numTasks; i++) {
            tasks.push(await createTask(coordSets[i]));
          }

          const PJ_FWD = proj.PJ_FWD || 1;
          const tStart = performance.now();
          await Promise.all(tasks.map(t =>
            proj.proj_trans_array({
              p: t.transformer,
              direction: PJ_FWD,
              n: coordsPerContext,
              coord: t.coordArray
            })
          ));
          const tElapsed = performance.now() - tStart;

          // Spot check first coord of each task
          const spotChecks = [];
          for (const t of tasks) {
            const c = await proj.get_coord_array(t.coordArray, 0);
            spotChecks.push([c[0], c[1]]);
          }

          transformResults[workerCount] = {
            elapsed: tElapsed,
            numTasks,
            totalCoords: numTasks * coordsPerContext,
            spotChecks,
            mode
          };

          // --- Test B: Concurrent CRS creation ---
          const numOps = 20;
          const contexts = [];
          for (let i = 0; i < numOps; i++) {
            contexts.push(await proj.context_create({ network: false }));
          }

          const cStart = performance.now();
          const transformers = await Promise.all(contexts.map(ctx =>
            proj.proj_create_crs_to_crs({
              source_crs: "EPSG:4326",
              target_crs: "EPSG:3857",
              context: ctx
            })
          ));
          const cElapsed = performance.now() - cStart;

          const allValid = transformers.every(t => !!t);
          crsResults[workerCount] = {
            elapsed: cElapsed,
            count: numOps,
            allValid,
            mode
          };

          console.log(`${workerCount} worker(s) [${mode}]: transforms ${tElapsed.toFixed(1)}ms, CRS ${cElapsed.toFixed(1)}ms`);
        } catch (error) {
          return {
            success: false,
            error: `Worker count ${workerCount}: ${error.message}`,
            stack: error.stack
          };
        }
      }

      // Build report
      const tCounts = Object.keys(transformResults).map(Number).sort((a, b) => a - b);
      const cCounts = Object.keys(crsResults).map(Number).sort((a, b) => a - b);

      const tBase = tCounts.length > 0 ? transformResults[tCounts[0]].elapsed : 1;
      const cBase = cCounts.length > 0 ? crsResults[cCounts[0]].elapsed : 1;

      return {
        success: true,
        crossOriginIsolated: self.crossOriginIsolated || false,
        coordsPerContext,
        transforms: tCounts.map(w => {
          const r = transformResults[w];
          return {
            workers: w, numTasks: r.numTasks, totalCoords: r.totalCoords,
            ms: r.elapsed, speedup: tBase / r.elapsed,
            usPerCoord: r.elapsed / r.totalCoords * 1000,
            spotChecks: r.spotChecks, mode: r.mode
          };
        }),
        crs: cCounts.map(w => {
          const r = crsResults[w];
          return {
            workers: w, count: r.count,
            ms: r.elapsed, speedup: cBase / r.elapsed,
            msPerOp: r.elapsed / r.count,
            allValid: r.allValid, mode: r.mode
          };
        })
      };
    }, { coordsPerContext: COORDS_PER_CONTEXT });

    if (!result.success) {
      console.log(`[${modeName}] Benchmark failed:`, result.error);
      if (result.stack) console.log('Stack:', result.stack);
    }

    expect(result.success).toBe(true);

    // Log transform results
    console.log(`\n=== Browser Benchmark (${modeName}, crossOriginIsolated=${result.crossOriginIsolated}) ===`);
    console.log(`${result.coordsPerContext} coords per task`);
    console.log('--- Concurrent Transforms ---');
    for (const r of result.transforms) {
      console.log(`${r.workers} worker(s): ${r.numTasks} tasks, ${r.ms.toFixed(1)}ms (${r.speedup.toFixed(2)}x, ${r.usPerCoord.toFixed(1)}us/coord)`);
      // Spot check
      for (const [x, y] of r.spotChecks) {
        expect(Math.abs(x)).toBeGreaterThan(1000);
        expect(Math.abs(y)).toBeGreaterThan(1000);
      }
    }

    // Log CRS results
    console.log('--- Concurrent CRS Creation ---');
    for (const r of result.crs) {
      console.log(`${r.workers} worker(s): ${r.count} ops, ${r.ms.toFixed(1)}ms (${r.speedup.toFixed(2)}x, ${r.msPerOp.toFixed(1)}ms/op)`);
      expect(r.allValid).toBe(true);
    }
    console.log('--------------------------');
  });
});
