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
                if ("EPSG".equals(wgs84.get("auth-name"))) {
                    pass("auth-name is EPSG");
                } else {
                    fail("auth-name should be EPSG, got " + wgs84.get("auth-name"));
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
                if (meter.get("conv-factor") instanceof Number) {
                    pass("conv-factor is a number: " + meter.get("conv-factor"));
                } else {
                    fail("conv-factor is not a number: " + meter.get("conv-factor"));
                }
            } else {
                fail("EPSG:9001 (metre) not found in results");
            }
            if (usFoot != null) {
                double cf = ((Number) usFoot.get("conv-factor")).doubleValue();
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
                pass("Found Earth: auth-name=" + earth.get("auth-name"));
            } else {
                fail("Earth not found in celestial body results");
            }
        } catch (Exception e) {
            fail("getCelestialBodyListFromDatabase failed: " + e.getMessage());
            e.printStackTrace();
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
