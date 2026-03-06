// @ts-check
const { test, expect } = require('@playwright/test');

// Tests CDN-style loading where module is in a subdirectory relative to HTML page
test.describe('CDN-Style Loading Tests', () => {
  test('can initialize PROJ and verify worker mode', async ({ page, baseURL }) => {
    // Determine expected mode based on server port
    const isSharedMode = (baseURL || '').includes('8081');
    const expectedMode = isSharedMode ? 'pthreads' : 'single-threaded';

    // Capture console logs and errors
    const consoleLogs = [];
    const consoleErrors = [];

    page.on('console', msg => {
      const text = msg.text();
      consoleLogs.push({ type: msg.type(), text });
      console.log('Browser console:', msg.type(), text);
    });

    page.on('pageerror', error => {
      consoleErrors.push(error.message);
      console.log('Browser error:', error.message);
    });

    // Navigate to the cdn-style test page
    await page.goto('/test/browser/cdn-style/index.html');

    // Wait for PROJ module to be available
    await page.waitForFunction(() => window.proj !== undefined, { timeout: 30000 });

    // Initialize and verify mode
    const result = await page.evaluate(async () => {
      const proj = window.proj;

      try {
        console.log('Starting PROJ initialization (CDN-style test)...');
        const initFunction = proj.init_BANG_ || proj['init!'] || proj.init;

        if (!initFunction || typeof initFunction !== 'function') {
          return { success: false, error: 'Init function not found' };
        }

        await initFunction();
        console.log('PROJ initialized successfully');

        // Get worker mode
        const mode = proj.get_worker_mode ? proj.get_worker_mode() : 'unknown';
        console.log('Worker mode:', mode);

        // Check crossOriginIsolated status
        const crossOriginIsolated = self.crossOriginIsolated || false;
        console.log('crossOriginIsolated:', crossOriginIsolated);

        // Verify we can create a context (async in worker mode)
        const context = await proj.context_create();
        if (!context) {
          return { success: false, error: 'Failed to create context after init' };
        }

        return {
          success: true,
          mode,
          crossOriginIsolated
        };
      } catch (error) {
        return {
          success: false,
          error: error.message,
          stack: error.stack
        };
      }
    });

    // Check for 404 errors in console
    const has404Errors = consoleLogs.some(log =>
      log.text.includes('404') ||
      log.text.includes('Failed to fetch resources')
    );

    if (!result.success) {
      console.log('Initialization failed:', result.error);
      if (result.stack) {
        console.log('Stack:', result.stack);
      }
    }

    expect(has404Errors).toBe(false);
    expect(result.success).toBe(true);

    // Verify the correct mode based on server configuration
    console.log(`Expected mode: ${expectedMode}, Actual mode: ${result.mode}`);
    expect(result.mode).toBe(expectedMode);
  });

  test('can perform coordinate transformation with CDN-style loading', async ({ page }) => {
    // Capture errors
    page.on('console', msg => console.log('Browser console:', msg.type(), msg.text()));
    page.on('pageerror', error => console.log('Browser error:', error.message));

    await page.goto('/test/browser/cdn-style/index.html');
    await page.waitForFunction(() => window.proj !== undefined, { timeout: 30000 });

    const result = await page.evaluate(async () => {
      const proj = window.proj;

      try {
        // Initialize
        const initFunction = proj.init_BANG_ || proj['init!'] || proj.init;
        await initFunction();

        // Create context (async)
        const context = await proj.context_create();

        // Create transformer (async)
        const transformer = await proj.proj_create_crs_to_crs({
          source_crs: "EPSG:4326",
          target_crs: "EPSG:2249",
          context: context
        });

        if (!transformer) {
          return { success: false, error: 'Failed to create transformer' };
        }

        // Create coordinate array (async)
        const coordArray = await proj.coord_array(1);

        // Set coordinates (async)
        await proj.set_coords_BANG_(coordArray, [[42.3603222, -71.0579667, 0, 0]]);

        const PJ_FWD = proj.PJ_FWD || 1;

        // Transform (async)
        await proj.proj_trans_array({
          p: transformer,
          direction: PJ_FWD,
          n: 1,
          coord: coordArray
        });

        // Get transformed coordinates (async)
        const coords = await proj.get_coord_array(coordArray, 0);
        const x = coords[0];
        const y = coords[1];

        // Boston City Hall should be approximately X: 775,200 ft, Y: 2,956,400 ft
        const xInRange = x > 775000 && x < 776000;
        const yInRange = y > 2956000 && y < 2957000;

        return {
          success: true,
          x,
          y,
          xInRange,
          yInRange
        };
      } catch (error) {
        return {
          success: false,
          error: error.message,
          stack: error.stack
        };
      }
    });

    if (!result.success) {
      console.log('Transformation failed:', result.error);
      if (result.stack) {
        console.log('Stack:', result.stack);
      }
    }

    expect(result.success).toBe(true);
    expect(result.xInRange).toBe(true);
    expect(result.yInRange).toBe(true);
  });

  test('compare coordinates with network OFF vs ON (grid fetch test)', async ({ page }) => {
    page.on('console', msg => console.log('Browser console:', msg.type(), msg.text()));
    page.on('pageerror', error => console.log('Browser error:', error.message));

    await page.goto('/test/browser/cdn-style/index.html');
    await page.waitForFunction(() => window.proj !== undefined, { timeout: 30000 });

    const result = await page.evaluate(async () => {
      const proj = window.proj;

      try {
        const initFunction = proj.init_BANG_ || proj['init!'] || proj.init;
        await initFunction();

        const originalLat = 42.3603222;
        const originalLon = -71.0579667;
        const PJ_FWD = proj.PJ_FWD || 1;

        // Test 1: Network OFF
        const ctxOff = await proj.context_create({ network: false });
        await proj.proj_context_set_enable_network({ context: ctxOff, enabled: 0 });

        const transformerOff = await proj.proj_create_crs_to_crs({
          source_crs: "EPSG:4267",  // NAD27
          target_crs: "EPSG:26986", // NAD83 MA State Plane (meters)
          context: ctxOff
        });

        if (!transformerOff) {
          return { success: false, error: 'Failed to create transformer (network off)' };
        }

        const coordArrayOff = await proj.coord_array(1);
        await proj.set_coords_BANG_(coordArrayOff, [[originalLat, originalLon, 0, 0]]);

        await proj.proj_trans_array({
          p: transformerOff,
          direction: PJ_FWD,
          n: 1,
          coord: coordArrayOff
        });

        const coordsOff = await proj.get_coord_array(coordArrayOff, 0);
        const xOff = coordsOff[0];
        const yOff = coordsOff[1];

        // Test 2: Network ON (default)
        const ctxOn = await proj.context_create();

        const transformerOn = await proj.proj_create_crs_to_crs({
          source_crs: "EPSG:4267",  // NAD27
          target_crs: "EPSG:26986", // NAD83 MA State Plane (meters)
          context: ctxOn
        });

        if (!transformerOn) {
          return { success: false, error: 'Failed to create transformer (network on)' };
        }

        const coordArrayOn = await proj.coord_array(1);
        await proj.set_coords_BANG_(coordArrayOn, [[originalLat, originalLon, 0, 0]]);

        await proj.proj_trans_array({
          p: transformerOn,
          direction: PJ_FWD,
          n: 1,
          coord: coordArrayOn
        });

        const coordsOn = await proj.get_coord_array(coordArrayOn, 0);
        const xOn = coordsOn[0];
        const yOn = coordsOn[1];

        const diffX = Math.abs(xOn - xOff);
        const diffY = Math.abs(yOn - yOff);

        return {
          success: true,
          xOff, yOff, xOn, yOn, diffX, diffY,
          gridFetchChangedResult: diffX > 0.01 || diffY > 0.01
        };
      } catch (error) {
        return {
          success: false,
          error: error.message,
          stack: error.stack
        };
      }
    });

    console.log('Grid fetch comparison:', result);
    expect(result.success).toBe(true);
    expect(result.gridFetchChangedResult).toBe(true);
  });
});
