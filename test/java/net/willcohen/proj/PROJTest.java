package net.willcohen.proj;

import java.util.List;

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

            // The transformed coordinates should be in MA State Plane feet
            // Boston City Hall should be approximately:
            // X: ~774,000 feet, Y: ~2,959,000 feet
            // We can't easily read back the coords without more API, but at least
            // the transformation didn't error

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

    private static void pass(String message) {
        System.out.println("  PASS: " + message);
        testsPassed++;
    }

    private static void fail(String message) {
        System.out.println("  FAIL: " + message);
        testsFailed++;
    }
}
