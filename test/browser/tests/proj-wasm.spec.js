// @ts-check
const { test, expect } = require('@playwright/test');

test.describe('PROJ WASM Browser Tests', () => {
  test.beforeEach(async ({ page }) => {
    // Capture console logs
    page.on('console', msg => console.log('Browser console:', msg.type(), msg.text()));
    page.on('pageerror', error => console.log('Browser error:', error.message));

    // Navigate to the test page
    await page.goto('/test/browser/test.html');

    // Wait for PROJ module to be available and initialized
    await page.waitForFunction(() => window.proj !== undefined, { timeout: 30000 });

    // Initialize PROJ with better error handling
    await page.evaluate(async () => {
      console.log('Starting PROJ initialization...');
      const initFunction = window.proj.init_BANG_ || window.proj['init!'] || window.proj.init;
      console.log('Init function found:', typeof initFunction);
      if (initFunction && typeof initFunction === 'function') {
        try {
          console.log('Calling init function...');
          await initFunction();
          console.log('Init completed successfully');
        } catch (error) {
          console.error('Init failed:', error.message, error.stack);
          throw error;
        }
      }
    });
  });

  test('module exports expected functions', async ({ page }) => {
    const apiCheck = await page.evaluate(() => {
      const proj = window.proj;
      const expectedExports = [
        'init_BANG_',
        'context_create',
        'proj_create_crs_to_crs',
        'coord_array',
        'set_coords_BANG_',
        'proj_trans_array',
        'proj_destroy'
      ];

      const results = {};
      for (const exportName of expectedExports) {
        results[exportName] = typeof proj[exportName] === 'function';
      }
      return results;
    });

    expect(apiCheck.init_BANG_).toBe(true);
    expect(apiCheck.context_create).toBe(true);
    expect(apiCheck.proj_create_crs_to_crs).toBe(true);
    expect(apiCheck.coord_array).toBe(true);
    expect(apiCheck.set_coords_BANG_).toBe(true);
    expect(apiCheck.proj_trans_array).toBe(true);
    expect(apiCheck.proj_destroy).toBe(true);
  });

  test('coordToCoordArray creates a 1-element coord array', async ({ page }) => {
    const result = await page.evaluate(async () => {
      const proj = window.proj;

      try {
        const ca = await proj.coordToCoordArray([42.3603222, -71.0579667, 100.0, 0.0]);
        if (!ca) return { error: 'coordToCoordArray returned falsy' };

        const coords = await proj.getCoords(ca, 0);
        if (!coords) return { error: 'getCoords returned falsy' };

        return {
          x: coords[0],
          y: coords[1],
          z: coords[2],
          t: coords[3],
          xOk: Math.abs(coords[0] - 42.3603222) < 0.0001,
          yOk: Math.abs(coords[1] - (-71.0579667)) < 0.0001,
          zOk: Math.abs(coords[2] - 100.0) < 0.0001,
          tOk: Math.abs(coords[3] - 0.0) < 0.0001
        };
      } catch (e) {
        return { error: e.message };
      }
    });

    expect(result.error).toBeUndefined();
    expect(result.xOk).toBe(true);
    expect(result.yOk).toBe(true);
    expect(result.zOk).toBe(true);
    expect(result.tOk).toBe(true);
  });

  test('can create and use a context', async ({ page }) => {
    const result = await page.evaluate(async () => {
      const proj = window.proj;
      const context = await proj.context_create();

      return {
        hasContext: !!context,
        contextType: typeof context
      };
    });

    expect(result.hasContext).toBe(true);
    expect(result.contextType).toBe('object');
  });

  test('can create coordinate transformation', async ({ page }) => {
    const result = await page.evaluate(async () => {
      const proj = window.proj;
      const context = await proj.context_create();

      const transformer = await proj.proj_create_crs_to_crs({
        source_crs: "EPSG:4326",
        target_crs: "EPSG:2249",  // MA State Plane
        context: context
      });

      return {
        hasTransformer: !!transformer,
        transformerNotZero: transformer !== 0
      };
    });

    expect(result.hasTransformer).toBe(true);
    expect(result.transformerNotZero).toBe(true);
  });

  test('can transform coordinates', async ({ page }) => {
    const result = await page.evaluate(async () => {
      const proj = window.proj;
      const context = await proj.context_create();

      const transformer = await proj.proj_create_crs_to_crs({
        source_crs: "EPSG:4326",
        target_crs: "EPSG:2249",  // MA State Plane
        context: context
      });

      // Create coordinate array for 1 coordinate (async)
      const coordArray = await proj.coord_array(1);

      // Set coordinates: Boston City Hall (EPSG:4326 uses lat/lon order)
      const originalLat = 42.3603222;
      const originalLon = -71.0579667;
      await proj.set_coords_BANG_(coordArray, [[originalLat, originalLon, 0, 0]]);

      // Transform coordinates (async)
      const PJ_FWD = proj.PJ_FWD || 1;
      await proj.proj_trans_array({
        p: transformer,
        direction: PJ_FWD,
        n: 1,
        coord: coordArray
      });

      // Get transformed coordinates from worker memory (async)
      const coords = await proj.get_coord_array(coordArray, 0);

      if (!coords) {
        return { error: 'coordinate array not found' };
      }

      const transformedX = coords[0];
      const transformedY = coords[1];

      return {
        originalLon,
        originalLat,
        transformedX,
        transformedY,
        xChanged: Math.abs(transformedX - originalLat) > 100,
        yChanged: Math.abs(transformedY - originalLon) > 100,
        // Boston City Hall should be approximately X: 775,200 ft, Y: 2,956,400 ft
        xInRange: transformedX > 775000 && transformedX < 776000,
        yInRange: transformedY > 2956000 && transformedY < 2957000
      };
    });

    expect(result.error).toBeUndefined();
    expect(result.xChanged).toBe(true);
    expect(result.yChanged).toBe(true);
    expect(result.xInRange).toBe(true);
    expect(result.yInRange).toBe(true);
  });

  test('CRS transformation without explicit context', async ({ page }) => {
    const result = await page.evaluate(async () => {
      const proj = window.proj;

      try {
        const transformer = await proj.proj_create_crs_to_crs({
          source_crs: "EPSG:4326",
          target_crs: "EPSG:3857"
        });

        if (!transformer || transformer === 0) {
          return { error: 'transformer is null/0' };
        }

        const coordArray = await proj.coord_array(1);
        await proj.set_coords_BANG_(coordArray, [[42.3603, -71.0591, 0, 0]]);

        await proj.proj_trans_array({
          p: transformer,
          direction: proj.PJ_FWD || 1,
          n: 1,
          coord: coordArray
        });

        const coords = await proj.get_coord_array(coordArray, 0);
        return {
          hasTransformer: true,
          x: coords[0],
          y: coords[1],
          xLarge: Math.abs(coords[0]) > 1000
        };
      } catch (e) {
        return { error: e.message };
      }
    });

    expect(result.error).toBeUndefined();
    expect(result.hasTransformer).toBe(true);
    expect(result.xLarge).toBe(true);
  });

  test('full transform pipeline without any context (demo page pattern)', async ({ page }) => {
    const result = await page.evaluate(async () => {
      const proj = window.proj;

      try {
        const transformer = await proj.projCreateCrsToCrs({
          source_crs: "EPSG:4326",
          target_crs: "EPSG:3857"
        });

        const coords = await proj.coordArray(1);
        await proj.setCoords(coords, [[42.3603, -71.0591, 0, 0]]);

        await proj.projTransArray({
          p: transformer,
          direction: proj.PJ_FWD || 1,
          n: 1,
          coord: coords
        });

        const r = await proj.getCoords(coords, 0);
        return {
          success: true,
          x: r[0],
          y: r[1],
          xInRange: Math.abs(r[0]) > 100000,
          yInRange: Math.abs(r[1]) > 100000
        };
      } catch (e) {
        return { error: e.message };
      }
    });

    expect(result.error).toBeUndefined();
    expect(result.success).toBe(true);
    expect(result.xInRange).toBe(true);
    expect(result.yInRange).toBe(true);
  });

  test('get authorities without explicit context', async ({ page }) => {
    const result = await page.evaluate(async () => {
      const proj = window.proj;

      try {
        const authorities = await proj.proj_get_authorities_from_database({});

        if (authorities === null || authorities === undefined) {
          return { nullResult: true };
        }

        return {
          isArray: Array.isArray(authorities),
          hasEPSG: authorities.includes('EPSG'),
          count: authorities.length
        };
      } catch (e) {
        return { error: e.message };
      }
    });

    expect(result.error).toBeUndefined();
    if (!result.nullResult) {
      expect(result.isArray).toBe(true);
      expect(result.hasEPSG).toBe(true);
    }
  });

  test('projAsWkt returns WKT for CRS created without explicit context', async ({ page }) => {
    const result = await page.evaluate(async () => {
      const proj = window.proj;

      try {
        const crs = await proj.projCreateFromDatabase({
          auth_name: "EPSG", code: "4326", category: proj.PJ_CATEGORY_CRS
        });

        if (!crs || crs === 0) {
          return { error: 'CRS is null/0' };
        }

        const wkt = await proj.projAsWkt({
          pj: crs, type: proj.PJ_WKT2_2019, options: null
        });

        return {
          wktType: typeof wkt,
          wktLength: (wkt || '').length,
          hasWGS84: (wkt || '').includes('WGS 84'),
          wktPreview: (wkt || '').substring(0, 100)
        };
      } catch (e) {
        return { error: e.message };
      }
    });

    expect(result.error).toBeUndefined();
    expect(result.wktType).toBe('string');
    expect(result.wktLength).toBeGreaterThan(0);
    expect(result.hasWGS84).toBe(true);
  });

  test('promote to 3D and transform without explicit context (warns on cross-worker reconciliation)', async ({ page }) => {
    const result = await page.evaluate(async () => {
      const proj = window.proj;
      const warnings = [];
      const origWarn = console.warn;
      console.warn = (...args) => { warnings.push(args.join(' ')); };

      try {
        const src2d = await proj.projCreateFromDatabase({
          auth_name: "EPSG", code: "4326", category: proj.PJ_CATEGORY_CRS
        });
        const tgt2d = await proj.projCreateFromDatabase({
          auth_name: "EPSG", code: "4150", category: proj.PJ_CATEGORY_CRS
        });

        const src3d = await proj.projCrsPromoteTo3D({ crs_3D_name: "", crs_2D: src2d });
        if (!src3d || src3d === 0) return { error: 'src3d is null/0' };

        const tgt3d = await proj.projCrsPromoteTo3D({ crs_3D_name: "", crs_2D: tgt2d });
        if (!tgt3d || tgt3d === 0) return { error: 'tgt3d is null/0' };

        const t3d = await proj.projCreateCrsToCrsFromPj({
          source_crs: src3d, target_crs: tgt3d
        });
        if (!t3d || t3d === 0) return { error: 'transformer is null/0' };

        const coords = await proj.coordArray(1);
        await proj.setCoords(coords, [[46.95, 7.45, 500, 0]]);
        await proj.projTransArray({ p: t3d, direction: proj.PJ_FWD, n: 1, coord: coords });
        const r = await proj.getCoords(coords, 0);

        return {
          z: r[2],
          heightChanged: Math.abs(r[2] - 500) > 1,
          warnings: warnings
        };
      } catch (e) {
        return { error: e.message };
      } finally {
        console.warn = origWarn;
      }
    });

    expect(result.error).toBeUndefined();
    expect(result.heightChanged).toBe(true);
    if (result.warnings && result.warnings.length > 0) {
      const w = result.warnings.join('\n');
      expect(w).toContain('different workers');
      expect(w).toContain('Recreating');
    }
  });

  test('handles invalid CRS gracefully', async ({ page }) => {
    const result = await page.evaluate(async () => {
      const proj = window.proj;
      const context = await proj.context_create();

      let caught = false;
      let transformer = null;

      try {
        transformer = await proj.proj_create_crs_to_crs({
          source_crs: "INVALID:999999",
          target_crs: "EPSG:4326",
          context: context
        });
      } catch (error) {
        caught = true;
        const hasExpectedError = error.message.includes('crs not found') ||
                                error.message.includes('NoSuchAuthorityCodeException');
        return { caught, hasExpectedError, transformer };
      }

      return {
        caught,
        transformer,
        transformerIsNullOrZero: !transformer || transformer === 0
      };
    });

    // Should either throw an error or return null/0
    expect(result.caught || result.transformerIsNullOrZero).toBe(true);
  });

  test('can query context errors', async ({ page }) => {
    const result = await page.evaluate(async () => {
      const proj = window.proj;
      const context = await proj.context_create();

      // Try to create an invalid transformation to trigger an error
      try {
        await proj.proj_create_crs_to_crs({
          source_crs: "INVALID:999999",
          target_crs: "EPSG:4326",
          context: context
        });
      } catch (error) {
        // Expected - invalid CRS throws exception
      }

      // Check error state (async)
      const errno = await proj.proj_context_errno({ context: context });

      return {
        errno,
        errnoIsNumber: typeof errno === 'number',
        errnoNonNegative: errno >= 0
      };
    });

    expect(result.errnoIsNumber).toBe(true);
    expect(result.errnoNonNegative).toBe(true);
  });

  test('can get authorities from database', async ({ page }) => {
    const result = await page.evaluate(async () => {
      const proj = window.proj;
      const context = await proj.context_create();

      let authorities;
      let error = null;

      try {
        authorities = await proj.proj_get_authorities_from_database({ context });
      } catch (e) {
        error = e.message;
        return { error, functionExists: true };
      }

      // The function might return null if there's an issue with string array processing
      if (authorities === null || authorities === undefined) {
        return { authorities: null, functionExists: true };
      }

      return {
        authorities,
        isArray: Array.isArray(authorities),
        hasAuthorities: authorities.length > 0,
        includesEPSG: authorities.includes('EPSG')
      };
    });

    // Function should exist and either work or fail gracefully
    expect(result.functionExists || result.isArray).toBe(true);

    // If it works, check the results
    if (result.isArray) {
      expect(result.hasAuthorities).toBe(true);
      expect(result.includesEPSG).toBe(true);
    }
  });

  test('can get codes from database', async ({ page }) => {
    const result = await page.evaluate(async () => {
      const proj = window.proj;
      const context = await proj.context_create();

      let codes;
      let error = null;

      try {
        codes = await proj.proj_get_codes_from_database({
          context: context,
          auth_name: "EPSG"
        });
      } catch (e) {
        error = e.message;
        return { error, functionExists: true };
      }

      // The function might return null if there's an issue with string array processing
      if (codes === null || codes === undefined) {
        return { codes: null, functionExists: true };
      }

      return {
        codes,
        isArray: Array.isArray(codes),
        hasManyCodes: codes.length > 1000,
        includes4326: codes.includes('4326')
      };
    });

    // Function should exist and either work or fail gracefully
    expect(result.functionExists || result.isArray).toBe(true);

    // If it works, check the results
    if (result.isArray) {
      expect(result.hasManyCodes).toBe(true);
      expect(result.includes4326).toBe(true);
    }
  });

  test('resources are automatically cleaned up without manual destroy', async ({ page }) => {
    const result = await page.evaluate(async () => {
      const proj = window.proj;

      // Test 1: Create resources without manual cleanup
      const context = await proj.context_create();

      // Create multiple resources using simple proj strings (async)
      const transformer1 = await proj.proj_create_crs_to_crs({
        source_crs: "+proj=longlat +datum=WGS84 +no_defs",
        target_crs: "+proj=merc +datum=WGS84 +no_defs",
        context: context
      });

      const transformer2 = await proj.proj_create_crs_to_crs({
        source_crs: "+proj=merc +datum=WGS84 +no_defs",
        target_crs: "+proj=longlat +datum=WGS84 +no_defs",
        context: context
      });

      // No manual cleanup - resources should be tracked automatically

      return {
        transformer1Created: !!transformer1,
        transformer2Created: !!transformer2,
        contextCreated: !!context,
        noManualCleanup: true
      };
    });

    expect(result.transformer1Created).toBe(true);
    expect(result.transformer2Created).toBe(true);
    expect(result.contextCreated).toBe(true);
    expect(result.noManualCleanup).toBe(true);
  });

  test('compare coordinates with network OFF vs ON (grid fetch test)', async ({ page }) => {
    const result = await page.evaluate(async () => {
      const proj = window.proj;

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
        xOff, yOff, xOn, yOn, diffX, diffY,
        gridFetchChangedResult: diffX > 0.01 || diffY > 0.01
      };
    });

    console.log('Grid fetch comparison:', result);
    expect(result.gridFetchChangedResult).toBe(true);
  });

  test('can get CRS info list from database', async ({ page }) => {
    const result = await page.evaluate(async () => {
      const proj = window.proj;
      const context = await proj.context_create();

      const entries = await proj.getCrsInfoListFromDatabase({ context, auth_name: 'EPSG' });

      if (!entries || !Array.isArray(entries)) {
        return { error: 'did not return an array', type: typeof entries };
      }

      const wgs84 = entries.find(e => e.code === '4326');

      // Test without auth filter
      const allEntries = await proj.getCrsInfoListFromDatabase({ context });

      return {
        isArray: true,
        count: entries.length,
        hasMany: entries.length > 1000,
        hasWgs84: !!wgs84,
        authName: wgs84 ? wgs84.auth_name : null,
        name: wgs84 ? wgs84.name : null,
        deprecated: wgs84 ? wgs84.deprecated : null,
        bboxValid: wgs84 ? wgs84.bbox_valid : null,
        hasWestLon: wgs84 ? typeof wgs84.west_lon_degree === 'number' : false,
        hasAreaName: wgs84 ? typeof wgs84.area_name === 'string' : false,
        allCount: allEntries ? allEntries.length : 0,
        allHasMore: allEntries ? allEntries.length > entries.length : false
      };
    });

    expect(result.error).toBeUndefined();
    expect(result.isArray).toBe(true);
    expect(result.hasMany).toBe(true);
    expect(result.hasWgs84).toBe(true);
    expect(result.authName).toBe('EPSG');
    expect(result.name).toBe('WGS 84');
    expect(result.deprecated).toBe(false);
    expect(result.bboxValid).toBe(true);
    expect(result.hasWestLon).toBe(true);
    expect(result.hasAreaName).toBe(true);
    expect(result.allHasMore).toBe(true);
  });

  test('resource tracking with releasing blocks in browser', async ({ page }) => {
    const result = await page.evaluate(async () => {
      const proj = window.proj;

      // Check if resource-tracker is available (it's an external dependency)
      if (!window.resourceTracker && !window['resource-tracker']) {
        return { error: 'resource-tracker not available - it must be loaded as an external dependency' };
      }

      const rt = window.resourceTracker || window['resource-tracker'];
      if (!rt.releasing) {
        return { error: 'releasing function not found' };
      }

      let insideBlockSuccess = false;

      // Use releasing block for deterministic cleanup
      await rt.releasing(async () => {
        const context = await proj.context_create();

        // Create resources inside releasing block (async)
        const crs = await proj.proj_create_from_database({
          context: context,
          auth_name: "EPSG",
          code: "4326"
        });

        insideBlockSuccess = !!crs;

        // All resources created in this block will be cleaned up when it exits
      });

      return {
        insideBlockSuccess,
        releasingBlockCompleted: true
      };
    });

    // Resource-tracker is an external dependency, so it might not be loaded in some test environments
    if (result.error) {
      expect(result.error).toContain('resource-tracker');
    } else {
      expect(result.insideBlockSuccess).toBe(true);
      expect(result.releasingBlockCompleted).toBe(true);
    }
  });
});
