// Copyright (c) 2024, 2025, 2026 Will Cohen
//
// Part of clj-proj, under the MIT License.
// See LICENSE for license information.
// SPDX-License-Identifier: MIT

package net.willcohen.proj;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Test for the Java PROJ API.
 */
public class PROJTest {

    private static int testsPassed = 0;
    private static int testsFailed = 0;

    public static void main(String[] args) {
        System.out.println("=== PROJ Java API Test ===\n");

        try {
            if (Arrays.asList(args).contains("--graal")) {
                System.out.println("Forcing GraalVM WASM backend...");
                PROJ.forceGraal();
            }

            run("PROJ.init()", PROJTest::testInit);
            run("Backend detection", PROJTest::testBackendCheck);
            run("PROJ.contextCreate()", PROJTest::testContextCreate);
            run("PROJ.getAuthoritiesFromDatabase()", PROJTest::testGetAuthorities);
            run("PROJ.getCodesFromDatabase()", PROJTest::testGetCodes);
            run("Coordinate transformation", PROJTest::testTransformation);
            run("Coordinate transformation from PJ objects", PROJTest::testTransformationFromPj);
            run("PROJ.getCrsInfoListFromDatabase()", PROJTest::testGetCrsInfoList);
            run("PROJ.getUnitsFromDatabase()", PROJTest::testGetUnits);
            run("PROJ.getCelestialBodyListFromDatabase()", PROJTest::testGetCelestialBodies);
            run("PROJ.create()", PROJTest::testCreate);
            run("getAreaOfUse", PROJTest::testGetAreaOfUse);
            run("getAreaOfUseEx", PROJTest::testGetAreaOfUseEx);
            run("csGetAxisInfo", PROJTest::testCsGetAxisInfo);
            run("ellipsoidGetParameters", PROJTest::testEllipsoidGetParameters);
            run("primeMeridianGetParameters", PROJTest::testPrimeMeridianGetParameters);
            run("coordoperationGetMethodInfo", PROJTest::testCoordoperationGetMethodInfo);
            run("coordoperationGetParam", PROJTest::testCoordoperationGetParam);
            run("coordoperationGetGridUsed", PROJTest::testCoordoperationGetGridUsed);
            run("uomGetInfoFromDatabase", PROJTest::testUomGetInfoFromDatabase);
            run("gridGetInfoFromDatabase", PROJTest::testGridGetInfoFromDatabase);

            System.out.println("\n=== Test Results ===");
            System.out.println("Passed: " + testsPassed);
            System.out.println("Failed: " + testsFailed);

            if (testsFailed > 0) {
                System.exit(1);
            }
        } catch (Exception e) {
            System.err.println("Test suite failed with exception:");
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void testInit() {
        PROJ.init();
        pass("Initialization successful");
    }

    private static void testBackendCheck() {
        boolean isFfi = PROJ.isFfi();
        boolean isGraal = PROJ.isGraal();
        check(isFfi || isGraal, "Backend detected: " + (isFfi ? "FFI" : "GraalVM"),
              "No backend detected (both isFfi and isGraal are false)");
    }

    private static void testContextCreate() {
        Object ctx = PROJ.contextCreate();
        if (!check(ctx != null, "Context created successfully", "Context is null")) return;
        check(PROJ.isContext(ctx), "isContext() returns true for context", "isContext() returns false for valid context");
    }

    private static void testGetAuthorities() {
        List<String> authorities = PROJ.getAuthoritiesFromDatabase();
        if (authorities == null || authorities.isEmpty()) { fail("No authorities returned"); return; }
        pass("Got " + authorities.size() + " authorities");
        check(authorities.contains("EPSG"), "EPSG authority found", "EPSG authority not found in: " + authorities);
    }

    private static void testGetCodes() {
        List<String> codes = PROJ.getCodesFromDatabase("EPSG");
        if (codes == null || codes.isEmpty()) { fail("No codes returned for EPSG"); return; }
        pass("Got " + codes.size() + " EPSG codes");
        check(codes.contains("4326"), "EPSG:4326 found", "EPSG:4326 not found");
    }

    private static void testTransformation() {
        Object ctx = PROJ.contextCreate();
        // EPSG:4326 is WGS84 (lat/lon), EPSG:2249 is MA State Plane (feet).
        Object transform = PROJ.createCrsToCrs(ctx, "EPSG:4326", "EPSG:2249");
        if (!check(transform != null, "Transformation created", "createCrsToCrs returned null")) return;
        Object coords = PROJ.coordArray(1);
        if (!check(coords != null, "Coordinate array created", "coordArray returned null")) return;

        // Boston City Hall. EPSG:4326 expects lat/lon order.
        PROJ.setCoords(coords, new double[][] {{42.3603222, -71.0579667}});
        pass("Coordinates set");
        checkTransResult(PROJ.transArray(transform, coords, 1), "Transformation executed successfully");

        double[] transformed = PROJ.getCoords(coords, 0);
        if (transformed == null) { fail("getCoords returned null"); return; }
        double x = transformed[0];
        double y = transformed[1];
        // Boston City Hall in MA State Plane: X ~775,200 ft, Y ~2,956,400 ft.
        check(x > 775000 && x < 776000, "X coordinate correct: " + x, "X coordinate should be ~775,200, got " + x);
        check(y > 2956000 && y < 2957000, "Y coordinate correct: " + y, "Y coordinate should be ~2,956,400, got " + y);
    }

    private static void testTransformationFromPj() {
        Object ctx = PROJ.contextCreate();
        Object sourceCrs = PROJ.createFromDatabase(ctx, "EPSG", "4326");
        if (!check(sourceCrs != null, "Source CRS (EPSG:4326) created from database",
                   "createFromDatabase returned null for EPSG:4326")) return;
        Object targetCrs = PROJ.createFromDatabase(ctx, "EPSG", "2249");
        if (!check(targetCrs != null, "Target CRS (EPSG:2249) created from database",
                   "createFromDatabase returned null for EPSG:2249")) return;
        Object transform = PROJ.createCrsToCrsFromPj(ctx, sourceCrs, targetCrs);
        if (!check(transform != null, "Transformation created from PJ objects",
                   "createCrsToCrsFromPj returned null")) return;

        Object coords = PROJ.coordArray(1);
        PROJ.setCoords(coords, new double[][] {{42.3603222, -71.0579667}}); // Boston City Hall
        checkTransResult(PROJ.transArray(transform, coords, 1), "Transformation from PJ objects executed successfully");
    }

    private static void testGetCrsInfoList() {
        Object ctx = PROJ.contextCreate();
        List<Map<String, Object>> entries = PROJ.getCrsInfoListFromDatabase(ctx, "EPSG");
        if (entries == null || entries.isEmpty()) { fail("No CRS info entries returned for EPSG"); return; }
        check(entries.size() > 1000, "Got " + entries.size() + " EPSG CRS info entries",
              "Expected >1000 EPSG entries, got " + entries.size());

        Map<String, Object> wgs84 = find(entries, "code", "4326");
        if (check(wgs84 != null, "Found EPSG:4326 (WGS 84)", "EPSG:4326 not found in CRS info list")) {
            check("EPSG".equals(wgs84.get("authName")), "authName is EPSG", "authName should be EPSG, got " + wgs84.get("authName"));
            check("WGS 84".equals(wgs84.get("name")), "name is WGS 84", "name should be WGS 84, got " + wgs84.get("name"));
            check(Boolean.FALSE.equals(wgs84.get("deprecated")), "deprecated is false",
                  "deprecated should be false, got " + wgs84.get("deprecated"));
        }

        List<Map<String, Object>> allEntries = PROJ.getCrsInfoListFromDatabase(ctx);
        check(allEntries.size() > entries.size(),
              "All-authority query returned more entries (" + allEntries.size() + ") than EPSG-only",
              "All-authority query should return more entries than EPSG-only");
    }

    private static void testGetUnits() {
        Object ctx = PROJ.contextCreate();
        List<Map<String, Object>> entries = PROJ.getUnitsFromDatabase(ctx, "EPSG", "linear", false);
        if (entries == null || entries.isEmpty()) { fail("No unit entries returned for EPSG linear"); return; }
        pass("Got " + entries.size() + " EPSG linear unit entries");

        Map<String, Object> meter = find(entries, "code", "9001");
        if (meter == null) {
            fail("EPSG:9001 (metre) not found in results");
        } else {
            pass("Found EPSG:9001 (metre): " + meter.get("name"));
            check(meter.get("convFactor") instanceof Number, "conv-factor is a number: " + meter.get("convFactor"),
                  "conv-factor is not a number: " + meter.get("convFactor"));
        }
        Map<String, Object> usFoot = find(entries, "code", "9003");
        if (usFoot == null) {
            fail("EPSG:9003 (US survey foot) not found in results");
        } else {
            double cf = ((Number) usFoot.get("convFactor")).doubleValue();
            check(cf > 0.3 && cf < 0.4, "Found EPSG:9003 (US survey foot), conv-factor=" + cf,
                  "US survey foot conv-factor out of range: " + cf);
        }

        List<Map<String, Object>> all = PROJ.getUnitsFromDatabase(ctx, "EPSG", null, false);
        check(find(all, "code", "9101") != null, "A null category lists every category (" + all.size() + " units)",
              "A null category did not list EPSG:9101 (radian), got " + all.size() + " units");
    }

    private static void testGetCelestialBodies() {
        Object ctx = PROJ.contextCreate();
        List<Map<String, Object>> entries = PROJ.getCelestialBodyListFromDatabase(ctx);
        if (entries == null || entries.isEmpty()) { fail("No celestial body entries returned"); return; }
        pass("Got " + entries.size() + " celestial body entries");
        Map<String, Object> earth = find(entries, "name", "Earth");
        if (earth != null) pass("Found Earth: authName=" + earth.get("authName"));
        else fail("Earth not found in celestial body results");
    }

    private static void testCreate() {
        Object ctx = PROJ.contextCreate();
        check(PROJ.create(ctx, "+proj=robin") != null, "Created PJ from PROJ string (+proj=robin)",
              "create(+proj=robin) returned null");
        check(PROJ.create(ctx, "EPSG:4326") != null, "Created PJ from EPSG code", "create(EPSG:4326) returned null");
        check(PROJ.create(ctx, "+proj=pipeline +step +proj=unitconvert +xy_in=deg +xy_out=rad +step +proj=robin") != null,
              "Created PJ from pipeline definition", "create(pipeline) returned null");
        check(PROJ.create("EPSG:4326") != null, "Created PJ without explicit context",
              "create(EPSG:4326) without context returned null");
    }

    private static void testGetAreaOfUse() {
        Object ctx = PROJ.contextCreate();
        Object crs = PROJ.createFromDatabase(ctx, "EPSG", "4326");
        Map<String, Object> area = PROJ.getAreaOfUse(ctx, crs);
        if (area == null) { fail("getAreaOfUse returned null"); return; }
        assertEqual("westLonDegree", -180.0, (Double) area.get("westLonDegree"));
        assertEqual("southLatDegree", -90.0, (Double) area.get("southLatDegree"));
        assertEqual("eastLonDegree", 180.0, (Double) area.get("eastLonDegree"));
        assertEqual("northLatDegree", 90.0, (Double) area.get("northLatDegree"));
        check(area.get("areaName") instanceof String, "getAreaOfUse returned valid AreaOfUse", "areaName is not a string");
    }

    private static void testGetAreaOfUseEx() {
        Object ctx = PROJ.contextCreate();
        Object crs = PROJ.createFromDatabase(ctx, "EPSG", "4326");
        Map<String, Object> area = PROJ.getAreaOfUseEx(ctx, crs, 0);
        if (area == null) { fail("getAreaOfUseEx returned null"); return; }
        check(area.get("westLonDegree") instanceof Number, "getAreaOfUseEx returned valid AreaOfUse",
              "westLonDegree is not a number");
    }

    private static void testCsGetAxisInfo() {
        Object ctx = PROJ.contextCreate();
        Object crs = PROJ.createFromDatabase(ctx, "EPSG", "4326");
        Object cs = PROJ.crsGetCoordinateSystem(ctx, crs);
        Map<String, Object> axis = PROJ.csGetAxisInfo(ctx, cs, 0);
        if (axis == null) { fail("csGetAxisInfo returned null"); return; }
        check(axis.get("name") instanceof String && axis.get("unitConvFactor") instanceof Number,
              "csGetAxisInfo returned valid AxisInfo: " + axis.get("name"), "AxisInfo has wrong types");
    }

    private static void testEllipsoidGetParameters() {
        Object ctx = PROJ.contextCreate();
        Object crs = PROJ.createFromDatabase(ctx, "EPSG", "4326");
        Object ellipsoid = PROJ.getEllipsoid(ctx, crs);
        Map<String, Object> params = PROJ.ellipsoidGetParameters(ctx, ellipsoid);
        if (params == null) { fail("ellipsoidGetParameters returned null"); return; }
        double semiMajor = ((Number) params.get("semiMajorMetre")).doubleValue();
        double invFlat = ((Number) params.get("invFlattening")).doubleValue();
        check(semiMajor > 6378000 && invFlat > 298, "ellipsoidGetParameters: semiMajor=" + semiMajor + " invFlat=" + invFlat,
              "Unexpected ellipsoid values: " + params);
    }

    private static void testPrimeMeridianGetParameters() {
        Object ctx = PROJ.contextCreate();
        Object crs = PROJ.createFromDatabase(ctx, "EPSG", "4326");
        Object pm = PROJ.getPrimeMeridian(ctx, crs);
        Map<String, Object> params = PROJ.primeMeridianGetParameters(ctx, pm);
        if (params == null) { fail("primeMeridianGetParameters returned null"); return; }
        double lon = ((Number) params.get("longitude")).doubleValue();
        check(lon == 0.0 && params.get("unitName") instanceof String,
              "primeMeridianGetParameters: longitude=0.0 unit=" + params.get("unitName"), "Unexpected PM values: " + params);
    }

    private static void testCoordoperationGetMethodInfo() {
        Object ctx = PROJ.contextCreate();
        Object crs = PROJ.createFromDatabase(ctx, "EPSG", "2249");
        Object coordop = PROJ.crsGetCoordoperation(ctx, crs);
        Map<String, Object> info = PROJ.coordoperationGetMethodInfo(ctx, coordop);
        if (info == null) { fail("coordoperationGetMethodInfo returned null"); return; }
        check(info.get("methodName") instanceof String, "coordoperationGetMethodInfo: " + info.get("methodName"),
              "methodName is not a string");
    }

    private static void testCoordoperationGetParam() {
        Object ctx = PROJ.contextCreate();
        Object crs = PROJ.createFromDatabase(ctx, "EPSG", "2249");
        Object coordop = PROJ.crsGetCoordoperation(ctx, crs);
        Map<String, Object> param = PROJ.coordoperationGetParam(ctx, coordop, 0);
        if (param == null) { fail("coordoperationGetParam returned null"); return; }
        check(param.get("name") instanceof String && param.get("value") instanceof Number,
              "coordoperationGetParam: " + param.get("name") + "=" + param.get("value"), "param has wrong types: " + param);
    }

    private static void testCoordoperationGetGridUsed() {
        Object ctx = PROJ.contextCreate();
        Object crs = PROJ.createFromDatabase(ctx, "EPSG", "2249");
        Object coordop = PROJ.crsGetCoordoperation(ctx, crs);
        if (PROJ.coordoperationGetGridUsedCount(ctx, coordop) <= 0) {
            pass("coordoperationGetGridUsed: no grids used (count=0)");
            return;
        }
        Map<String, Object> grid = PROJ.coordoperationGetGridUsed(ctx, coordop, 0);
        if (grid != null && grid.get("shortName") instanceof String) pass("coordoperationGetGridUsed: " + grid.get("shortName"));
        else fail("grid info has wrong structure");
    }

    private static void testUomGetInfoFromDatabase() {
        Object ctx = PROJ.contextCreate();
        Map<String, Object> info = PROJ.uomGetInfoFromDatabase(ctx, "EPSG", "9001");
        if (info == null) { fail("uomGetInfoFromDatabase returned null"); return; }
        check("metre".equals(info.get("name")) && ((Number) info.get("convFactor")).doubleValue() == 1.0,
              "uomGetInfoFromDatabase: metre conv=1.0 cat=" + info.get("category"), "Unexpected UOM values: " + info);
    }

    private static void testGridGetInfoFromDatabase() {
        Object ctx = PROJ.contextCreate();
        Map<String, Object> info = PROJ.gridGetInfoFromDatabase(ctx, "us_noaa_nadcon5_nad83_1986_nad83_harn_conus.tif");
        if (info == null) { fail("gridGetInfoFromDatabase returned null"); return; }
        check(info.get("fullName") instanceof String && info.get("available") instanceof Number,
              "gridGetInfoFromDatabase: " + info.get("fullName"), "Unexpected grid info: " + info);
    }

    private static void run(String name, Runnable test) {
        System.out.println("Test: " + name);
        try {
            test.run();
        } catch (Exception e) {
            fail(name + " failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Both messages are built before the test, so neither may dereference a
    // value that can be null.
    private static boolean check(boolean ok, String passMessage, String failMessage) {
        if (ok) pass(passMessage);
        else fail(failMessage);
        return ok;
    }

    private static void checkTransResult(int result, String passMessage) {
        if (result == 0) pass(passMessage);
        else fail("Transformation returned error code: " + result + " (" + PROJ.errorCodeToString(result) + ")");
    }

    private static Map<String, Object> find(List<Map<String, Object>> entries, String key, String value) {
        return entries.stream().filter(e -> value.equals(e.get(key))).findFirst().orElse(null);
    }

    private static void assertEqual(String field, double expected, double actual) {
        check(expected == actual, field + " = " + actual, field + " expected " + expected + " but got " + actual);
    }

    private static void pass(String message) {
        System.out.println("  PASS: " + message);
        testsPassed++;
    }

    private static void fail(String message) {
        System.out.println("  FAIL: " + message);
        testsFailed++;
    }
}
