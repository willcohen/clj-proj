// Copyright (c) 2024, 2025, 2026 Will Cohen
//
// Part of clj-proj, under the MIT License.
// See LICENSE for license information.
// SPDX-License-Identifier: MIT

package net.willcohen.proj;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.Keyword;
import clojure.lang.IPersistentMap;
import clojure.lang.PersistentHashMap;
import clojure.lang.PersistentVector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Java API for the PROJ coordinate transformation library.
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
    private static final Map<String, IFn> FNS = new ConcurrentHashMap<>();

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

    private static IFn fn(String name) {
        return FNS.computeIfAbsent(name, PROJ::getVar);
    }

    private static IPersistentMap map(Object... kvs) {
        for (int i = 0; i < kvs.length; i += 2) kvs[i] = Keyword.intern((String) kvs[i]);
        return PersistentHashMap.create(kvs);
    }

    /**
     * Initialize the PROJ library. Selects the best available backend:
     * native FFI when platform libraries are available, otherwise GraalVM WASM.
     */
    public static void init() {
        fn("init!").invoke();
    }

    /**
     * Force the GraalVM WASM backend, even when native libraries are available.
     */
    public static void forceGraal() {
        fn("force-graal!").invoke();
    }

    /**
     * Force the native FFI backend.
     */
    public static void forceFfi() {
        fn("force-ffi!").invoke();
    }

    /**
     * Toggle between FFI and GraalVM backends.
     */
    public static void toggleGraal() {
        fn("toggle-graal!").invoke();
    }

    /**
     * Returns true when the native FFI backend is active.
     * @return true when FFI is active
     */
    public static boolean isFfi() {
        return (Boolean) fn("ffi?").invoke();
    }

    /**
     * Returns true when the GraalVM WASM backend is active.
     * @return true when GraalVM is active
     */
    public static boolean isGraal() {
        return (Boolean) fn("graal?").invoke();
    }

    /**
     * Returns true when the Node.js backend is active. Not applicable on the JVM.
     * @return true when Node.js is active
     */
    public static boolean isNode() {
        return (Boolean) fn("node?").invoke();
    }

    /**
     * Create a new PROJ context. Contexts give thread-safe isolation.
     * @return opaque context object
     */
    public static Object contextCreate() {
        return fn("context-create").invoke();
    }

    /**
     * Get the native pointer from a context.
     * @param context the context object
     * @return the native pointer
     */
    public static Object contextPtr(Object context) {
        return fn("context-ptr").invoke(context);
    }

    /**
     * Get the database path from a context.
     * @param context the context object
     * @return the database path
     */
    public static String contextDatabasePath(Object context) {
        Object result = fn("context-database-path").invoke(context);
        return result != null ? result.toString() : null;
    }

    /**
     * Returns true when the object is a PROJ context.
     * @param obj the object to examine
     * @return true when the object is a context
     */
    public static boolean isContext(Object obj) {
        return (Boolean) fn("is-context?").invoke(obj);
    }

    /**
     * Set the database path for a context.
     * @param context the context object
     */
    public static void contextSetDatabasePath(Object context) {
        fn("context-set-database-path").invoke(context);
    }

    /**
     * Set the database path for a context.
     * @param context the context object
     * @param dbPath the database path
     */
    public static void contextSetDatabasePath(Object context, String dbPath) {
        fn("context-set-database-path").invoke(context, dbPath);
    }

    /**
     * Allocate a coordinate array for n coordinates with 4 dimensions (x, y, z, t).
     * @param n number of coordinates
     * @return coordinate array object
     */
    public static Object coordArray(int n) {
        return fn("coord-array").invoke(n);
    }

    /**
     * Allocate a coordinate array for n coordinates with specified dimensions.
     * @param n number of coordinates
     * @param dims number of dimensions (2, 3, or 4)
     * @return coordinate array object
     */
    public static Object coordArray(int n, int dims) {
        return fn("coord-array").invoke(n, dims);
    }

    /**
     * Convert a single coordinate to a coordinate array.
     * @param coord the coordinate as double array
     * @return coordinate array object
     */
    public static Object coordToCoordArray(double[] coord) {
        return fn("coord->coord-array").invoke(vec(coord, coord.length));
    }

    /**
     * Set coordinates in a coordinate array.
     * The method pads each coordinate to 4 dimensions (x, y, z, t) with zeros.
     * @param coordArray the coordinate array
     * @param coords array of coordinates, each as [x, y] or [x, y, z] or [x, y, z, t]
     */
    public static void setCoords(Object coordArray, double[][] coords) {
        Object[] outer = new Object[coords.length];
        for (int i = 0; i < coords.length; i++) outer[i] = vec(coords[i], 4);
        fn("set-coords!").invoke(coordArray, PersistentVector.create(outer));
    }

    /**
     * Set a single coordinate in a coordinate array at the given index.
     * The method pads the coordinate to 4 dimensions (x, y, z, t) with zeros.
     * @param coordArray the coordinate array
     * @param index the index (0-based)
     * @param coord the coordinate values
     */
    public static void setCoord(Object coordArray, int index, double[] coord) {
        fn("set-coord!").invoke(coordArray, index, vec(coord, 4));
    }

    /**
     * Set values for a specific column in a coordinate array.
     * @param coordArray the coordinate array
     * @param colIndex the column index (0=x, 1=y, 2=z, 3=t)
     * @param values the values for that column
     */
    public static void setCol(Object coordArray, int colIndex, double[] values) {
        fn("set-col!").invoke(coordArray, colIndex, vec(values, values.length));
    }

    /**
     * Set X column values in a coordinate array.
     * @param coordArray the coordinate array
     * @param values the X values
     */
    public static void setXcol(Object coordArray, double[] values) {
        fn("set-xcol!").invoke(coordArray, vec(values, values.length));
    }

    /**
     * Set Y column values in a coordinate array.
     * @param coordArray the coordinate array
     * @param values the Y values
     */
    public static void setYcol(Object coordArray, double[] values) {
        fn("set-ycol!").invoke(coordArray, vec(values, values.length));
    }

    /**
     * Set Z column values in a coordinate array.
     * @param coordArray the coordinate array
     * @param values the Z values
     */
    public static void setZcol(Object coordArray, double[] values) {
        fn("set-zcol!").invoke(coordArray, vec(values, values.length));
    }

    /**
     * Set T (time) column values in a coordinate array.
     * @param coordArray the coordinate array
     * @param values the T values
     */
    public static void setTcol(Object coordArray, double[] values) {
        fn("set-tcol!").invoke(coordArray, vec(values, values.length));
    }

    /**
     * Get the [x, y, z, t] values from a coordinate array at the given index.
     * @param coordArray the coordinate array
     * @param index the index (0-based)
     * @return array of [x, y, z, t] doubles
     */
    public static double[] getCoords(Object coordArray, int index) {
        Object result = fn("get-coords").invoke(coordArray, index);
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

    /**
     * Convert a PROJ error code to a human-readable string.
     * @param errorCode the error code
     * @return description of the error
     */
    public static String errorCodeToString(int errorCode) {
        Object result = fn("error-code->string").invoke(errorCode);
        return result != null ? result.toString() : null;
    }

    /**
     * Create a transformation between two coordinate reference systems.
     * @param context the PROJ context
     * @param sourceCrs source CRS (for example "EPSG:4326")
     * @param targetCrs target CRS (for example "EPSG:2249")
     * @return transformation object
     */
    public static Object createCrsToCrs(Object context, String sourceCrs, String targetCrs) {
        return fn("proj-create-crs-to-crs").invoke(map("context", context, "source-crs", sourceCrs, "target-crs", targetCrs));
    }

    /**
     * Create a transformation between two coordinate reference systems with the default context.
     * @param sourceCrs source CRS (for example "EPSG:4326")
     * @param targetCrs target CRS (for example "EPSG:2249")
     * @return transformation object
     */
    public static Object createCrsToCrs(String sourceCrs, String targetCrs) {
        return fn("proj-create-crs-to-crs").invoke(map("source-crs", sourceCrs, "target-crs", targetCrs));
    }

    /**
     * Create a transformation between two CRS objects (PJ pointers).
     * @param context the PROJ context
     * @param sourceCrs source CRS object (from createFromDatabase or similar)
     * @param targetCrs target CRS object (from createFromDatabase or similar)
     * @return transformation object
     */
    public static Object createCrsToCrsFromPj(Object context, Object sourceCrs, Object targetCrs) {
        return fn("proj-create-crs-to-crs-from-pj").invoke(map("context", context, "source-crs", sourceCrs, "target-crs", targetCrs));
    }

    /**
     * Create a transformation between two CRS objects with the default context.
     * @param sourceCrs source CRS object
     * @param targetCrs target CRS object
     * @return transformation object
     */
    public static Object createCrsToCrsFromPj(Object sourceCrs, Object targetCrs) {
        return fn("proj-create-crs-to-crs-from-pj").invoke(map("source-crs", sourceCrs, "target-crs", targetCrs));
    }

    /**
     * Create a PROJ object from a definition string (PROJ string, WKT, or pipeline).
     * @param context the PROJ context
     * @param definition the definition string (for example "+proj=robin", "EPSG:4326", or a pipeline)
     * @return PJ object
     */
    public static Object create(Object context, String definition) {
        return fn("proj-create").invoke(map("context", context, "definition", definition));
    }

    /**
     * Create a PROJ object from a definition string with the default context.
     * @param definition the definition string
     * @return PJ object
     */
    public static Object create(String definition) {
        return fn("proj-create").invoke(map("definition", definition));
    }

    /**
     * Create a CRS object from the database by authority and code.
     * @param context the PROJ context
     * @param authName authority name (for example "EPSG")
     * @param code the code (for example "4326")
     * @return CRS object (PJ pointer)
     */
    public static Object createFromDatabase(Object context, String authName, String code) {
        return fn("proj-create-from-database").invoke(map("context", context, "auth-name", authName, "code", code, "category", PJ_CATEGORY_CRS));
    }

    /**
     * Create a CRS object from the database with the default context.
     * @param authName authority name (for example "EPSG")
     * @param code the code (for example "4326")
     * @return CRS object (PJ pointer)
     */
    public static Object createFromDatabase(String authName, String code) {
        return fn("proj-create-from-database").invoke(map("auth-name", authName, "code", code, "category", PJ_CATEGORY_CRS));
    }

    /**
     * Transform an array of coordinates.
     * @param transformation the transformation object
     * @param coordArray the coordinate array (modified in place)
     * @param n number of coordinates to transform
     * @return 0 on success, error code on failure
     */
    public static int transArray(Object transformation, Object coordArray, int n) {
        return transArray(transformation, coordArray, n, PJ_FWD);
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
        Object result = fn("proj-trans-array").invoke(map("p", transformation, "coord", coordArray, "n", n, "direction", direction));
        return result != null ? ((Number) result).intValue() : 0;
    }

    /**
     * Get list of available authorities from the PROJ database.
     * @param context the PROJ context
     * @return list of authority names (for example ["EPSG", "ESRI", "PROJ"])
     */
    @SuppressWarnings("unchecked")
    public static List<String> getAuthoritiesFromDatabase(Object context) {
        return (List<String>) fn("proj-get-authorities-from-database").invoke(map("context", context));
    }

    /**
     * Get list of available authorities from the PROJ database with the default context.
     * @return list of authority names
     */
    @SuppressWarnings("unchecked")
    public static List<String> getAuthoritiesFromDatabase() {
        return (List<String>) fn("proj-get-authorities-from-database").invoke(map());
    }

    /**
     * Get list of CRS codes from the PROJ database for an authority.
     * @param context the PROJ context
     * @param authName authority name (for example "EPSG")
     * @return list of codes
     */
    @SuppressWarnings("unchecked")
    public static List<String> getCodesFromDatabase(Object context, String authName) {
        return (List<String>) fn("proj-get-codes-from-database").invoke(map("context", context, "auth-name", authName));
    }

    /**
     * Get list of CRS codes from the PROJ database for an authority with the default context.
     * @param authName authority name (for example "EPSG")
     * @return list of codes
     */
    @SuppressWarnings("unchecked")
    public static List<String> getCodesFromDatabase(String authName) {
        return (List<String>) fn("proj-get-codes-from-database").invoke(map("auth-name", authName));
    }

    /**
     * Get the full CRS catalog from PROJ's database as a list of maps.
     * Each map has keys: authName, code, name, type, deprecated, bboxValid,
     * westLonDegree, southLatDegree, eastLonDegree, northLatDegree,
     * areaName, projectionMethodName, celestialBodyName.
     * @param context the PROJ context
     * @param authName authority name filter (for example "EPSG"), or null for all authorities
     * @return list of CRS info maps with String keys
     */
    public static List<Map<String, Object>> getCrsInfoListFromDatabase(Object context, String authName) {
        IPersistentMap opts = authName != null ? map("context", context, "auth-name", authName) : map("context", context);
        return convertKeywordMaps(fn("proj-get-crs-info-list-from-database").invoke(opts));
    }

    /**
     * Get the full CRS catalog from PROJ's database for all authorities.
     * @param context the PROJ context
     * @return list of CRS info maps with String keys
     */
    public static List<Map<String, Object>> getCrsInfoListFromDatabase(Object context) {
        return getCrsInfoListFromDatabase(context, null);
    }

    /**
     * Get unit information from PROJ's database.
     * Each map has keys: authName, code, name, category, convFactor, projShortName, deprecated.
     * @param context the PROJ context (optional, pass null to auto-create)
     * @param authName authority name filter (for example "EPSG"), or null for all
     * @param category unit category filter (for example "linear" or "angular"), or null for all
     * @param allowDeprecated whether to include deprecated units
     * @return list of unit info maps with String keys
     */
    public static List<Map<String, Object>> getUnitsFromDatabase(Object context, String authName, String category, boolean allowDeprecated) {
        return convertKeywordMaps(fn("proj-get-units-from-database").invoke(map(
            "context", context, "auth-name", authName != null ? authName : "",
            "category", category, "allow-deprecated", allowDeprecated ? 1 : 0)));
    }

    /**
     * Get unit information from PROJ's database for all authorities and categories.
     * @param context the PROJ context
     * @return list of unit info maps with String keys
     */
    public static List<Map<String, Object>> getUnitsFromDatabase(Object context) {
        return getUnitsFromDatabase(context, null, null, false);
    }

    /**
     * Get celestial body information from PROJ's database.
     * Each map has keys: authName, name.
     * @param context the PROJ context (optional, pass null to auto-create)
     * @param authName authority name filter, or null for all
     * @return list of celestial body info maps with String keys
     */
    public static List<Map<String, Object>> getCelestialBodyListFromDatabase(Object context, String authName) {
        return convertKeywordMaps(fn("proj-get-celestial-body-list-from-database").invoke(
            map("context", context, "auth-name", authName != null ? authName : "")));
    }

    /**
     * Get celestial body information from PROJ's database for all authorities.
     * @param context the PROJ context
     * @return list of celestial body info maps with String keys
     */
    public static List<Map<String, Object>> getCelestialBodyListFromDatabase(Object context) {
        return getCelestialBodyListFromDatabase(context, null);
    }

    public static String getName(Object obj) {
        return (String) fn("proj-get-name").invoke(map("obj", obj));
    }

    public static Object getEllipsoid(Object context, Object obj) {
        return fn("proj-get-ellipsoid").invoke(map("ctx", context, "obj", obj));
    }

    public static Object getPrimeMeridian(Object context, Object obj) {
        return fn("proj-get-prime-meridian").invoke(map("ctx", context, "obj", obj));
    }

    public static Object crsGetCoordinateSystem(Object context, Object crs) {
        return fn("proj-crs-get-coordinate-system").invoke(map("ctx", context, "crs", crs));
    }

    public static int csGetAxisCount(Object context, Object cs) {
        return ((Number) fn("proj-cs-get-axis-count").invoke(map("ctx", context, "cs", cs))).intValue();
    }

    public static Object crsGetCoordoperation(Object context, Object crs) {
        return fn("proj-crs-get-coordoperation").invoke(map("ctx", context, "crs", crs));
    }

    public static int coordoperationGetParamCount(Object context, Object coordoperation) {
        return ((Number) fn("proj-coordoperation-get-param-count").invoke(map("ctx", context, "coordoperation", coordoperation))).intValue();
    }

    public static int coordoperationGetGridUsedCount(Object context, Object coordoperation) {
        return ((Number) fn("proj-coordoperation-get-grid-used-count").invoke(map("ctx", context, "coordoperation", coordoperation))).intValue();
    }

    public static Map<String, Object> getAreaOfUse(Object context, Object obj) {
        return convertKeywordMap(fn("proj-get-area-of-use").invoke(map("context", context, "obj", obj)));
    }

    public static Map<String, Object> getAreaOfUseEx(Object context, Object obj, int domainIdx) {
        return convertKeywordMap(fn("proj-get-area-of-use-ex").invoke(map("context", context, "obj", obj, "domainIdx", domainIdx)));
    }

    public static Map<String, Object> csGetAxisInfo(Object context, Object cs, int index) {
        return convertKeywordMap(fn("proj-cs-get-axis-info").invoke(map("ctx", context, "cs", cs, "index", index)));
    }

    public static Map<String, Object> ellipsoidGetParameters(Object context, Object ellipsoid) {
        return convertKeywordMap(fn("proj-ellipsoid-get-parameters").invoke(map("ctx", context, "ellipsoid", ellipsoid)));
    }

    public static Map<String, Object> primeMeridianGetParameters(Object context, Object primeMeridian) {
        return convertKeywordMap(fn("proj-prime-meridian-get-parameters").invoke(map("ctx", context, "prime-meridian", primeMeridian)));
    }

    public static Map<String, Object> coordoperationGetMethodInfo(Object context, Object coordoperation) {
        return convertKeywordMap(fn("proj-coordoperation-get-method-info").invoke(map("ctx", context, "coordoperation", coordoperation)));
    }

    public static Map<String, Object> coordoperationGetParam(Object context, Object coordoperation, int index) {
        return convertKeywordMap(fn("proj-coordoperation-get-param").invoke(map("ctx", context, "coordoperation", coordoperation, "index", index)));
    }

    public static Map<String, Object> coordoperationGetGridUsed(Object context, Object coordoperation, int index) {
        return convertKeywordMap(fn("proj-coordoperation-get-grid-used").invoke(map("ctx", context, "coordoperation", coordoperation, "index", index)));
    }

    public static Map<String, Object> uomGetInfoFromDatabase(Object context, String authName, String code) {
        return convertKeywordMap(fn("proj-uom-get-info-from-database").invoke(map("context", context, "auth-name", authName, "code", code)));
    }

    public static Map<String, Object> gridGetInfoFromDatabase(Object context, String gridName) {
        return convertKeywordMap(fn("proj-grid-get-info-from-database").invoke(map("context", context, "grid-name", gridName)));
    }

    public static Map<String, Object> coordoperationGetTowgs84Values(Object context, Object coordoperation, int valueCount, int emitErrorIfIncompatible) {
        return convertKeywordMap(fn("proj-coordoperation-get-towgs84-values").invoke(map("ctx", context, "coordoperation", coordoperation,
            "value-count", valueCount, "emit-error-if-incompatible", emitErrorIfIncompatible)));
    }

    /**
     * Destroy a PROJ context. Usually not necessary, because the library tracks resources automatically.
     * @param context the context to destroy
     */
    public static void contextDestroy(Object context) {
        fn("proj-context-destroy").invoke(map("context", context));
    }

    /**
     * Destroy a PROJ object. Usually not necessary, because the library tracks resources automatically.
     * @param pj the PROJ object to destroy
     */
    public static void destroy(Object pj) {
        fn("proj-destroy").invoke(map("pj", pj));
    }

    /** Forward transformation direction */
    public static final int PJ_FWD = 1;
    /** Identity (no-op) transformation direction */
    public static final int PJ_IDENT = 0;
    /** Inverse transformation direction */
    public static final int PJ_INV = -1;

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

    // The first len values of a, padded with 0.0.
    private static PersistentVector vec(double[] a, int len) {
        Object[] out = new Object[len];
        for (int i = 0; i < len; i++) out[i] = i < a.length ? a[i] : 0.0;
        return PersistentVector.create(out);
    }

    private static final Pattern DASH_CHAR = Pattern.compile("-(.)");

    private static String kebabToCamelCase(String kebab) {
        return DASH_CHAR.matcher(kebab).replaceAll(m -> m.group(1).toUpperCase(Locale.ROOT));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> convertKeywordMap(Object cljMap) {
        if (cljMap == null) return null;
        Map<String, Object> javaMap = new HashMap<>();
        for (Map.Entry<Keyword, Object> e : ((Map<Keyword, Object>) cljMap).entrySet()) {
            javaMap.put(kebabToCamelCase(e.getKey().getName()), e.getValue());
        }
        return javaMap;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> convertKeywordMaps(Object cljList) {
        List<Object> in = (List<Object>) cljList;
        List<Map<String, Object>> result = new ArrayList<>(in.size());
        for (Object entry : in) result.add(convertKeywordMap(entry));
        return result;
    }
}
