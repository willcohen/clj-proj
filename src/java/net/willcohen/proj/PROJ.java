package net.willcohen.proj;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.Keyword;
import clojure.lang.IPersistentMap;
import clojure.lang.PersistentHashMap;
import clojure.lang.PersistentVector;

import java.util.ArrayList;
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
    private static IFn getCoordsFn;
    private static IFn errorCodeToStringFn;

    // Generated PROJ functions (most commonly used)
    private static IFn createFn;
    private static IFn createCrsToCrsFn;
    private static IFn createCrsToCrsFromPjFn;
    private static IFn createFromDatabaseFn;
    private static IFn transArrayFn;
    private static IFn getAuthoritiesFromDatabaseFn;
    private static IFn getCodesFromDatabaseFn;
    private static IFn getCrsInfoListFromDatabaseFn;
    private static IFn getUnitsFromDatabaseFn;
    private static IFn getCelestialBodyListFromDatabaseFn;
    private static IFn contextDestroyFn;
    private static IFn destroyFn;
    private static IFn getNameFn;
    private static IFn getEllipsoidFn;
    private static IFn getPrimeMeridianFn;
    private static IFn crsGetCoordinateSystemFn;
    private static IFn csGetAxisCountFn;
    private static IFn crsGetCoordoperationFn;
    private static IFn getAreaOfUseFn;
    private static IFn getAreaOfUseExFn;
    private static IFn csGetAxisInfoFn;
    private static IFn ellipsoidGetParametersFn;
    private static IFn primeMeridianGetParametersFn;
    private static IFn coordoperationGetMethodInfoFn;
    private static IFn coordoperationGetParamFn;
    private static IFn coordoperationGetParamCountFn;
    private static IFn coordoperationGetGridUsedCountFn;
    private static IFn coordoperationGetGridUsedFn;
    private static IFn uomGetInfoFromDatabaseFn;
    private static IFn gridGetInfoFromDatabaseFn;
    private static IFn coordoperationGetTowgs84ValuesFn;

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

    /**
     * Get coordinates from a coordinate array at the given index.
     * Returns [x, y, z, t] for the coordinate at the specified index.
     * @param coordArray the coordinate array
     * @param index the index (0-based)
     * @return array of [x, y, z, t] doubles
     */
    public static double[] getCoords(Object coordArray, int index) {
        if (getCoordsFn == null) getCoordsFn = getVar("get-coords");
        Object result = getCoordsFn.invoke(coordArray, index);
        if (result instanceof clojure.lang.IPersistentVector) {
            clojure.lang.IPersistentVector vec = (clojure.lang.IPersistentVector) result;
            double[] coords = new double[4];
            for (int i = 0; i < 4 && i < vec.count(); i++) {
                Object val = vec.nth(i);
                if (val instanceof Number) {
                    coords[i] = ((Number) val).doubleValue();
                }
            }
            return coords;
        }
        return null;
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
     * Create a PROJ object from a definition string (PROJ string, WKT, or pipeline).
     * @param context the PROJ context
     * @param definition the definition string (e.g., "+proj=robin", "EPSG:4326", pipeline)
     * @return PJ object
     */
    public static Object create(Object context, String definition) {
        if (createFn == null) createFn = getVar("proj-create");
        return createFn.invoke(map(
            kw("context"), context,
            kw("definition"), definition
        ));
    }

    /**
     * Create a PROJ object from a definition string using default context.
     * @param definition the definition string
     * @return PJ object
     */
    public static Object create(String definition) {
        if (createFn == null) createFn = getVar("proj-create");
        return createFn.invoke(map(
            kw("definition"), definition
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

    /**
     * Get the full CRS catalog from PROJ's database as a list of maps.
     * Each map has keys: auth-name, code, name, type, deprecated, bbox-valid,
     * west-lon-degree, south-lat-degree, east-lon-degree, north-lat-degree,
     * area-name, projection-method-name, celestial-body-name.
     * @param context the PROJ context
     * @param authName authority name filter (e.g., "EPSG"), or null for all authorities
     * @return list of CRS info maps with String keys
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> getCrsInfoListFromDatabase(Object context, String authName) {
        if (getCrsInfoListFromDatabaseFn == null) getCrsInfoListFromDatabaseFn = getVar("proj-get-crs-info-list-from-database");
        IPersistentMap opts = authName != null
            ? map(kw("context"), context, kw("auth-name"), authName)
            : map(kw("context"), context);
        Object result = getCrsInfoListFromDatabaseFn.invoke(opts);
        return convertKeywordMaps((List<Map<Keyword, Object>>) result);
    }

    /**
     * Get the full CRS catalog from PROJ's database for all authorities.
     * @param context the PROJ context
     * @return list of CRS info maps with String keys
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> getCrsInfoListFromDatabase(Object context) {
        return getCrsInfoListFromDatabase(context, null);
    }

    /**
     * Get unit information from PROJ's database.
     * Each map has keys: auth-name, code, name, category, conv-factor, proj-short-name, deprecated.
     * @param context the PROJ context (optional, pass null to auto-create)
     * @param authName authority name filter (e.g., "EPSG"), or null for all
     * @param category unit category filter (e.g., "linear", "angular"), or null for all
     * @param allowDeprecated whether to include deprecated units
     * @return list of unit info maps with String keys
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> getUnitsFromDatabase(Object context, String authName, String category, boolean allowDeprecated) {
        if (getUnitsFromDatabaseFn == null) getUnitsFromDatabaseFn = getVar("proj-get-units-from-database");
        IPersistentMap opts = map(kw("context"), context,
                                  kw("auth-name"), authName != null ? authName : "",
                                  kw("category"), category != null ? category : "",
                                  kw("allow-deprecated"), allowDeprecated ? 1 : 0);
        Object result = getUnitsFromDatabaseFn.invoke(opts);
        return convertKeywordMaps((List<Map<Keyword, Object>>) result);
    }

    /**
     * Get unit information from PROJ's database for all authorities and categories.
     * @param context the PROJ context
     * @return list of unit info maps with String keys
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> getUnitsFromDatabase(Object context) {
        return getUnitsFromDatabase(context, null, null, false);
    }

    /**
     * Get celestial body information from PROJ's database.
     * Each map has keys: auth-name, name.
     * @param context the PROJ context (optional, pass null to auto-create)
     * @param authName authority name filter, or null for all
     * @return list of celestial body info maps with String keys
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> getCelestialBodyListFromDatabase(Object context, String authName) {
        if (getCelestialBodyListFromDatabaseFn == null) getCelestialBodyListFromDatabaseFn = getVar("proj-get-celestial-body-list-from-database");
        IPersistentMap opts = map(kw("context"), context,
                                  kw("auth-name"), authName != null ? authName : "");
        Object result = getCelestialBodyListFromDatabaseFn.invoke(opts);
        return convertKeywordMaps((List<Map<Keyword, Object>>) result);
    }

    /**
     * Get celestial body information from PROJ's database for all authorities.
     * @param context the PROJ context
     * @return list of celestial body info maps with String keys
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> getCelestialBodyListFromDatabase(Object context) {
        return getCelestialBodyListFromDatabase(context, null);
    }

    // --- CRS Decomposition ---

    public static String getName(Object obj) {
        if (getNameFn == null) getNameFn = getVar("proj-get-name");
        return (String) getNameFn.invoke(map(kw("obj"), obj));
    }

    public static Object getEllipsoid(Object context, Object obj) {
        if (getEllipsoidFn == null) getEllipsoidFn = getVar("proj-get-ellipsoid");
        return getEllipsoidFn.invoke(map(kw("ctx"), context, kw("obj"), obj));
    }

    public static Object getPrimeMeridian(Object context, Object obj) {
        if (getPrimeMeridianFn == null) getPrimeMeridianFn = getVar("proj-get-prime-meridian");
        return getPrimeMeridianFn.invoke(map(kw("ctx"), context, kw("obj"), obj));
    }

    public static Object crsGetCoordinateSystem(Object context, Object crs) {
        if (crsGetCoordinateSystemFn == null) crsGetCoordinateSystemFn = getVar("proj-crs-get-coordinate-system");
        return crsGetCoordinateSystemFn.invoke(map(kw("ctx"), context, kw("crs"), crs));
    }

    public static int csGetAxisCount(Object context, Object cs) {
        if (csGetAxisCountFn == null) csGetAxisCountFn = getVar("proj-cs-get-axis-count");
        Object result = csGetAxisCountFn.invoke(map(kw("ctx"), context, kw("cs"), cs));
        return ((Number) result).intValue();
    }

    public static Object crsGetCoordoperation(Object context, Object crs) {
        if (crsGetCoordoperationFn == null) crsGetCoordoperationFn = getVar("proj-crs-get-coordoperation");
        return crsGetCoordoperationFn.invoke(map(kw("ctx"), context, kw("crs"), crs));
    }

    public static int coordoperationGetParamCount(Object context, Object coordoperation) {
        if (coordoperationGetParamCountFn == null) coordoperationGetParamCountFn = getVar("proj-coordoperation-get-param-count");
        Object result = coordoperationGetParamCountFn.invoke(map(kw("ctx"), context, kw("coordoperation"), coordoperation));
        return ((Number) result).intValue();
    }

    public static int coordoperationGetGridUsedCount(Object context, Object coordoperation) {
        if (coordoperationGetGridUsedCountFn == null) coordoperationGetGridUsedCountFn = getVar("proj-coordoperation-get-grid-used-count");
        Object result = coordoperationGetGridUsedCountFn.invoke(map(kw("ctx"), context, kw("coordoperation"), coordoperation));
        return ((Number) result).intValue();
    }

    // --- Out-param functions (return Maps with String keys) ---

    @SuppressWarnings("unchecked")
    public static Map<String, Object> getAreaOfUse(Object context, Object obj) {
        if (getAreaOfUseFn == null) getAreaOfUseFn = getVar("proj-get-area-of-use");
        Object result = getAreaOfUseFn.invoke(map(kw("context"), context, kw("obj"), obj));
        return result != null ? convertKeywordMap((Map<Keyword, Object>) result) : null;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> getAreaOfUseEx(Object context, Object obj, int domainIdx) {
        if (getAreaOfUseExFn == null) getAreaOfUseExFn = getVar("proj-get-area-of-use-ex");
        Object result = getAreaOfUseExFn.invoke(map(kw("context"), context, kw("obj"), obj, kw("domainIdx"), domainIdx));
        return result != null ? convertKeywordMap((Map<Keyword, Object>) result) : null;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> csGetAxisInfo(Object context, Object cs, int index) {
        if (csGetAxisInfoFn == null) csGetAxisInfoFn = getVar("proj-cs-get-axis-info");
        Object result = csGetAxisInfoFn.invoke(map(kw("ctx"), context, kw("cs"), cs, kw("index"), index));
        return result != null ? convertKeywordMap((Map<Keyword, Object>) result) : null;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> ellipsoidGetParameters(Object context, Object ellipsoid) {
        if (ellipsoidGetParametersFn == null) ellipsoidGetParametersFn = getVar("proj-ellipsoid-get-parameters");
        Object result = ellipsoidGetParametersFn.invoke(map(kw("ctx"), context, kw("ellipsoid"), ellipsoid));
        return result != null ? convertKeywordMap((Map<Keyword, Object>) result) : null;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> primeMeridianGetParameters(Object context, Object primeMeridian) {
        if (primeMeridianGetParametersFn == null) primeMeridianGetParametersFn = getVar("proj-prime-meridian-get-parameters");
        Object result = primeMeridianGetParametersFn.invoke(map(kw("ctx"), context, kw("prime-meridian"), primeMeridian));
        return result != null ? convertKeywordMap((Map<Keyword, Object>) result) : null;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> coordoperationGetMethodInfo(Object context, Object coordoperation) {
        if (coordoperationGetMethodInfoFn == null) coordoperationGetMethodInfoFn = getVar("proj-coordoperation-get-method-info");
        Object result = coordoperationGetMethodInfoFn.invoke(map(kw("ctx"), context, kw("coordoperation"), coordoperation));
        return result != null ? convertKeywordMap((Map<Keyword, Object>) result) : null;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> coordoperationGetParam(Object context, Object coordoperation, int index) {
        if (coordoperationGetParamFn == null) coordoperationGetParamFn = getVar("proj-coordoperation-get-param");
        Object result = coordoperationGetParamFn.invoke(map(kw("ctx"), context, kw("coordoperation"), coordoperation, kw("index"), index));
        return result != null ? convertKeywordMap((Map<Keyword, Object>) result) : null;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> coordoperationGetGridUsed(Object context, Object coordoperation, int index) {
        if (coordoperationGetGridUsedFn == null) coordoperationGetGridUsedFn = getVar("proj-coordoperation-get-grid-used");
        Object result = coordoperationGetGridUsedFn.invoke(map(kw("ctx"), context, kw("coordoperation"), coordoperation, kw("index"), index));
        return result != null ? convertKeywordMap((Map<Keyword, Object>) result) : null;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> uomGetInfoFromDatabase(Object context, String authName, String code) {
        if (uomGetInfoFromDatabaseFn == null) uomGetInfoFromDatabaseFn = getVar("proj-uom-get-info-from-database");
        Object result = uomGetInfoFromDatabaseFn.invoke(map(kw("context"), context, kw("auth-name"), authName, kw("code"), code));
        return result != null ? convertKeywordMap((Map<Keyword, Object>) result) : null;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> gridGetInfoFromDatabase(Object context, String gridName) {
        if (gridGetInfoFromDatabaseFn == null) gridGetInfoFromDatabaseFn = getVar("proj-grid-get-info-from-database");
        Object result = gridGetInfoFromDatabaseFn.invoke(map(kw("context"), context, kw("grid-name"), gridName));
        return result != null ? convertKeywordMap((Map<Keyword, Object>) result) : null;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> coordoperationGetTowgs84Values(Object context, Object coordoperation, int valueCount, int emitErrorIfIncompatible) {
        if (coordoperationGetTowgs84ValuesFn == null) coordoperationGetTowgs84ValuesFn = getVar("proj-coordoperation-get-towgs84-values");
        Object result = coordoperationGetTowgs84ValuesFn.invoke(map(kw("ctx"), context, kw("coordoperation"), coordoperation,
            kw("value-count"), valueCount, kw("emit-error-if-incompatible"), emitErrorIfIncompatible));
        return result != null ? convertKeywordMap((Map<Keyword, Object>) result) : null;
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

    private static String kebabToCamelCase(String kebab) {
        String[] parts = kebab.split("-");
        if (parts.length == 1) return kebab;
        StringBuilder sb = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].length() > 0) {
                sb.append(Character.toUpperCase(parts[i].charAt(0)));
                sb.append(parts[i].substring(1));
            }
        }
        return sb.toString();
    }

    private static Map<String, Object> convertKeywordMap(Map<Keyword, Object> cljMap) {
        Map<String, Object> javaMap = new HashMap<>();
        for (Map.Entry<Keyword, Object> e : cljMap.entrySet()) {
            javaMap.put(kebabToCamelCase(e.getKey().getName()), e.getValue());
        }
        return javaMap;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> convertKeywordMaps(List<Map<Keyword, Object>> cljList) {
        List<Map<String, Object>> result = new ArrayList<>(cljList.size());
        for (Map<Keyword, Object> entry : cljList) {
            Map<String, Object> javaMap = new HashMap<>();
            for (Map.Entry<Keyword, Object> e : entry.entrySet()) {
                javaMap.put(kebabToCamelCase(e.getKey().getName()), e.getValue());
            }
            result.add(javaMap);
        }
        return result;
    }
}
