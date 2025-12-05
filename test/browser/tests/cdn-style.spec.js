// @ts-check
const { test, expect } = require('@playwright/test');

// Tests CDN-style loading where module is in a subdirectory relative to HTML page
test.describe('CDN-Style Loading Tests', () => {
  test('can initialize PROJ when module is in subdirectory', async ({ page }) => {
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

    // Try to initialize PROJ - this is where the bug manifests
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

        // Verify we can actually use PROJ
        const context = proj.context_create();
        if (!context) {
          return { success: false, error: 'Failed to create context after init' };
        }

        return { success: true };
      } catch (error) {
        return {
          success: false,
          error: error.message,
          stack: error.stack
        };
      }
    });

    // Check for 404 errors in console (the specific bug we're testing for)
    const has404Errors = consoleLogs.some(log =>
      log.text.includes('404') ||
      log.text.includes('Failed to fetch resources')
    );

    // The test passes if initialization succeeds without 404 errors
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

        // Create context and transformer
        const context = proj.context_create();
        const transformer = proj.proj_create_crs_to_crs({
          source_crs: "EPSG:4326",
          target_crs: "EPSG:2249",
          context: context
        });

        if (!transformer) {
          return { success: false, error: 'Failed to create transformer' };
        }

        // Transform Boston City Hall coordinates
        const coordArray = proj.coord_array(1);
        proj.set_coords_BANG_(coordArray, [[42.3603222, -71.0579667, 0, 0]]);

        const malloc = coordArray.get ? coordArray.get('malloc') : coordArray.malloc;
        const PJ_FWD = proj.PJ_FWD || 1;

        proj.proj_trans_array({
          p: transformer,
          direction: PJ_FWD,
          n: 1,
          coord: malloc
        });

        const array = coordArray.get ? coordArray.get('array') : coordArray.array;
        const x = array[0];
        const y = array[1];

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

    expect(result.success).toBe(true);
    expect(result.xInRange).toBe(true);
    expect(result.yInRange).toBe(true);
  });
});
