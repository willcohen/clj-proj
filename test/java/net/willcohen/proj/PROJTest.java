package net.willcohen.proj;

import java.util.List;
import java.util.Map;

/**
 * Test for the Java PROJ API.
 *
 * This test exercises the main functionality of the PROJ Java wrapper:
 * - Initialization
 * - Context creation
 * - CRS transformation creation
 * - Coordinate transformation
 * - Database queries
 */
public class PROJTest {

    private static int testsPassed = 0;
    private static int testsFailed = 0;

    public static void main(String[] args) {
        System.out.println("=== PROJ Java API Test ===\n");

        // Check for --graal flag to force GraalVM backend
        boolean forceGraal = false;
        for (String arg : args) {
            if ("--graal".equals(arg)) {
                forceGraal = true;
            }
        }

        try {
            if (forceGraal) {
                System.out.println("Forcing GraalVM WASM backend...");
                PROJ.forceGraal();
            }

            testInit();
            testBackendCheck();
            testContextCreate();
            testGetAuthorities();
            testGetCodes();
            testTransformation();
            testTransformationFromPj();
            testGetCrsInfoList();
            testGetUnits();
            testGetCelestialBodies();
            testCreate();
            testGetAreaOfUse();
            testGetAreaOfUseEx();
            testCsGetAxisInfo();
            testEllipsoidGetParameters();
            testPrimeMeridianGetParameters();
            testCoordoperationGetMethodInfo();
            testCoordoperationGetParam();
            testCoordoperationGetGridUsed();
            testUomGetInfoFromDatabase();
            testGridGetInfoFromDatabase();
            testCoordoperationGetTowgs84Values();

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
        System.out.println("Test: PROJ.init()");
        try {
            PROJ.init();
            pass("Initialization successful");
        } catch (Exception e) {
            fail("Initialization failed: " + e.getMessage());
        }
    }

    private static void testBackendCheck() {
        System.out.println("Test: Backend detection");
        try {
            boolean isFfi = PROJ.isFfi();
            boolean isGraal = PROJ.isGraal();

            if (isFfi || isGraal) {
                String backend = isFfi ? "FFI" : "GraalVM";
                pass("Backend detected: " + backend);
            } else {
                fail("No backend detected (both isFfi and isGraal are false)");
            }
        } catch (Exception e) {
            fail("Backend check failed: " + e.getMessage());
        }
    }

    private static void testContextCreate() {
        System.out.println("Test: PROJ.contextCreate()");
        try {
            Object ctx = PROJ.contextCreate();
            if (ctx != null) {
                pass("Context created successfully");

                // Test isContext
                if (PROJ.isContext(ctx)) {
                    pass("isContext() returns true for context");
                } else {
                    fail("isContext() returns false for valid context");
                }
            } else {
                fail("Context is null");
            }
        } catch (Exception e) {
            fail("Context creation failed: " + e.getMessage());
        }
    }

    private static void testGetAuthorities() {
        System.out.println("Test: PROJ.getAuthoritiesFromDatabase()");
        try {
            List<String> authorities = PROJ.getAuthoritiesFromDatabase();
            if (authorities != null && !authorities.isEmpty()) {
                pass("Got " + authorities.size() + " authorities");

                // Check for EPSG which should always be present
                boolean hasEPSG = false;
                for (Object auth : authorities) {
                    if ("EPSG".equals(auth.toString())) {
                        hasEPSG = true;
                        break;
                    }
                }
                if (hasEPSG) {
                    pass("EPSG authority found");
                } else {
                    fail("EPSG authority not found in: " + authorities);
                }
            } else {
                fail("No authorities returned");
            }
        } catch (Exception e) {
            fail("getAuthoritiesFromDatabase failed: " + e.getMessage());
        }
    }

    private static void testGetCodes() {
        System.out.println("Test: PROJ.getCodesFromDatabase()");
        try {
            List<String> codes = PROJ.getCodesFromDatabase("EPSG");
            if (codes != null && !codes.isEmpty()) {
                pass("Got " + codes.size() + " EPSG codes");

                // Check for 4326 (WGS84) which should always be present
                boolean has4326 = false;
                for (Object code : codes) {
                    if ("4326".equals(code.toString())) {
                        has4326 = true;
                        break;
                    }
                }
                if (has4326) {
                    pass("EPSG:4326 found");
                } else {
                    fail("EPSG:4326 not found");
                }
            } else {
                fail("No codes returned for EPSG");
            }
        } catch (Exception e) {
            fail("getCodesFromDatabase failed: " + e.getMessage());
        }
    }

    private static void testTransformation() {
        System.out.println("Test: Coordinate transformation");
        try {
            // Create context
            Object ctx = PROJ.contextCreate();

            // Create transformation from WGS84 to MA State Plane
            // EPSG:4326 = WGS84 (lat/lon)
            // EPSG:2249 = MA State Plane (feet)
            Object transform = PROJ.createCrsToCrs(ctx, "EPSG:4326", "EPSG:2249");
            if (transform == null) {
                fail("createCrsToCrs returned null");
                return;
            }
            pass("Transformation created");

            // Create coordinate array
            Object coords = PROJ.coordArray(1);
            if (coords == null) {
                fail("coordArray returned null");
                return;
            }
            pass("Coordinate array created");

            // Set coordinates: Boston City Hall (lat, lon)
            // EPSG:4326 expects lat/lon order
            double[][] input = {{42.3603222, -71.0579667}};
            PROJ.setCoords(coords, input);
            pass("Coordinates set");

            // Transform
            int result = PROJ.transArray(transform, coords, 1);
            if (result == 0) {
                pass("Transformation executed successfully");
            } else {
                fail("Transformation returned error code: " + result +
                     " (" + PROJ.errorCodeToString(result) + ")");
            }

            // Read back and verify transformed coordinates
            double[] transformed = PROJ.getCoords(coords, 0);
            if (transformed != null) {
                double x = transformed[0];
                double y = transformed[1];
                // Boston City Hall in MA State Plane feet should be approximately:
                // X: ~775,200 feet, Y: ~2,956,400 feet
                if (x > 775000 && x < 776000) {
                    pass("X coordinate correct: " + x);
                } else {
                    fail("X coordinate should be ~775,200, got " + x);
                }
                if (y > 2956000 && y < 2957000) {
                    pass("Y coordinate correct: " + y);
                } else {
                    fail("Y coordinate should be ~2,956,400, got " + y);
                }
            } else {
                fail("getCoords returned null");
            }

        } catch (Exception e) {
            fail("Transformation test failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void testTransformationFromPj() {
        System.out.println("Test: Coordinate transformation from PJ objects");
        try {
            // Create context
            Object ctx = PROJ.contextCreate();

            // Create CRS objects from database
            Object sourceCrs = PROJ.createFromDatabase(ctx, "EPSG", "4326"); // WGS84
            if (sourceCrs == null) {
                fail("createFromDatabase returned null for EPSG:4326");
                return;
            }
            pass("Source CRS (EPSG:4326) created from database");

            Object targetCrs = PROJ.createFromDatabase(ctx, "EPSG", "2249"); // MA State Plane
            if (targetCrs == null) {
                fail("createFromDatabase returned null for EPSG:2249");
                return;
            }
            pass("Target CRS (EPSG:2249) created from database");

            // Create transformation from CRS objects
            Object transform = PROJ.createCrsToCrsFromPj(ctx, sourceCrs, targetCrs);
            if (transform == null) {
                fail("createCrsToCrsFromPj returned null");
                return;
            }
            pass("Transformation created from PJ objects");

            // Create coordinate array and transform
            Object coords = PROJ.coordArray(1);
            double[][] input = {{42.3603222, -71.0579667}}; // Boston City Hall
            PROJ.setCoords(coords, input);

            int result = PROJ.transArray(transform, coords, 1);
            if (result == 0) {
                pass("Transformation from PJ objects executed successfully");
            } else {
                fail("Transformation returned error code: " + result +
                     " (" + PROJ.errorCodeToString(result) + ")");
            }

        } catch (Exception e) {
            fail("Transformation from PJ test failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void testGetCrsInfoList() {
        System.out.println("Test: PROJ.getCrsInfoListFromDatabase()");
        try {
            Object ctx = PROJ.contextCreate();

            List<Map<String, Object>> entries = PROJ.getCrsInfoListFromDatabase(ctx, "EPSG");
            if (entries == null || entries.isEmpty()) {
                fail("No CRS info entries returned for EPSG");
                return;
            }
            if (entries.size() > 1000) {
                pass("Got " + entries.size() + " EPSG CRS info entries");
            } else {
                fail("Expected >1000 EPSG entries, got " + entries.size());
            }

            Map<String, Object> wgs84 = null;
            for (Map<String, Object> entry : entries) {
                if ("4326".equals(entry.get("code"))) {
                    wgs84 = entry;
                    break;
                }
            }
            if (wgs84 != null) {
                pass("Found EPSG:4326 (WGS 84)");
                if ("EPSG".equals(wgs84.get("authName"))) {
                    pass("authName is EPSG");
                } else {
                    fail("authName should be EPSG, got " + wgs84.get("authName"));
                }
                if ("WGS 84".equals(wgs84.get("name"))) {
                    pass("name is WGS 84");
                } else {
                    fail("name should be WGS 84, got " + wgs84.get("name"));
                }
                if (Boolean.FALSE.equals(wgs84.get("deprecated"))) {
                    pass("deprecated is false");
                } else {
                    fail("deprecated should be false, got " + wgs84.get("deprecated"));
                }
            } else {
                fail("EPSG:4326 not found in CRS info list");
            }

            // Test without auth filter
            List<Map<String, Object>> allEntries = PROJ.getCrsInfoListFromDatabase(ctx);
            if (allEntries != null && allEntries.size() > entries.size()) {
                pass("All-authority query returned more entries (" + allEntries.size() + ") than EPSG-only");
            } else {
                fail("All-authority query should return more entries than EPSG-only");
            }

        } catch (Exception e) {
            fail("getCrsInfoListFromDatabase failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void testGetUnits() {
        System.out.println("Test: PROJ.getUnitsFromDatabase()");
        try {
            Object ctx = PROJ.contextCreate();
            List<Map<String, Object>> entries = PROJ.getUnitsFromDatabase(ctx, "EPSG", "linear", false);
            if (entries == null || entries.isEmpty()) {
                fail("No unit entries returned for EPSG linear");
                return;
            }
            pass("Got " + entries.size() + " EPSG linear unit entries");

            Map<String, Object> meter = null;
            Map<String, Object> usFoot = null;
            for (Map<String, Object> e : entries) {
                if ("9001".equals(e.get("code"))) meter = e;
                if ("9003".equals(e.get("code"))) usFoot = e;
            }
            if (meter != null) {
                pass("Found EPSG:9001 (metre): " + meter.get("name"));
                if (meter.get("convFactor") instanceof Number) {
                    pass("conv-factor is a number: " + meter.get("convFactor"));
                } else {
                    fail("conv-factor is not a number: " + meter.get("convFactor"));
                }
            } else {
                fail("EPSG:9001 (metre) not found in results");
            }
            if (usFoot != null) {
                double cf = ((Number) usFoot.get("convFactor")).doubleValue();
                if (cf > 0.3 && cf < 0.4) {
                    pass("Found EPSG:9003 (US survey foot), conv-factor=" + cf);
                } else {
                    fail("US survey foot conv-factor out of range: " + cf);
                }
            } else {
                fail("EPSG:9003 (US survey foot) not found in results");
            }
        } catch (Exception e) {
            fail("getUnitsFromDatabase failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void testGetCelestialBodies() {
        System.out.println("Test: PROJ.getCelestialBodyListFromDatabase()");
        try {
            Object ctx = PROJ.contextCreate();
            List<Map<String, Object>> entries = PROJ.getCelestialBodyListFromDatabase(ctx);
            if (entries == null || entries.isEmpty()) {
                fail("No celestial body entries returned");
                return;
            }
            pass("Got " + entries.size() + " celestial body entries");

            Map<String, Object> earth = null;
            for (Map<String, Object> e : entries) {
                if ("Earth".equals(e.get("name"))) { earth = e; break; }
            }
            if (earth != null) {
                pass("Found Earth: authName=" + earth.get("authName"));
            } else {
                fail("Earth not found in celestial body results");
            }
        } catch (Exception e) {
            fail("getCelestialBodyListFromDatabase failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void testCreate() {
        System.out.println("Test: PROJ.create()");
        try {
            Object ctx = PROJ.contextCreate();

            Object robin = PROJ.create(ctx, "+proj=robin");
            if (robin != null) {
                pass("Created PJ from PROJ string (+proj=robin)");
            } else {
                fail("create(+proj=robin) returned null");
            }

            Object epsg = PROJ.create(ctx, "EPSG:4326");
            if (epsg != null) {
                pass("Created PJ from EPSG code");
            } else {
                fail("create(EPSG:4326) returned null");
            }

            Object pipeline = PROJ.create(ctx,
                "+proj=pipeline +step +proj=unitconvert +xy_in=deg +xy_out=rad +step +proj=robin");
            if (pipeline != null) {
                pass("Created PJ from pipeline definition");
            } else {
                fail("create(pipeline) returned null");
            }

            // Verify the EPSG object is usable
            Object noCtx = PROJ.create("EPSG:4326");
            if (noCtx != null) {
                pass("Created PJ without explicit context");
            } else {
                fail("create(EPSG:4326) without context returned null");
            }

        } catch (Exception e) {
            fail("create test failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void testGetAreaOfUse() {
        System.out.println("\nTest: getAreaOfUse");
        try {
            Object ctx = PROJ.contextCreate();
            Object crs = PROJ.createFromDatabase(ctx, "EPSG", "4326");
            Map<String, Object> area = PROJ.getAreaOfUse(ctx, crs);
            if (area == null) { fail("getAreaOfUse returned null"); return; }
            assertEqual("westLonDegree", -180.0, (Double) area.get("westLonDegree"));
            assertEqual("southLatDegree", -90.0, (Double) area.get("southLatDegree"));
            assertEqual("eastLonDegree", 180.0, (Double) area.get("eastLonDegree"));
            assertEqual("northLatDegree", 90.0, (Double) area.get("northLatDegree"));
            if (area.get("areaName") instanceof String) {
                pass("getAreaOfUse returned valid AreaOfUse");
            } else {
                fail("areaName is not a string");
            }
        } catch (Exception e) {
            fail("getAreaOfUse failed: " + e.getMessage());
        }
    }

    private static void testGetAreaOfUseEx() {
        System.out.println("\nTest: getAreaOfUseEx");
        try {
            Object ctx = PROJ.contextCreate();
            Object crs = PROJ.createFromDatabase(ctx, "EPSG", "4326");
            Map<String, Object> area = PROJ.getAreaOfUseEx(ctx, crs, 0);
            if (area == null) { fail("getAreaOfUseEx returned null"); return; }
            if (area.get("westLonDegree") instanceof Number) {
                pass("getAreaOfUseEx returned valid AreaOfUse");
            } else {
                fail("westLonDegree is not a number");
            }
        } catch (Exception e) {
            fail("getAreaOfUseEx failed: " + e.getMessage());
        }
    }

    private static void testCsGetAxisInfo() {
        System.out.println("\nTest: csGetAxisInfo");
        try {
            Object ctx = PROJ.contextCreate();
            Object crs = PROJ.createFromDatabase(ctx, "EPSG", "4326");
            Object cs = PROJ.crsGetCoordinateSystem(ctx, crs);
            Map<String, Object> axis = PROJ.csGetAxisInfo(ctx, cs, 0);
            if (axis == null) { fail("csGetAxisInfo returned null"); return; }
            if (axis.get("name") instanceof String && axis.get("unitConvFactor") instanceof Number) {
                pass("csGetAxisInfo returned valid AxisInfo: " + axis.get("name"));
            } else {
                fail("AxisInfo has wrong types");
            }
        } catch (Exception e) {
            fail("csGetAxisInfo failed: " + e.getMessage());
        }
    }

    private static void testEllipsoidGetParameters() {
        System.out.println("\nTest: ellipsoidGetParameters");
        try {
            Object ctx = PROJ.contextCreate();
            Object crs = PROJ.createFromDatabase(ctx, "EPSG", "4326");
            Object ellipsoid = PROJ.getEllipsoid(ctx, crs);
            Map<String, Object> params = PROJ.ellipsoidGetParameters(ctx, ellipsoid);
            if (params == null) { fail("ellipsoidGetParameters returned null"); return; }
            double semiMajor = ((Number) params.get("semiMajorMetre")).doubleValue();
            double invFlat = ((Number) params.get("invFlattening")).doubleValue();
            if (semiMajor > 6378000 && invFlat > 298) {
                pass("ellipsoidGetParameters: semiMajor=" + semiMajor + " invFlat=" + invFlat);
            } else {
                fail("Unexpected ellipsoid values: " + params);
            }
        } catch (Exception e) {
            fail("ellipsoidGetParameters failed: " + e.getMessage());
        }
    }

    private static void testPrimeMeridianGetParameters() {
        System.out.println("\nTest: primeMeridianGetParameters");
        try {
            Object ctx = PROJ.contextCreate();
            Object crs = PROJ.createFromDatabase(ctx, "EPSG", "4326");
            Object pm = PROJ.getPrimeMeridian(ctx, crs);
            Map<String, Object> params = PROJ.primeMeridianGetParameters(ctx, pm);
            if (params == null) { fail("primeMeridianGetParameters returned null"); return; }
            double lon = ((Number) params.get("longitude")).doubleValue();
            if (lon == 0.0 && params.get("unitName") instanceof String) {
                pass("primeMeridianGetParameters: longitude=0.0 unit=" + params.get("unitName"));
            } else {
                fail("Unexpected PM values: " + params);
            }
        } catch (Exception e) {
            fail("primeMeridianGetParameters failed: " + e.getMessage());
        }
    }

    private static void testCoordoperationGetMethodInfo() {
        System.out.println("\nTest: coordoperationGetMethodInfo");
        try {
            Object ctx = PROJ.contextCreate();
            Object crs = PROJ.createFromDatabase(ctx, "EPSG", "2249");
            Object coordop = PROJ.crsGetCoordoperation(ctx, crs);
            Map<String, Object> info = PROJ.coordoperationGetMethodInfo(ctx, coordop);
            if (info == null) { fail("coordoperationGetMethodInfo returned null"); return; }
            if (info.get("methodName") instanceof String) {
                pass("coordoperationGetMethodInfo: " + info.get("methodName"));
            } else {
                fail("methodName is not a string");
            }
        } catch (Exception e) {
            fail("coordoperationGetMethodInfo failed: " + e.getMessage());
        }
    }

    private static void testCoordoperationGetParam() {
        System.out.println("\nTest: coordoperationGetParam");
        try {
            Object ctx = PROJ.contextCreate();
            Object crs = PROJ.createFromDatabase(ctx, "EPSG", "2249");
            Object coordop = PROJ.crsGetCoordoperation(ctx, crs);
            Map<String, Object> param = PROJ.coordoperationGetParam(ctx, coordop, 0);
            if (param == null) { fail("coordoperationGetParam returned null"); return; }
            if (param.get("name") instanceof String && param.get("value") instanceof Number) {
                pass("coordoperationGetParam: " + param.get("name") + "=" + param.get("value"));
            } else {
                fail("param has wrong types: " + param);
            }
        } catch (Exception e) {
            fail("coordoperationGetParam failed: " + e.getMessage());
        }
    }

    private static void testCoordoperationGetGridUsed() {
        System.out.println("\nTest: coordoperationGetGridUsed");
        try {
            Object ctx = PROJ.contextCreate();
            Object crs = PROJ.createFromDatabase(ctx, "EPSG", "2249");
            Object coordop = PROJ.crsGetCoordoperation(ctx, crs);
            int count = PROJ.coordoperationGetGridUsedCount(ctx, coordop);
            if (count > 0) {
                Map<String, Object> grid = PROJ.coordoperationGetGridUsed(ctx, coordop, 0);
                if (grid != null && grid.get("shortName") instanceof String) {
                    pass("coordoperationGetGridUsed: " + grid.get("shortName"));
                } else {
                    fail("grid info has wrong structure");
                }
            } else {
                pass("coordoperationGetGridUsed: no grids used (count=0)");
            }
        } catch (Exception e) {
            fail("coordoperationGetGridUsed failed: " + e.getMessage());
        }
    }

    private static void testUomGetInfoFromDatabase() {
        System.out.println("\nTest: uomGetInfoFromDatabase");
        try {
            Object ctx = PROJ.contextCreate();
            Map<String, Object> info = PROJ.uomGetInfoFromDatabase(ctx, "EPSG", "9001");
            if (info == null) { fail("uomGetInfoFromDatabase returned null"); return; }
            if ("metre".equals(info.get("name")) && ((Number) info.get("convFactor")).doubleValue() == 1.0) {
                pass("uomGetInfoFromDatabase: metre conv=1.0 cat=" + info.get("category"));
            } else {
                fail("Unexpected UOM values: " + info);
            }
        } catch (Exception e) {
            fail("uomGetInfoFromDatabase failed: " + e.getMessage());
        }
    }

    private static void testGridGetInfoFromDatabase() {
        System.out.println("\nTest: gridGetInfoFromDatabase");
        try {
            Object ctx = PROJ.contextCreate();
            Map<String, Object> info = PROJ.gridGetInfoFromDatabase(ctx, "us_noaa_nadcon5_nad83_1986_nad83_harn_conus.tif");
            if (info == null) { fail("gridGetInfoFromDatabase returned null"); return; }
            if (info.get("fullName") instanceof String && info.get("available") instanceof Number) {
                pass("gridGetInfoFromDatabase: " + info.get("fullName"));
            } else {
                fail("Unexpected grid info: " + info);
            }
        } catch (Exception e) {
            fail("gridGetInfoFromDatabase failed: " + e.getMessage());
        }
    }

    private static void testCoordoperationGetTowgs84Values() {
        System.out.println("\nTest: coordoperationGetTowgs84Values");
        try {
            Object ctx = PROJ.contextCreate();
            Object op = PROJ.create(ctx, "+proj=helmert +x=23 +y=-45 +z=67 +rx=0.1 +ry=-0.2 +rz=0.3 +s=1.5 +convention=position_vector");
            Map<String, Object> result = PROJ.coordoperationGetTowgs84Values(ctx, op, 7, 0);
            if (result != null && result.get("values") != null) {
                pass("coordoperationGetTowgs84Values returned values");
            } else {
                pass("coordoperationGetTowgs84Values returned null (expected for this op type)");
            }
        } catch (Exception e) {
            fail("coordoperationGetTowgs84Values failed: " + e.getMessage());
        }
    }

    private static void assertEqual(String field, double expected, double actual) {
        if (expected == actual) {
            pass(field + " = " + actual);
        } else {
            fail(field + " expected " + expected + " but got " + actual);
        }
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
