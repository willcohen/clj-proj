# proj-wasm

A transpiled WebAssembly version of [PROJ](https://github.com/OSGeo/PROJ),
available for use from JavaScript.

This package is part of the [clj-proj](https://github.com/willcohen/clj-proj)
project and is experimental. See that project's
[README](https://github.com/willcohen/clj-proj/blob/main/README.md) for more
details.

## Installation

```bash
npm install proj-wasm
```

## Usage

```javascript
import * as proj from 'proj-wasm';

// Initialize PROJ (required before using any functions)
await proj.init();

// Create a coordinate transformation from WGS84 to Web Mercator
// (context is auto-created; pass one explicitly to control network or pin to a worker)
const transformer = await proj.projCreateCrsToCrs({
  source_crs: "EPSG:4326",  // WGS84
  target_crs: "EPSG:3857"   // Web Mercator
});

// Create a coordinate array for one point
const coords = await proj.coordArray(1);

// Set coordinates: [latitude, longitude, z, time] for EPSG:4326
// Example: Boston City Hall coordinates
await proj.setCoords(coords, [[42.3601, -71.0589, 0, 0]]);

// Transform the coordinates
await proj.projTransArray({
  p: transformer,
  direction: 1,  // PJ_FWD (forward transformation)
  n: 1,          // number of coordinates
  coord: coords  // pass the coord array object directly
});

// Access the transformed coordinates
const result = await proj.getCoords(coords, 0);
const x = result[0];  // Easting
const y = result[1];  // Northing
console.log(`Transformed coordinates: [${x}, ${y}]`);
// Output: Transformed coordinates: [-7910240.56, 5215074.24]
```

### Naming Conventions

All functions are available in camelCase and snake_case:

| camelCase | snake_case |
|-----------|------------|
| `contextCreate()` | `context_create()` |
| `projCreateCrsToCrs()` | `proj_create_crs_to_crs()` |
| `coordArray(n)` | `coord_array(n)` |
| `setCoords(coords, values)` | `set_coords_BANG_(coords, values)` |
| `projTransArray(options)` | `proj_trans_array(options)` |
| `getCoords(coords, idx)` | `get_coords(coords, idx)` |
| `getWorkerCount()` | `get_worker_count()` |

The snake_case names agree with the PROJ C API names. The `_BANG_` suffix in
snake_case is Clojure's `!` convention for mutating functions. The camelCase
aliases do not have it.

## API Reference

### Core Functions

- `init()` - Initialize the PROJ library (call this function first)
- `contextCreate(options?)` - Create a new PROJ context (optional, auto-created when omitted)
  - `options.network` - Turn network grid fetching on or off (default: `true`)
- `projCreateCrsToCrs(options)` - Create a transformation between two coordinate reference systems
  - `options.context` - PROJ context (optional, auto-created if omitted)
  - `options.source_crs` - Source CRS (for example, "EPSG:4326")
  - `options.target_crs` - Target CRS (for example, "EPSG:3857")

### Coordinate Handling

- `coordArray(n)` - Create a coordinate array for n coordinates (JS-side Float64Array)
- `setCoords(coords, values)` - Set coordinate values
  - `coords` - Coordinate array created with `coordArray`
  - `values` - Array of [x, y, z, t] coordinate tuples
- `projTransArray(options)` - Transform coordinates
  - `options.p` - The transformer
  - `options.direction` - 1 for forward, -1 for inverse
  - `options.n` - Number of coordinates
  - `options.coord` - The coordinate array object
- `getCoords(coords, idx)` - Read the coordinate at index `idx` from the array
- `getWorkerCount()` - Returns the number of workers in the pool

### CRS Introspection

Functions that examine CRS structure return plain objects with typed fields. The library reads C output parameters automatically. No heap management is necessary. Return keys agree with the calling convention: camelCase aliases return camelCase keys, and snake_case aliases return snake_case keys.

- `projGetAreaOfUse(options)` / `proj_get_area_of_use(options)` - Get area of use for any PJ object
  - Returns `{ westLonDegree, southLatDegree, eastLonDegree, northLatDegree, areaName }` (camelCase) or `{ west_lon_degree, south_lat_degree, east_lon_degree, north_lat_degree, area_name }` (snake_case), or `null`
- `projEllipsoidGetParameters(options)` - Get ellipsoid parameters
  - Returns `{ semiMajorMetre, semiMinorMetre, isSemiMinorComputed, invFlattening }` or `null`
- `projCsGetAxisInfo(options)` - Get coordinate system axis info
  - Returns `{ name, abbreviation, direction, unitConvFactor, unitName, unitAuthName, unitCode }` or `null`
- `projPrimeMeridianGetParameters(options)` - Get prime meridian parameters
  - Returns `{ longitude, unitConvFactor, unitName }` or `null`
- `projCoordoperationGetMethodInfo(options)` - Get coordinate operation method
  - Returns `{ methodName, methodAuthName, methodCode }` or `null`
- `projUomGetInfoFromDatabase(options)` - Get unit of measure info
  - Returns `{ name, convFactor, category }` or `null`
- `projGridGetInfoFromDatabase(options)` - Get grid file info
  - Returns `{ fullName, packageName, url, directDownload, openLicense, available }` or `null`

### CRS Catalog

`projGetCrsInfoListFromDatabase` returns the CRS catalog as an array of plain
objects. An empty `auth_name` gives every authority, approximately 12000
entries.

```javascript
await proj.init();
const ctx = await proj.contextCreate();
const entries = await proj.projGetCrsInfoListFromDatabase({
  context: ctx,
  auth_name: ""
});
```

Each entry has camelCase keys: `authName`, `code`, `name`, `type`,
`deprecated`, `bboxValid`, `westLonDegree`, `southLatDegree`, `eastLonDegree`,
`northLatDegree`, `areaName`, `projectionMethodName`, `celestialBodyName`.

There is no server-side type filter. To select one kind of CRS, compare
`entry.type` with an exported `PJ_TYPE_*` constant, for example
`proj.PJ_TYPE_PROJECTED_CRS` (15):

```javascript
const projected = entries.filter(e => e.type === proj.PJ_TYPE_PROJECTED_CRS);
```

This module exposes the PROJ C API plus the camelCase aliases above. It has no
wrapper object with methods (for example, `getCrsInfoListFromDatabase({types})`).
Call the exported functions directly.

### Accessing Results

After transformation, read coordinates with `getCoords`:
```javascript
const result = await proj.getCoords(coords, 0);
// result[0] - X (easting/longitude)
// result[1] - Y (northing/latitude)
// result[2] - Z (height)
// result[3] - T (time)
```

## Common CRS Examples

- `EPSG:4326` - WGS84 (GPS coordinates)
- `EPSG:3857` - Web Mercator (used by Google Maps, OpenStreetMap)

## License

proj-wasm is under the MIT License. Refer to `LICENSE` in this package.

The package also contains code and data from other projects, for example PROJ
and Emscripten. Refer to
[THIRD-PARTY-NOTICES.md](https://github.com/willcohen/clj-proj/blob/main/THIRD-PARTY-NOTICES.md).
