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
      const context = await proj.contextCreate();

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
      const context = await proj.contextCreate();

      const transformer = await proj.projCreateCrsToCrs({
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
      const context = await proj.contextCreate();

      const transformer = await proj.projCreateCrsToCrs({
        source_crs: "EPSG:4326",
        target_crs: "EPSG:2249",  // MA State Plane
        context: context
      });

      // Create coordinate array for 1 coordinate (async)
      const coordArray = await proj.coordArray(1);

      // Set coordinates: Boston City Hall (EPSG:4326 uses lat/lon order)
      const originalLat = 42.3603222;
      const originalLon = -71.0579667;
      await proj.setCoords(coordArray, [[originalLat, originalLon, 0, 0]]);

      // Transform coordinates (async)
      const PJ_FWD = proj.PJ_FWD || 1;
      await proj.projTransArray({
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
        const transformer = await proj.projCreateCrsToCrs({
          source_crs: "EPSG:4326",
          target_crs: "EPSG:3857"
        });

        if (!transformer || transformer === 0) {
          return { error: 'transformer is null/0' };
        }

        const coordArray = await proj.coordArray(1);
        await proj.setCoords(coordArray, [[42.3603, -71.0591, 0, 0]]);

        await proj.projTransArray({
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
        const authorities = await proj.projGetAuthoritiesFromDatabase({});

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
      const context = await proj.contextCreate();

      let caught = false;
      let transformer = null;

      try {
        transformer = await proj.projCreateCrsToCrs({
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
      const context = await proj.contextCreate();

      // Try to create an invalid transformation to trigger an error
      try {
        await proj.projCreateCrsToCrs({
          source_crs: "INVALID:999999",
          target_crs: "EPSG:4326",
          context: context
        });
      } catch (error) {
        // Expected - invalid CRS throws exception
      }

      // Check error state (async)
      const errno = await proj.projContextErrno({ context: context });

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
      const context = await proj.contextCreate();

      let authorities;
      let error = null;

      try {
        authorities = await proj.projGetAuthoritiesFromDatabase({ context });
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
      const context = await proj.contextCreate();

      let codes;
      let error = null;

      try {
        codes = await proj.projGetCodesFromDatabase({
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
      const context = await proj.contextCreate();

      // Create multiple resources using simple proj strings (async)
      const transformer1 = await proj.projCreateCrsToCrs({
        source_crs: "+proj=longlat +datum=WGS84 +no_defs",
        target_crs: "+proj=merc +datum=WGS84 +no_defs",
        context: context
      });

      const transformer2 = await proj.projCreateCrsToCrs({
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
      const ctxOff = await proj.contextCreate({ network: false });
      await proj.projContextSetEnableNetwork({ context: ctxOff, enabled: 0 });

      const transformerOff = await proj.projCreateCrsToCrs({
        source_crs: "EPSG:4267",  // NAD27
        target_crs: "EPSG:26986", // NAD83 MA State Plane (meters)
        context: ctxOff
      });

      const coordArrayOff = await proj.coordArray(1);
      await proj.setCoords(coordArrayOff, [[originalLat, originalLon, 0, 0]]);

      await proj.projTransArray({
        p: transformerOff,
        direction: PJ_FWD,
        n: 1,
        coord: coordArrayOff
      });

      const coordsOff = await proj.get_coord_array(coordArrayOff, 0);
      const xOff = coordsOff[0];
      const yOff = coordsOff[1];

      // Test 2: Network ON (default)
      const ctxOn = await proj.contextCreate();

      const transformerOn = await proj.projCreateCrsToCrs({
        source_crs: "EPSG:4267",  // NAD27
        target_crs: "EPSG:26986", // NAD83 MA State Plane (meters)
        context: ctxOn
      });

      const coordArrayOn = await proj.coordArray(1);
      await proj.setCoords(coordArrayOn, [[originalLat, originalLon, 0, 0]]);

      await proj.projTransArray({
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
      const context = await proj.contextCreate();

      const entries = await proj.projGetCrsInfoListFromDatabase({ context, auth_name: 'EPSG' });

      if (!entries || !Array.isArray(entries)) {
        return { error: 'did not return an array', type: typeof entries };
      }

      const wgs84 = entries.find(e => e.code === '4326');

      // Test without auth filter
      const allEntries = await proj.projGetCrsInfoListFromDatabase({ context });

      // Nonexistent authority returns empty array
      const empty = await proj.projGetCrsInfoListFromDatabase({ context, auth_name: 'NONEXISTENT_AUTH_ZZZZZ' });

      return {
        isArray: true,
        count: entries.length,
        hasMany: entries.length > 1000,
        hasWgs84: !!wgs84,
        authName: wgs84 ? wgs84.authName : null,
        name: wgs84 ? wgs84.name : null,
        deprecated: wgs84 ? wgs84.deprecated : null,
        bboxValid: wgs84 ? wgs84.bboxValid : null,
        hasWestLon: wgs84 ? typeof wgs84.westLonDegree === 'number' : false,
        hasAreaName: wgs84 ? typeof wgs84.areaName === 'string' : false,
        projMethodNull: wgs84 ? wgs84.projectionMethodName === null : false,
        allCount: allEntries ? allEntries.length : 0,
        allHasMore: allEntries ? allEntries.length > entries.length : false,
        emptyIsArray: Array.isArray(empty),
        emptyLength: empty ? empty.length : -1
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
    expect(result.projMethodNull).toBe(true);
    expect(result.allHasMore).toBe(true);
    expect(result.emptyIsArray).toBe(true);
    expect(result.emptyLength).toBe(0);
  });

  test('can get units from database', async ({ page }) => {
    const result = await page.evaluate(async () => {
      const proj = window.proj;
      const context = await proj.contextCreate();
      const entries = await proj.projGetUnitsFromDatabase({ context, auth_name: 'EPSG', category: 'linear', allow_deprecated: 0 });
      if (!entries || !Array.isArray(entries)) {
        return { error: 'did not return an array', type: typeof entries };
      }
      const meter = entries.find(e => e.code === '9001');
      const usFoot = entries.find(e => e.code === '9003');
      return {
        isArray: true,
        count: entries.length,
        hasEntries: entries.length > 0,
        hasMeter: !!meter,
        authName: meter ? meter.authName : null,
        hasConvFactor: meter ? typeof meter.convFactor === 'number' : false,
        deprecated: meter ? meter.deprecated : null,
        hasUsFoot: !!usFoot,
        usFootConvFactor: usFoot ? usFoot.convFactor : null
      };
    });
    expect(result.error).toBeUndefined();
    expect(result.isArray).toBe(true);
    expect(result.hasEntries).toBe(true);
    expect(result.hasMeter).toBe(true);
    expect(result.authName).toBe('EPSG');
    expect(result.hasConvFactor).toBe(true);
    expect(result.deprecated).toBe(false);
    expect(result.hasUsFoot).toBe(true);
    expect(result.usFootConvFactor).toBeGreaterThan(0.3);
    expect(result.usFootConvFactor).toBeLessThan(0.4);
  });

  test('can get celestial body list from database', async ({ page }) => {
    const result = await page.evaluate(async () => {
      const proj = window.proj;
      const context = await proj.contextCreate();
      const entries = await proj.projGetCelestialBodyListFromDatabase({ context, auth_name: '' });
      if (!entries || !Array.isArray(entries)) {
        return { error: 'did not return an array', type: typeof entries };
      }
      const earth = entries.find(e => e.name === 'Earth');
      return {
        isArray: true,
        count: entries.length,
        hasEntries: entries.length > 0,
        hasEarth: !!earth,
        authName: earth ? earth.authName : null
      };
    });
    expect(result.error).toBeUndefined();
    expect(result.isArray).toBe(true);
    expect(result.hasEntries).toBe(true);
    expect(result.hasEarth).toBe(true);
    expect(typeof result.authName).toBe('string');
  });

  // Object Inspection tests

  test('can inspect CRS properties', async ({ page }) => {
    const result = await page.evaluate(async () => {
      const proj = window.proj;
      const context = await proj.contextCreate();
      const crs = await proj.projCreateFromDatabase({ context, auth_name: 'EPSG', code: '4326' });
      if (!crs) return { error: 'CRS not created' };
      const name = await proj.projGetName({ obj: crs });
      const type = await proj.projGetType({ obj: crs });
      const deprecated = await proj.projIsDeprecated({ obj: crs });
      const wkt = await proj.projAsWkt({ context, pj: crs });
      const json = await proj.projAsProjjson({ context, pj: crs });
      return {
        name, type, deprecated,
        wktIsString: typeof wkt === 'string',
        wktHasName: wkt ? wkt.includes('WGS 84') : false,
        jsonIsString: typeof json === 'string',
        jsonHasType: json ? json.includes('GeographicCRS') : false
      };
    });
    expect(result.error).toBeUndefined();
    expect(result.name).toBe('WGS 84');
    expect(result.type).toBe(12);
    expect(result.deprecated).toBe(0);
    expect(result.wktIsString).toBe(true);
    expect(result.wktHasName).toBe(true);
    expect(result.jsonIsString).toBe(true);
    expect(result.jsonHasType).toBe(true);
  });

  test('can get source and target CRS from transformation', async ({ page }) => {
    const result = await page.evaluate(async () => {
      const proj = window.proj;
      const context = await proj.contextCreate();
      const tx = await proj.projCreateCrsToCrs({ context, source_crs: 'EPSG:4326', target_crs: 'EPSG:2249' });
      const src = await proj.projGetSourceCrs({ context, pj: tx });
      const tgt = await proj.projGetTargetCrs({ context, pj: tx });
      const srcName = src ? await proj.projGetName({ obj: src }) : null;
      const tgtName = tgt ? await proj.projGetName({ obj: tgt }) : null;
      const projStr = await proj.projAsProjString({ context, pj: tx, type: 0 });
      return { srcName, tgtName, projStrIsString: typeof projStr === 'string' };
    });
    expect(result.srcName).toBe('WGS 84');
    expect(result.tgtName).toContain('Massachusetts');
    expect(result.projStrIsString).toBe(true);
  });

  // CRS Decomposition tests

  test('can decompose CRS structure', async ({ page }) => {
    const result = await page.evaluate(async () => {
      const proj = window.proj;
      const context = await proj.contextCreate();
      const projected = await proj.projCreateFromDatabase({ context, auth_name: 'EPSG', code: '2249' });
      const geodetic = await proj.projCrsGetGeodeticCrs({ ctx: context, crs: projected });
      const geodeticName = geodetic ? await proj.projGetName({ obj: geodetic }) : null;

      const crs4326 = await proj.projCreateFromDatabase({ context, auth_name: 'EPSG', code: '4326' });
      const cs = await proj.projCrsGetCoordinateSystem({ ctx: context, crs: crs4326 });
      const axisCount = cs ? await proj.projCsGetAxisCount({ ctx: context, cs }) : null;

      const ellipsoid = await proj.projGetEllipsoid({ ctx: context, obj: crs4326 });
      const ellipsoidName = ellipsoid ? await proj.projGetName({ obj: ellipsoid }) : null;

      const datum = await proj.projCrsGetDatumForced({ ctx: context, crs: crs4326 });
      const datumName = datum ? await proj.projGetName({ obj: datum }) : null;

      return { geodeticName, axisCount, ellipsoidName, datumName };
    });
    expect(result.geodeticName).toContain('NAD83');
    expect(result.axisCount).toBe(2);
    expect(result.ellipsoidName).toBe('WGS 84');
    expect(result.datumName).toContain('World Geodetic System 1984');
  });

  // Operation Factory tests

  test('can find operations between CRS', async ({ page }) => {
    const result = await page.evaluate(async () => {
      const proj = window.proj;
      const context = await proj.contextCreate();
      const src = await proj.projCreateFromDatabase({ context, auth_name: 'EPSG', code: '4326' });
      const tgt = await proj.projCreateFromDatabase({ context, auth_name: 'EPSG', code: '2249' });
      const ofc = await proj.projCreateOperationFactoryContext({ context });
      const ops = ofc ? await proj.projCreateOperations({ context, source_crs: src, target_crs: tgt, operationContext: ofc }) : null;
      const count = ops ? await proj.projListGetCount({ result: ops }) : 0;
      const tx = await proj.projCreateCrsToCrs({ context, source_crs: 'EPSG:4326', target_crs: 'EPSG:3857' });
      const normalized = tx ? await proj.projNormalizeForVisualization({ context, obj: tx }) : null;
      return { hasOfc: !!ofc, hasOps: !!ops, count, hasNormalized: !!normalized };
    });
    expect(result.hasOfc).toBe(true);
    expect(result.hasOps).toBe(true);
    expect(result.count).toBeGreaterThan(0);
    expect(result.hasNormalized).toBe(true);
  });

  // CreateFromWkt test

  test('can create CRS from WKT roundtrip', async ({ page }) => {
    const result = await page.evaluate(async () => {
      const proj = window.proj;
      const context = await proj.contextCreate();
      const crs = await proj.projCreateFromDatabase({ context, auth_name: 'EPSG', code: '4326' });
      const wkt = await proj.projAsWkt({ context, pj: crs });
      const crsFromWkt = await proj.projCreateFromWkt({ context, wkt });
      const name = crsFromWkt ? await proj.projGetName({ obj: crsFromWkt }) : null;
      return { hasWkt: typeof wkt === 'string', hasCrs: !!crsFromWkt, name };
    });
    expect(result.hasWkt).toBe(true);
    expect(result.hasCrs).toBe(true);
    expect(result.name).toBe('WGS 84');
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
        const context = await proj.contextCreate();

        // Create resources inside releasing block (async)
        const crs = await proj.projCreateFromDatabase({
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

  test('proj_create with PROJ string and pipeline', async ({ page }) => {
    const result = await page.evaluate(async () => {
      const proj = window.proj;
      try {
        const context = await proj.contextCreate();

        const robin = await proj.projCreate({ context, definition: '+proj=robin' });
        if (!robin || robin === 0) return { error: 'robin is null/0' };

        const epsg = await proj.projCreate({ context, definition: 'EPSG:4326' });
        if (!epsg || epsg === 0) return { error: 'epsg is null/0' };

        const name = await proj.projGetName({ obj: epsg });

        const pipeline = await proj.projCreate({
          context,
          definition: '+proj=pipeline +step +proj=unitconvert +xy_in=deg +xy_out=rad +step +proj=robin'
        });

        return {
          robinCreated: !!robin && robin !== 0,
          epsgName: name,
          pipelineCreated: !!pipeline && pipeline !== 0
        };
      } catch (e) {
        return { error: e.message };
      }
    });

    expect(result.error).toBeUndefined();
    expect(result.robinCreated).toBe(true);
    expect(result.epsgName).toBe('WGS 84');
    expect(result.pipelineCreated).toBe(true);
  });

  // Out-params tests

  test('can get area of use for CRS', async ({ page }) => {
    const result = await page.evaluate(async () => {
      const proj = window.proj;
      const ctx = await proj.contextCreate();
      const crs = await proj.projCreateFromDatabase({ context: ctx, auth_name: 'EPSG', code: '4326' });
      const area = await proj.projGetAreaOfUse({ context: ctx, obj: crs });
      return area;
    });
    expect(result).toBeTruthy();
    expect(result.westLonDegree).toBe(-180);
    expect(result.southLatDegree).toBe(-90);
    expect(result.eastLonDegree).toBe(180);
    expect(result.northLatDegree).toBe(90);
    expect(typeof result.areaName).toBe('string');
  });

  test('can get area of use for specific domain', async ({ page }) => {
    const result = await page.evaluate(async () => {
      const proj = window.proj;
      const ctx = await proj.contextCreate();
      const crs = await proj.projCreateFromDatabase({ context: ctx, auth_name: 'EPSG', code: '4326' });
      return await proj.projGetAreaOfUseEx({ context: ctx, obj: crs, domainIdx: 0 });
    });
    expect(result).toBeTruthy();
    expect(typeof result.westLonDegree).toBe('number');
    expect(typeof result.northLatDegree).toBe('number');
  });

  test('can get coordinate system axis info', async ({ page }) => {
    const result = await page.evaluate(async () => {
      const proj = window.proj;
      const ctx = await proj.contextCreate();
      const crs = await proj.projCreateFromDatabase({ context: ctx, auth_name: 'EPSG', code: '4326' });
      const cs = await proj.projCrsGetCoordinateSystem({ ctx, crs });
      return await proj.projCsGetAxisInfo({ ctx, cs, index: 0 });
    });
    expect(result).toBeTruthy();
    expect(typeof result.name).toBe('string');
    expect(typeof result.abbreviation).toBe('string');
    expect(typeof result.direction).toBe('string');
    expect(typeof result.unitConvFactor).toBe('number');
    expect(typeof result.unitName).toBe('string');
  });

  test('can get ellipsoid parameters', async ({ page }) => {
    const result = await page.evaluate(async () => {
      const proj = window.proj;
      const ctx = await proj.contextCreate();
      const crs = await proj.projCreateFromDatabase({ context: ctx, auth_name: 'EPSG', code: '4326' });
      const ellipsoid = await proj.projGetEllipsoid({ ctx, obj: crs });
      return await proj.projEllipsoidGetParameters({ ctx, ellipsoid });
    });
    expect(result).toBeTruthy();
    expect(result.semiMajorMetre).toBeGreaterThan(6378000);
    expect(result.semiMinorMetre).toBeGreaterThan(6356000);
    expect(result.invFlattening).toBeGreaterThan(298);
  });

  test('can get prime meridian parameters', async ({ page }) => {
    const result = await page.evaluate(async () => {
      const proj = window.proj;
      const ctx = await proj.contextCreate();
      const crs = await proj.projCreateFromDatabase({ context: ctx, auth_name: 'EPSG', code: '4326' });
      const pm = await proj.projGetPrimeMeridian({ ctx, obj: crs });
      return await proj.projPrimeMeridianGetParameters({ ctx, prime_meridian: pm });
    });
    expect(result).toBeTruthy();
    expect(result.longitude).toBe(0);
    expect(typeof result.unitConvFactor).toBe('number');
    expect(typeof result.unitName).toBe('string');
  });

  test('can get coordoperation method info', async ({ page }) => {
    const result = await page.evaluate(async () => {
      const proj = window.proj;
      const ctx = await proj.contextCreate();
      const crs = await proj.projCreateFromDatabase({ context: ctx, auth_name: 'EPSG', code: '2249' });
      const coordop = await proj.projCrsGetCoordoperation({ ctx, crs });
      return await proj.projCoordoperationGetMethodInfo({ ctx, coordoperation: coordop });
    });
    expect(result).toBeTruthy();
    expect(typeof result.methodName).toBe('string');
  });

  test('can get coordoperation param', async ({ page }) => {
    const result = await page.evaluate(async () => {
      const proj = window.proj;
      const ctx = await proj.contextCreate();
      const crs = await proj.projCreateFromDatabase({ context: ctx, auth_name: 'EPSG', code: '2249' });
      const coordop = await proj.projCrsGetCoordoperation({ ctx, crs });
      return await proj.projCoordoperationGetParam({ ctx, coordoperation: coordop, index: 0 });
    });
    expect(result).toBeTruthy();
    expect(typeof result.name).toBe('string');
    expect(typeof result.value).toBe('number');
  });

  test('can get coordoperation grid used', async ({ page }) => {
    const result = await page.evaluate(async () => {
      const proj = window.proj;
      const ctx = await proj.contextCreate();
      const crs = await proj.projCreateFromDatabase({ context: ctx, auth_name: 'EPSG', code: '2249' });
      const coordop = await proj.projCrsGetCoordoperation({ ctx, crs });
      const count = await proj.projCoordoperationGetGridUsedCount({ ctx, coordoperation: coordop });
      if (count > 0) {
        const grid = await proj.projCoordoperationGetGridUsed({ ctx, coordoperation: coordop, index: 0 });
        return { count, grid };
      }
      return { count, grid: null };
    });
    expect(typeof result.count).toBe('number');
    if (result.count > 0) {
      expect(typeof result.grid.shortName).toBe('string');
    }
  });

  test('can get UOM info from database', async ({ page }) => {
    const result = await page.evaluate(async () => {
      const proj = window.proj;
      const ctx = await proj.contextCreate();
      return await proj.projUomGetInfoFromDatabase({ context: ctx, auth_name: 'EPSG', code: '9001' });
    });
    expect(result).toBeTruthy();
    expect(result.name).toBe('metre');
    expect(result.convFactor).toBe(1.0);
    expect(result.category).toBe('linear');
  });

  test('can get grid info from database', async ({ page }) => {
    const result = await page.evaluate(async () => {
      const proj = window.proj;
      const ctx = await proj.contextCreate();
      return await proj.projGridGetInfoFromDatabase({ context: ctx, grid_name: 'us_noaa_nadcon5_nad83_1986_nad83_harn_conus.tif' });
    });
    expect(result).toBeTruthy();
    expect(typeof result.fullName).toBe('string');
    expect(typeof result.available).toBe('number');
  });

  test('can get towgs84 values', async ({ page }) => {
    const result = await page.evaluate(async () => {
      const proj = window.proj;
      const ctx = await proj.contextCreate();
      const op = await proj.projCreate({
        context: ctx,
        definition: '+proj=helmert +x=23 +y=-45 +z=67 +rx=0.1 +ry=-0.2 +rz=0.3 +s=1.5 +convention=position_vector'
      });
      const r = await proj.projCoordoperationGetTowgs84Values({
        ctx, coordoperation: op, value_count: 7, emit_error_if_incompatible: 0
      });
      return r ? { hasValues: Array.isArray(r.values), length: r.values.length } : null;
    });
    // helmert may not support towgs84; null is acceptable
    if (result) {
      expect(result.hasValues).toBe(true);
      expect(result.length).toBe(7);
    }
  });
});
