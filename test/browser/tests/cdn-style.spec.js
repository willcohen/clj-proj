// @ts-check
const { test, expect } = require('@playwright/test');

// CDN-style: the module loads from a subdirectory relative to the HTML page.
test.describe('CDN-Style Loading Tests', () => {
  test('can initialize PROJ with CDN-style loading', async ({ page }) => {
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

    await page.goto('/test/browser/cdn-style/index.html');
    await page.waitForFunction(() => window.proj !== undefined, { timeout: 30000 });

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

        const crossOriginIsolated = self.crossOriginIsolated || false;
        console.log('crossOriginIsolated:', crossOriginIsolated);

        const context = await proj.context_create();
        if (!context) {
          return { success: false, error: 'Failed to create context after init' };
        }

        return {
          success: true,
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
  });

  test('can perform coordinate transformation with CDN-style loading', async ({ page }) => {
    page.on('console', msg => console.log('Browser console:', msg.type(), msg.text()));
    page.on('pageerror', error => console.log('Browser error:', error.message));

    await page.goto('/test/browser/cdn-style/index.html');
    await page.waitForFunction(() => window.proj !== undefined, { timeout: 30000 });

    const result = await page.evaluate(async () => {
      const proj = window.proj;

      try {
        const initFunction = proj.init_BANG_ || proj['init!'] || proj.init;
        await initFunction();

        const context = await proj.context_create();

        const transformer = await proj.proj_create_crs_to_crs({
          source_crs: "EPSG:4326",
          target_crs: "EPSG:2249",
          context: context
        });

        if (!transformer) {
          return { success: false, error: 'Failed to create transformer' };
        }

        const coordArray = await proj.coord_array(1);

        await proj.set_coords_BANG_(coordArray, [[42.3603222, -71.0579667, 0, 0]]);

        const PJ_FWD = proj.PJ_FWD || 1;

        await proj.proj_trans_array({
          p: transformer,
          direction: PJ_FWD,
          n: 1,
          coord: coordArray
        });

        const coords = await proj.get_coord_array(coordArray, 0);
        const x = coords[0];
        const y = coords[1];

        // Boston City Hall: approximately X 775,200 ft, Y 2,956,400 ft
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
