package net.willcohen.proj;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.Keyword;
import clojure.lang.IPersistentMap;
import clojure.lang.PersistentHashMap;
import clojure.lang.PersistentVector;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * Java API for PROJ coordinate transformation library.
 *
 * This class provides a Java-friendly wrapper around the Clojure clj-proj library,
 * which itself wraps the PROJ C library for coordinate reference system transformations.
 *
 * Example usage:
 * <pre>
 * // Initialize (auto-selects best backend: native FFI or GraalVM WASM)
 * PROJ.init();
 *
 * // Create a context and transformation
 * Object ctx = PROJ.contextCreate();
 * Object transform = PROJ.createCrsToCrs(ctx, "EPSG:4326", "EPSG:2249");
 *
 * // Transform coordinates
 * Object coords = PROJ.coordArray(1);
 * PROJ.setCoords(coords, new double[][]{{42.36, -71.05}});
 * PROJ.transArray(transform, coords, 1);
 * </pre>
 */
public class PROJ {

    private static final String NS = "net.willcohen.proj.proj";
    private static boolean nsLoaded = false;

    // Clojure function references (lazily loaded)
    private static IFn initFn;
    private static IFn forceGraalFn;
    private static IFn forceFfiFn;
    private static IFn toggleGraalFn;
    private static IFn isFfiFn;
    private static IFn isGraalFn;
    private static IFn isNodeFn;
    private static IFn contextCreateFn;
    private static IFn contextPtrFn;
    private static IFn contextDatabasePathFn;
    private static IFn isContextFn;
    private static IFn contextSetDatabasePathFn;
    private static IFn coordArrayFn;
    private static IFn coordToCoordArrayFn;
    private static IFn setCoordsFn;
    private static IFn setCoordFn;
    private static IFn setColFn;
    private static IFn setXcolFn;
    private static IFn setYcolFn;
    private static IFn setZcolFn;
    private static IFn setTcolFn;
    private static IFn errorCodeToStringFn;

    // Generated PROJ functions (most commonly used)
    private static IFn createCrsToCrsFn;
    private static IFn createCrsToCrsFromPjFn;
    private static IFn createFromDatabaseFn;
    private static IFn transArrayFn;
    private static IFn getAuthoritiesFromDatabaseFn;
    private static IFn getCodesFromDatabaseFn;
    private static IFn contextDestroyFn;
    private static IFn destroyFn;

    private static synchronized void ensureLoaded() {
        if (!nsLoaded) {
            IFn require = Clojure.var("clojure.core", "require");
            require.invoke(Clojure.read(NS));
            nsLoaded = true;
        }
    }

    private static IFn getVar(String name) {
        ensureLoaded();
        return Clojure.var(NS, name);
    }

    private static Keyword kw(String name) {
        return Keyword.intern(name);
    }

    private static IPersistentMap map(Object... kvs) {
        return PersistentHashMap.create(kvs);
    }

    // --- Initialization ---

    /**
     * Initialize PROJ library. Auto-selects the best available backend:
     * native FFI if platform libraries are available, otherwise GraalVM WASM.
     */
    public static void init() {
        if (initFn == null) initFn = getVar("init!");
        initFn.invoke();
    }

    /**
     * Force use of GraalVM WASM backend even if native libraries are available.
     */
    public static void forceGraal() {
        if (forceGraalFn == null) forceGraalFn = getVar("force-graal!");
        forceGraalFn.invoke();
    }

    /**
     * Force use of native FFI backend.
     */
    public static void forceFfi() {
        if (forceFfiFn == null) forceFfiFn = getVar("force-ffi!");
        forceFfiFn.invoke();
    }

    /**
     * Toggle between FFI and GraalVM backends.
     */
    public static void toggleGraal() {
        if (toggleGraalFn == null) toggleGraalFn = getVar("toggle-graal!");
        toggleGraalFn.invoke();
    }

    // --- Implementation checks ---

    /**
     * Check if currently using native FFI backend.
     * @return true if using FFI
     */
    public static boolean isFfi() {
        if (isFfiFn == null) isFfiFn = getVar("ffi?");
        return (Boolean) isFfiFn.invoke();
    }

    /**
     * Check if currently using GraalVM WASM backend.
     * @return true if using GraalVM
     */
    public static boolean isGraal() {
        if (isGraalFn == null) isGraalFn = getVar("graal?");
        return (Boolean) isGraalFn.invoke();
    }

    /**
     * Check if currently using Node.js backend (not applicable on JVM).
     * @return true if using Node.js
     */
    public static boolean isNode() {
        if (isNodeFn == null) isNodeFn = getVar("node?");
        return (Boolean) isNodeFn.invoke();
    }

    // --- Context management ---

    /**
     * Create a new PROJ context. Contexts provide thread-safe isolation.
     * @return opaque context object
     */
    public static Object contextCreate() {
        if (contextCreateFn == null) contextCreateFn = getVar("context-create");
        return contextCreateFn.invoke();
    }

    /**
     * Get the native pointer from a context.
     * @param context the context object
     * @return the native pointer
     */
    public static Object contextPtr(Object context) {
        if (contextPtrFn == null) contextPtrFn = getVar("context-ptr");
        return contextPtrFn.invoke(context);
    }

    /**
     * Get the database path from a context.
     * @param context the context object
     * @return the database path
     */
    public static String contextDatabasePath(Object context) {
        if (contextDatabasePathFn == null) contextDatabasePathFn = getVar("context-database-path");
        Object result = contextDatabasePathFn.invoke(context);
        return result != null ? result.toString() : null;
    }

    /**
     * Check if an object is a PROJ context.
     * @param obj the object to check
     * @return true if it's a context
     */
    public static boolean isContext(Object obj) {
        if (isContextFn == null) isContextFn = getVar("is-context?");
        return (Boolean) isContextFn.invoke(obj);
    }

    /**
     * Set the database path for a context.
     * @param context the context object
     */
    public static void contextSetDatabasePath(Object context) {
        if (contextSetDatabasePathFn == null) contextSetDatabasePathFn = getVar("context-set-database-path");
        contextSetDatabasePathFn.invoke(context);
    }

    /**
     * Set the database path for a context.
     * @param context the context object
     * @param dbPath the database path
     */
    public static void contextSetDatabasePath(Object context, String dbPath) {
        if (contextSetDatabasePathFn == null) contextSetDatabasePathFn = getVar("context-set-database-path");
        contextSetDatabasePathFn.invoke(context, dbPath);
    }

    // --- Coordinate arrays ---

    /**
     * Allocate a coordinate array for n coordinates with 4 dimensions (x, y, z, t).
     * @param n number of coordinates
     * @return coordinate array object
     */
    public static Object coordArray(int n) {
        if (coordArrayFn == null) coordArrayFn = getVar("coord-array");
        return coordArrayFn.invoke(n);
    }

    /**
     * Allocate a coordinate array for n coordinates with specified dimensions.
     * @param n number of coordinates
     * @param dims number of dimensions (2, 3, or 4)
     * @return coordinate array object
     */
    public static Object coordArray(int n, int dims) {
        if (coordArrayFn == null) coordArrayFn = getVar("coord-array");
        return coordArrayFn.invoke(n, dims);
    }

    /**
     * Convert a single coordinate to a coordinate array.
     * @param coord the coordinate as double array
     * @return coordinate array object
     */
    public static Object coordToCoordArray(double[] coord) {
        if (coordToCoordArrayFn == null) coordToCoordArrayFn = getVar("coord->coord-array");
        return coordToCoordArrayFn.invoke(PersistentVector.create((Object[]) box(coord)));
    }

    /**
     * Set coordinates in a coordinate array.
     * Coordinates are padded to 4 dimensions (x, y, z, t) with zeros if needed.
     * @param coordArray the coordinate array
     * @param coords array of coordinates, each as [x, y] or [x, y, z] or [x, y, z, t]
     */
    public static void setCoords(Object coordArray, double[][] coords) {
        if (setCoordsFn == null) setCoordsFn = getVar("set-coords!");
        // Convert to Clojure vector of vectors, padding to 4 dimensions
        Object[] outer = new Object[coords.length];
        for (int i = 0; i < coords.length; i++) {
            Double[] padded = new Double[4];
            for (int j = 0; j < 4; j++) {
                padded[j] = (j < coords[i].length) ? coords[i][j] : 0.0;
            }
            outer[i] = PersistentVector.create((Object[]) padded);
        }
        setCoordsFn.invoke(coordArray, PersistentVector.create(outer));
    }

    /**
     * Set a single coordinate in a coordinate array at the given index.
     * Coordinate is padded to 4 dimensions (x, y, z, t) with zeros if needed.
     * @param coordArray the coordinate array
     * @param index the index (0-based)
     * @param coord the coordinate values
     */
    public static void setCoord(Object coordArray, int index, double[] coord) {
        if (setCoordFn == null) setCoordFn = getVar("set-coord!");
        Double[] padded = new Double[4];
        for (int j = 0; j < 4; j++) {
            padded[j] = (j < coord.length) ? coord[j] : 0.0;
        }
        setCoordFn.invoke(coordArray, index, PersistentVector.create((Object[]) padded));
    }

    /**
     * Set values for a specific column in a coordinate array.
     * @param coordArray the coordinate array
     * @param colIndex the column index (0=x, 1=y, 2=z, 3=t)
     * @param values the values for that column
     */
    public static void setCol(Object coordArray, int colIndex, double[] values) {
        if (setColFn == null) setColFn = getVar("set-col!");
        setColFn.invoke(coordArray, colIndex, PersistentVector.create((Object[]) box(values)));
    }

    /**
     * Set X column values in a coordinate array.
     * @param coordArray the coordinate array
     * @param values the X values
     */
    public static void setXcol(Object coordArray, double[] values) {
        if (setXcolFn == null) setXcolFn = getVar("set-xcol!");
        setXcolFn.invoke(coordArray, PersistentVector.create((Object[]) box(values)));
    }

    /**
     * Set Y column values in a coordinate array.
     * @param coordArray the coordinate array
     * @param values the Y values
     */
    public static void setYcol(Object coordArray, double[] values) {
        if (setYcolFn == null) setYcolFn = getVar("set-ycol!");
        setYcolFn.invoke(coordArray, PersistentVector.create((Object[]) box(values)));
    }

    /**
     * Set Z column values in a coordinate array.
     * @param coordArray the coordinate array
     * @param values the Z values
     */
    public static void setZcol(Object coordArray, double[] values) {
        if (setZcolFn == null) setZcolFn = getVar("set-zcol!");
        setZcolFn.invoke(coordArray, PersistentVector.create((Object[]) box(values)));
    }

    /**
     * Set T (time) column values in a coordinate array.
     * @param coordArray the coordinate array
     * @param values the T values
     */
    public static void setTcol(Object coordArray, double[] values) {
        if (setTcolFn == null) setTcolFn = getVar("set-tcol!");
        setTcolFn.invoke(coordArray, PersistentVector.create((Object[]) box(values)));
    }

    // --- Error handling ---

    /**
     * Convert a PROJ error code to a human-readable string.
     * @param errorCode the error code
     * @return description of the error
     */
    public static String errorCodeToString(int errorCode) {
        if (errorCodeToStringFn == null) errorCodeToStringFn = getVar("error-code->string");
        Object result = errorCodeToStringFn.invoke(errorCode);
        return result != null ? result.toString() : null;
    }

    // --- Core PROJ operations (from generated functions) ---

    /**
     * Create a transformation between two coordinate reference systems.
     * @param context the PROJ context
     * @param sourceCrs source CRS (e.g., "EPSG:4326")
     * @param targetCrs target CRS (e.g., "EPSG:2249")
     * @return transformation object
     */
    public static Object createCrsToCrs(Object context, String sourceCrs, String targetCrs) {
        if (createCrsToCrsFn == null) createCrsToCrsFn = getVar("proj-create-crs-to-crs");
        return createCrsToCrsFn.invoke(map(
            kw("context"), context,
            kw("source-crs"), sourceCrs,
            kw("target-crs"), targetCrs
        ));
    }

    /**
     * Create a transformation between two coordinate reference systems using default context.
     * @param sourceCrs source CRS (e.g., "EPSG:4326")
     * @param targetCrs target CRS (e.g., "EPSG:2249")
     * @return transformation object
     */
    public static Object createCrsToCrs(String sourceCrs, String targetCrs) {
        if (createCrsToCrsFn == null) createCrsToCrsFn = getVar("proj-create-crs-to-crs");
        return createCrsToCrsFn.invoke(map(
            kw("source-crs"), sourceCrs,
            kw("target-crs"), targetCrs
        ));
    }

    /**
     * Create a transformation between two CRS objects (PJ pointers).
     * This is the same as createCrsToCrs() except that the source and target CRS
     * are passed as PJ objects rather than string identifiers.
     * @param context the PROJ context
     * @param sourceCrs source CRS object (from createFromDatabase or similar)
     * @param targetCrs target CRS object (from createFromDatabase or similar)
     * @return transformation object
     */
    public static Object createCrsToCrsFromPj(Object context, Object sourceCrs, Object targetCrs) {
        if (createCrsToCrsFromPjFn == null) createCrsToCrsFromPjFn = getVar("proj-create-crs-to-crs-from-pj");
        return createCrsToCrsFromPjFn.invoke(map(
            kw("context"), context,
            kw("source-crs"), sourceCrs,
            kw("target-crs"), targetCrs
        ));
    }

    /**
     * Create a transformation between two CRS objects using default context.
     * @param sourceCrs source CRS object
     * @param targetCrs target CRS object
     * @return transformation object
     */
    public static Object createCrsToCrsFromPj(Object sourceCrs, Object targetCrs) {
        if (createCrsToCrsFromPjFn == null) createCrsToCrsFromPjFn = getVar("proj-create-crs-to-crs-from-pj");
        return createCrsToCrsFromPjFn.invoke(map(
            kw("source-crs"), sourceCrs,
            kw("target-crs"), targetCrs
        ));
    }

    /**
     * Create a CRS object from the database by authority and code.
     * @param context the PROJ context
     * @param authName authority name (e.g., "EPSG")
     * @param code the code (e.g., "4326")
     * @return CRS object (PJ pointer)
     */
    public static Object createFromDatabase(Object context, String authName, String code) {
        if (createFromDatabaseFn == null) createFromDatabaseFn = getVar("proj-create-from-database");
        return createFromDatabaseFn.invoke(map(
            kw("context"), context,
            kw("auth-name"), authName,
            kw("code"), code,
            kw("category"), PJ_CATEGORY_CRS
        ));
    }

    /**
     * Create a CRS object from the database using default context.
     * @param authName authority name (e.g., "EPSG")
     * @param code the code (e.g., "4326")
     * @return CRS object (PJ pointer)
     */
    public static Object createFromDatabase(String authName, String code) {
        if (createFromDatabaseFn == null) createFromDatabaseFn = getVar("proj-create-from-database");
        return createFromDatabaseFn.invoke(map(
            kw("auth-name"), authName,
            kw("code"), code,
            kw("category"), PJ_CATEGORY_CRS
        ));
    }

    /**
     * Transform an array of coordinates.
     * @param transformation the transformation object
     * @param coordArray the coordinate array (modified in place)
     * @param n number of coordinates to transform
     * @return 0 on success, error code on failure
     */
    public static int transArray(Object transformation, Object coordArray, int n) {
        return transArray(transformation, coordArray, n, 1); // PJ_FWD = 1
    }

    /**
     * Transform an array of coordinates with specified direction.
     * @param transformation the transformation object
     * @param coordArray the coordinate array (modified in place)
     * @param n number of coordinates to transform
     * @param direction transformation direction (1=forward, -1=inverse, 0=identity)
     * @return 0 on success, error code on failure
     */
    public static int transArray(Object transformation, Object coordArray, int n, int direction) {
        if (transArrayFn == null) transArrayFn = getVar("proj-trans-array");
        Object result = transArrayFn.invoke(map(
            kw("p"), transformation,
            kw("coord"), coordArray,
            kw("n"), n,
            kw("direction"), direction
        ));
        return result != null ? ((Number) result).intValue() : 0;
    }

    /**
     * Get list of available authorities from the PROJ database.
     * @param context the PROJ context
     * @return list of authority names (e.g., ["EPSG", "ESRI", "PROJ"])
     */
    @SuppressWarnings("unchecked")
    public static List<String> getAuthoritiesFromDatabase(Object context) {
        if (getAuthoritiesFromDatabaseFn == null) getAuthoritiesFromDatabaseFn = getVar("proj-get-authorities-from-database");
        Object result = getAuthoritiesFromDatabaseFn.invoke(map(kw("context"), context));
        return (List<String>) result;
    }

    /**
     * Get list of available authorities from the PROJ database using default context.
     * @return list of authority names
     */
    @SuppressWarnings("unchecked")
    public static List<String> getAuthoritiesFromDatabase() {
        if (getAuthoritiesFromDatabaseFn == null) getAuthoritiesFromDatabaseFn = getVar("proj-get-authorities-from-database");
        Object result = getAuthoritiesFromDatabaseFn.invoke(map());
        return (List<String>) result;
    }

    /**
     * Get list of CRS codes from the PROJ database for an authority.
     * @param context the PROJ context
     * @param authName authority name (e.g., "EPSG")
     * @return list of codes
     */
    @SuppressWarnings("unchecked")
    public static List<String> getCodesFromDatabase(Object context, String authName) {
        if (getCodesFromDatabaseFn == null) getCodesFromDatabaseFn = getVar("proj-get-codes-from-database");
        Object result = getCodesFromDatabaseFn.invoke(map(
            kw("context"), context,
            kw("auth-name"), authName
        ));
        return (List<String>) result;
    }

    /**
     * Get list of CRS codes from the PROJ database for an authority using default context.
     * @param authName authority name (e.g., "EPSG")
     * @return list of codes
     */
    @SuppressWarnings("unchecked")
    public static List<String> getCodesFromDatabase(String authName) {
        if (getCodesFromDatabaseFn == null) getCodesFromDatabaseFn = getVar("proj-get-codes-from-database");
        Object result = getCodesFromDatabaseFn.invoke(map(kw("auth-name"), authName));
        return (List<String>) result;
    }

    // --- Cleanup (usually not needed due to automatic resource tracking) ---

    /**
     * Destroy a PROJ context. Usually not needed as resources are automatically tracked.
     * @param context the context to destroy
     */
    public static void contextDestroy(Object context) {
        if (contextDestroyFn == null) contextDestroyFn = getVar("proj-context-destroy");
        contextDestroyFn.invoke(map(kw("context"), context));
    }

    /**
     * Destroy a PROJ object. Usually not needed as resources are automatically tracked.
     * @param pj the PROJ object to destroy
     */
    public static void destroy(Object pj) {
        if (destroyFn == null) destroyFn = getVar("proj-destroy");
        destroyFn.invoke(map(kw("pj"), pj));
    }

    // --- Direction constants ---

    /** Forward transformation direction */
    public static final int PJ_FWD = 1;
    /** Identity (no-op) transformation direction */
    public static final int PJ_IDENT = 0;
    /** Inverse transformation direction */
    public static final int PJ_INV = -1;

    // --- Category constants ---

    /** Ellipsoid category */
    public static final int PJ_CATEGORY_ELLIPSOID = 0;
    /** Prime meridian category */
    public static final int PJ_CATEGORY_PRIME_MERIDIAN = 1;
    /** Datum category */
    public static final int PJ_CATEGORY_DATUM = 2;
    /** CRS category */
    public static final int PJ_CATEGORY_CRS = 3;
    /** Coordinate operation category */
    public static final int PJ_CATEGORY_COORDINATE_OPERATION = 4;
    /** Datum ensemble category */
    public static final int PJ_CATEGORY_DATUM_ENSEMBLE = 5;

    // --- Helper methods ---

    private static Double[] box(double[] arr) {
        Double[] result = new Double[arr.length];
        for (int i = 0; i < arr.length; i++) {
            result[i] = arr[i];
        }
        return result;
    }
}
