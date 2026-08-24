# clj-proj

**[Live Demo](https://willcohen.github.io/clj-proj/)**

[![CI](https://github.com/willcohen/clj-proj/actions/workflows/ci.yml/badge.svg)](https://github.com/willcohen/clj-proj/actions/workflows/ci.yml)
[![NPM Version](https://img.shields.io/npm/v/proj-wasm)](https://www.npmjs.com/package/proj-wasm)
[![Clojars Version](https://img.shields.io/clojars/v/net.willcohen%2Fproj)](https://clojars.org/net.willcohen/proj)

This project supplies a native (or transpiled) version of [PROJ](https://proj.org/) ([GitHub](https://github.com/OSGeo/PROJ)) for the JVM and JS ecosystems.

The goal is a fast version of PROJ for these platforms that stays
synchronized with upstream development.

For the JVM, a [package published to
Clojars](https://clojars.org/net.willcohen/proj) gives Clojure bindings. For
JavaScript, [an ES6 module called
`proj-wasm`](https://www.npmjs.com/package/proj-wasm) published to NPM gives an
interface to an internal transpiled WASM module.


## EARLY DEVELOPMENT 

This project is new. Some functions are not complete. The tests are not
complete. Feedback from testers is welcome.

The APIs and the structure of this library are an early work-in-progress.
They can change while development continues.

## How It Works

### API Scope

This library is limited to PROJ's [C API for ISO 19111
functionality](https://proj.org/en/stable/development/reference/functions.html#c-api-for-iso-19111-functionality).
The PROJ C API has some sections. Objects from the ISO 19111 section do not
mix with functions from the other sections. The exception is an ISO 19111
`CoordinateOperation` object with a valid PROJ pipeline export. These objects
operate with transformation functions, for example `proj_trans_array()`.

### Implementation Strategy

The library has one API (`net.willcohen.proj.proj`). At runtime, it automatically selects the first available backend in this sequence:

1. **Native FFI (Panama FFM through dtype-next)** - Direct calls to compiled PROJ libraries
2. **GraalVM WebAssembly** - Runs emscripten-compiled PROJ in JVM
3. **JavaScript/WebAssembly** - Direct WASM for Node.js and browsers

During initialization, the library identifies the environment and the available backends:

```clojure
;; The implementation atom tracks which backend is active
(defonce implementation (atom nil))

;; JVM: clj-native's try-init! attempts native FFI, then falls back to
;; the GraalVM WASM backend, and records the winner in the atom.
;; CLJS: starts the worker pool and returns a Promise.
(defn init! [opts]
  #?(:clj
     (nps/try-init! implementation force-graal log?
                    native/init-proj
                    wasm/init-proj)
     :cljs
     (-> (wasm/init-proj opts)
         (.then (fn [_] (reset! implementation runtime))))))
```

### Runtime Dispatch System

All PROJ functions flow through a central dispatcher:

```clojure
;; Macros generate thin wrapper functions
(defn proj-create-crs-to-crs [opts]
  (dispatch-proj-fn :proj_create_crs_to_crs 
                    (get fndefs :proj_create_crs_to_crs) 
                    opts))

;; The dispatcher orchestrates the entire call flow
(defn dispatch-proj-fn [fn-key fn-def opts]
  (ensure-initialized!)
  (let [args (extract-args fn-def opts)
        result (call-native fn-key fn-def args)]
    (process-return-value-with-tracking result fn-def)))
```

The real `dispatch-proj-fn` adds an auto-context step, cross-worker argument
reconciliation, a per-return-type branch for `:struct-list` and
`:out-params`, and a result check that reads `proj_context_errno`.

The dispatch system does these tasks:
- **Argument extraction**: It converts Clojure maps to function arguments and applies defaults
- **Platform routing**: It sends calls to the applicable backend implementation
- **Return processing**: It converts C types back to Clojure data structures
- **Context management**: It gives thread-safe access to PROJ contexts

### Resource Management

PROJ returns pointers that the caller must free. The library tracks these resources and releases them automatically:

- **JVM**: automatic cleanup through `tech.v3.resource` during garbage collection
- **JavaScript**: automatic cleanup through the `resource-tracker` library

```clojure
;; When a function returns a pointer, the library tracks it automatically
;; No manual cleanup needed - this happens behind the scenes:
(resource/track result-pointer
  {:dispose-fn (fn [] (proj-destroy pointer-address))
   :track-type :auto})  ; Cleaned up on GC
```

Manual calls to `proj-destroy` or other cleanup functions are not necessary. The library releases all resources when they go out of scope or during garbage collection.

### Context Management

PROJ uses contexts for thread safety and operation tracking:

```clojure
;; Use an explicit context (stored in an atom)
(def ctx (context-create))
(proj-get-authorities-from-database {:context ctx})

;; Or let the library create a temporary context
(proj-get-authorities-from-database {})  ; Creates context internally
```

In JavaScript, the worker pool pins each context to one worker. If PJ objects from different workers go to the same function (for example, after round-robin context creation), the library recreates the mismatched objects on the target worker through a PROJJSON roundtrip. The library also sends a `console.warn` message. For the best performance, use an explicit shared context.

When atomic context access is necessary, the library uses the `cs` (context-swap) wrapper:
- It puts each operation in an atom's `swap!` for thread-safe access
- It records operation counts and results
- It applies platform-specific context requirements

Each context atom holds this state:
- The native context pointer
- An operation counter
- Result storage for atomic operations

### Coordinate Transformation Implementation

The library transforms coordinates in arrays. A single coordinate uses an
array of one.

```clojure
;; Batch transformation with coordinate arrays
(def coords (coord-array 2))  ; 2 coordinates
;; EPSG:4326 uses lat/lon order. Each coordinate is (x y z t).
(set-coords! coords [[42.3603222 -71.0579667 0 0]
                     [40.7127 -74.0059 0 0]])
(proj-trans-array {:p transformation :direction 1 :n 2 :coord coords})
```

`proj-trans-array` mutates the coordinate array in place. The `:direction`
key is necessary: 1 is forward, -1 is inverse. `set-coords!` needs one
coordinate for each row of the array, and each coordinate needs all four
values.

Each platform implements coordinate arrays differently:
- **FFI**: `dtype-next` tensors give zero-copy native memory access
- **GraalVM**: memory allocated in the WASM heap
- **ClojureScript**: worker-allocated arrays through message passing

### Advanced Features

#### Dynamic Implementation Switching
```clojure
;; Force a specific implementation for testing
(force-graal!)  ; Use GraalVM even if native is available
(force-ffi!)    ; Use native FFI

;; Check current implementation
(ffi?)    ; => true if using native
(graal?)  ; => true if using GraalVM
```

#### Cross-Platform Testing
The test framework makes sure that behavior is the same on all implementations:
```clojure
(defmacro with-each-implementation [& body]
  ;; Runs the same test against FFI, GraalVM, and ClojureScript
  ...)
```

### Flexible API

The API gives these conveniences:

- **Parameter naming flexibility**: Use underscores or hyphens
  ```clojure
  ;; The two forms work identically:
  (proj-create-crs-to-crs {:source_crs "EPSG:4326" :target_crs "EPSG:2249"})
  (proj-create-crs-to-crs {:source-crs "EPSG:4326" :target-crs "EPSG:2249"})
  ```

- **Optional parameters with defaults**: Functions apply defaults
  ```clojure
  ;; Context is optional - library creates one if needed
  (proj-get-authorities-from-database {})
  
  ;; Or provide your own
  (proj-get-authorities-from-database {:context my-ctx})
  ```

- **Idiomatic return keys per platform**: Out-param and struct-list functions return maps with platform-native key casing:
  - Clojure: kebab-case keywords (`:west-lon-degree`, `:semi-major-metre`)
  - Java: camelCase strings (`"westLonDegree"`, `"semiMajorMetre"`)
  - JS camelCase aliases: camelCase keys (`westLonDegree`, `semiMajorMetre`)
  - JS snake_case aliases: snake_case keys (`west_lon_degree`, `semi_major_metre`)

- **Consistent error handling**: Error behavior is the same on all platforms
  - The library catches C++ exceptions from WASM and converts them
  - The library puts native errors in Clojure exceptions

## Grid Fetching

For some PROJ transformations, grid shift files are necessary. These files are TIFF-format datum corrections from `cdn.proj.org`. Without these grids, some transformations (for example, NAD27->NAD83) give only "ballpark" accuracy. When network access is on, clj-proj fetches grids automatically.

### Per-Platform Behavior

- **JVM + Native FFI**: Java's HttpClient does HTTP range requests through native upcall callbacks. No configuration is necessary.
- **JVM + GraalVM WASM**: Java's HttpClient fetches grids through ProxyExecutable callbacks that `network.clj` installs in the WASM function table. No configuration is necessary.
- **Browser**: PROJ runs in Web Workers, where Emscripten's synchronous FETCH (through `Atomics.wait`) is permitted. No special headers are necessary.
- **Node.js**: PROJ runs in `worker_threads` with an XMLHttpRequest polyfill that sends sync requests to a fetch-worker through SharedArrayBuffer + Atomics. No configuration is necessary.

### Network On and Off

```clojure
;; Network enabled by default
(def ctx (proj/context-create))

;; Explicitly disable network
(def ctx-offline (proj/context-create {:network false}))
```

```javascript
// JavaScript - context is optional; create one explicitly to control network
const ctx = await proj.contextCreate();              // network enabled (default)
const ctxOffline = await proj.contextCreate({network: false}); // network disabled
```

### Browser: no Cross-Origin Isolation necessary

There is one WASM build, and it is single-threaded. Each worker runs that
binary with no internal threads. The WASM memory is a plain `ArrayBuffer`,
not a `SharedArrayBuffer`.

Thus the `Cross-Origin-Opener-Policy` header and the
`Cross-Origin-Embedder-Policy` header are not necessary. The library operates
the same when `crossOriginIsolated` is true or false. The browser test suite
runs against a server of each type.

Parallelism comes from the worker pool. To change the number of workers, use
the `workers` option of `init`.

### Known Limitations
- GraalVM network callbacks add initialization overhead

## Platform-Specific Details

### JVM (Java / Clojure)

The JVM implementation has two backends:

1. **Native FFI (Preferred)** - Available on supported platforms
2. **GraalVM WebAssembly (Fallback)** - For platforms without native libraries

Supported platforms (native):
- macOS/darwin Apple Silicon (arm64)
- Linux x64 and arm64
- Windows x64

Not yet built:
- macOS/darwin Intel (x86_64)
- Windows ARM64 - Cross-compiler not available in nixpkgs

### JDK 25+ with native library

On platforms with a native precompiled PROJ, this library calls it through
dtype-next on the JDK Panama FFM backend (`java.lang.foreign`). This is the
preferred option.

#### How Native FFI Works

The native implementation:
1. Extracts platform-specific libraries from resources to a temp directory
2. Binds the `dtype-next` library singleton to the absolute path of the extracted library
3. Uses `dtype-next` for native interop and memory management

The library contains pre-compiled PROJ libraries for each platform in `resources/{platform}/`. At runtime, it identifies the OS and architecture. Then it loads the applicable libraries.

#### Usage

On a computer where the native library was built:
```clojure
(require '[net.willcohen.proj.proj :as proj])

;; Initialization happens automatically on first use in Clojure/JVM
;; For explicit initialization, call (proj/init!)

;; Create a coordinate transformation
(def ctx (proj/context-create))
(def transformer (proj/proj-create-crs-to-crs {:context ctx
                                               :source-crs "EPSG:4326"
                                               :target-crs "EPSG:2249"}))

;; Transform a single coordinate 
(def coords (proj/coord-array 1))
;; EPSG:4326 uses lat/lon order, not lon/lat!
(proj/set-coords! coords [[42.3603222 -71.0579667 0 0]]) ; Boston City Hall (lat, lon)
(proj/proj-trans-array {:p transformer :direction 1 :n 1 :coord coords})
;; coords now contains transformed coordinates in EPSG:2249 (MA State Plane)

;; Query available authorities
(proj/proj-get-authorities-from-database)
;; => ["EPSG" "ESRI" "IAU_2015" "IGNF" "NKG" "NRCAN" ...]

;; No manual cleanup needed! Resources are automatically tracked and 
;; cleaned up when they go out of scope or during garbage collection
```

### Java API

A Java wrapper class (`net.willcohen.proj.PROJ`) gives idiomatic Java access to the library:

```java
import net.willcohen.proj.PROJ;

// Initialize (auto-selects best backend: native FFI or GraalVM WASM)
PROJ.init();

// Create a context and transformation
Object ctx = PROJ.contextCreate();
Object transform = PROJ.createCrsToCrs(ctx, "EPSG:4326", "EPSG:2249");

// Transform coordinates (EPSG:4326 uses lat/lon order)
Object coords = PROJ.coordArray(1);
PROJ.setCoords(coords, new double[][]{{42.3603222, -71.0579667}}); // Boston City Hall
PROJ.transArray(transform, coords, 1);
// coords now contains transformed coordinates in EPSG:2249 (MA State Plane)

// Query available authorities
List<String> authorities = PROJ.getAuthoritiesFromDatabase();
// => ["EPSG", "ESRI", "IAU_2015", "IGNF", "NKG", "NRCAN", ...]

// Create transformation from CRS objects (for advanced use)
Object sourceCrs = PROJ.createFromDatabase(ctx, "EPSG", "4326");
Object targetCrs = PROJ.createFromDatabase(ctx, "EPSG", "2249");
Object transformFromPj = PROJ.createCrsToCrsFromPj(ctx, sourceCrs, targetCrs);

// No manual cleanup needed - resources are automatically tracked!
```

The Java API has the same functions as the Clojure API. It includes:
- All initialization and backend control methods (`init()`, `forceGraal()`, `forceFfi()`)
- Context management (`contextCreate()`, `isContext()`)
- CRS transformations (`createCrsToCrs()`, `createCrsToCrsFromPj()`, `createFromDatabase()`)
- Coordinate arrays (`coordArray()`, `setCoords()`, `transArray()`)
- Database queries (`getAuthoritiesFromDatabase()`, `getCodesFromDatabase()`)
- CRS introspection (`getAreaOfUse()`, `ellipsoidGetParameters()`, `csGetAxisInfo()`, `primeMeridianGetParameters()`, `coordoperationGetMethodInfo()`, and so on). The library reads C output parameters automatically and returns Maps
- Direction constants (`PJ_FWD`, `PJ_INV`, `PJ_IDENT`)

### JDK 25+ with GraalVM WebAssembly

On platforms with no native library, this library runs the transpiled
WebAssembly version of PROJ through GraalVM's WebAssembly support.

For this transpiled PROJ, JDK 25 or later is necessary because of GraalVM.
Enable JVMCI for better performance.

#### How GraalVM Implementation Works

When native libraries are not available, the GraalVM implementation:
1. Creates a GraalVM polyglot context with JavaScript and WebAssembly support
2. Loads the emscripten-compiled PROJ module, then writes proj.db and proj.ini to Emscripten's virtual filesystem
3. Does type conversion between JVM and JavaScript with ProxyArray, ProxyExecutable, and ProxyObject
4. Installs Java-side ProxyExecutable callbacks (`network.clj`) in the WASM function table and registers them with PROJ. This lets Java's HttpClient fetch grids
5. Catches C++ exceptions from WASM code

> **Note:** GraalVM can show "WARNING: The polyglot context is using an implementation that does not support runtime compilation" during initialization. This message shows interpreted (non-JIT) WASM execution. It has no effect on correctness.

Initialization is slow. The load of the WASM binary (3.6MB) and the PROJ database (10.2MB) takes some seconds.

#### Usage

To force GraalVM implementation on a system where native libraries are available:

```clojure
(require '[net.willcohen.proj.proj :as proj])

;; Force GraalVM WASM implementation
;; If on a fallback-only platform, this step is unneeded
(proj/force-graal!)
;; => true

;; Usage is identical to native implementation
(def ctx (proj/context-create))
(def transformer (proj/proj-create-crs-to-crs {:context ctx
                                               :source-crs "EPSG:4326"
                                               :target-crs "EPSG:2249"}))

;; Transform coordinates (EPSG:4326 uses lat/lon order)
(def coords (proj/coord-array 1))
(proj/set-coords! coords [[42.3603222 -71.0579667 0 0]]) ; Boston City Hall
(proj/proj-trans-array {:p transformer :direction 1 :n 1 :coord coords})
;; coords now contains transformed coordinates

;; No manual cleanup needed - resources are automatically managed!
```

Note: GraalVM initialization takes 5-7 seconds because it loads the WASM module. You can see Truffle/GraalVM diagnostic output during initialization.

### JavaScript / ClojureScript

The JavaScript implementation uses emscripten-compiled PROJ in workers:

- **Worker-based WASM execution**: PROJ runs in Web Workers (browser) or `worker_threads` (Node.js). This keeps the main thread responsive. It also lets the workers do synchronous network operations for grid fetching
- **squint compilation**: ClojureScript code compiles to ES6 modules
- **Async API**: All operations return Promises because they dispatch to workers

#### Environment-Specific Behavior

The library identifies the JavaScript environment automatically and adapts to it:

- **Node.js**: PROJ runs in `worker_threads` with an XMLHttpRequest polyfill for grid fetching
- **Browser**: PROJ runs in Web Workers with Emscripten's built-in FETCH support
- **Environment detection**: Automatic at initialization

#### Usage

For Node.js, create `index.mjs`:
```javascript
import * as proj from "proj-wasm";

// Initialize PROJ (required before any operations in JavaScript)
await proj.init();  // Convenience alias for init! (also available as init_BANG_)

// Create a transformation (all operations are async, context is auto-created)
const transformer = await proj.projCreateCrsToCrs({
  source_crs: "EPSG:4326",
  target_crs: "EPSG:2249"
});

// Transform coordinates (EPSG:4326 uses lat/lon order)
const coords = await proj.coordArray(1);
await proj.setCoords(coords, [[42.3603222, -71.0579667, 0, 0]]); // Boston City Hall (lat, lon)
await proj.projTransArray({
  p: transformer,
  direction: proj.PJ_FWD,
  n: 1,
  coord: coords
});

// Read transformed coordinates
const transformed = await proj.getCoords(coords, 0);
console.log("Transformed:", transformed[0], transformed[1]);

// Shutdown workers when done (allows Node.js process to exit cleanly)
await proj.shutdown();

// Resources are automatically cleaned up - no manual cleanup needed!
// The resource-tracker library releases them when objects go out of scope

// Optional: create an explicit context to disable network or pin to a worker
// const ctx = await proj.contextCreate({ network: false });
// const t = await proj.projCreateCrsToCrs({ context: ctx, ... });
```

For browsers, the API is the same, and no special headers are necessary.

```bash
$ node index.mjs
# Transformed coordinates will be displayed
```

# clj-proj Build Guide

## Prerequisites

Builds run through Babashka and Nix. The host build needs no container.

Two tasks do need podman or docker:

- `bb build --cross` and `bb build --cross-platform` build each target in a
  container, from the Containerfile that clj-native supplies. clj-proj has no
  Containerfile of its own. The task vendors clj-native into
  `clj-native-vendor/` and points the flake at it. On the published clj-native
  jar, the flake files come out of the jar. With the `:dev` checkout override,
  the task copies the checkout.
- `bb test:linux` pulls the public `clojure:tools-deps-trixie` image.

**Babashka + Nix users**:
- Install [Nix](https://nixos.org/download.html) and [direnv](https://direnv.net/)
- **One-time setup**: `direnv allow`
- The flake pins **GraalVM CE 25** as the JDK. Its libgraal lets the `:graal`
  PROJ WASM guest JIT-compile. Without it, the guest runs interpreted, which
  is much slower. `direnv allow` points `JAVA_HOME` at the GraalVM JDK
  automatically for each shell that enters the repo. For a shell without
  direnv, run tasks with `direnv exec . bb <task>`. The `bb test:graal` /
  `test:clojure-graal` tasks give a warning if they identify a non-GraalVM
  JDK.
- Use `direnv exec <dir> <cmd>`, which uses the cached dev shell.
  `nix develop <dir> --command <cmd>` gives the same environment. But it
  evaluates the flake again on each call, and this takes seconds each time.

## Building

### Quick Reference

```bash
bb tasks          # List all available commands
bb build --help   # Show build options
bb clean --help   # Show clean options
```

### Common Build Tasks

```bash
bb build --native                         # Native libraries, current platform
bb build --wasm                           # WebAssembly
bb build --cross-platform linux/amd64     # One cross target
bb build --cross-platform linux/aarch64
bb build --cross-platform windows/amd64
bb build --cross                          # All default platforms
bb test-run                               # Build everything, run all tests
```

### Development Setup

```bash
direnv allow      # One-time setup
bb dev            # Rich REPL with Portal
bb demo           # Browser demo at localhost:8080
```

### Packaging

```bash
bb jar            # JVM (JAR file)
bb squint         # JavaScript (ES6 module)
```

### Build Process Overview

1. **Native builds** compile PROJ + dependencies (SQLite, LibTIFF, zlib) for the host platform
   - **Output**: `resources/{platform}/` (for example, `resources/darwin-aarch64/`)
   - **Linux**: Static linking
   - **Windows**: Static linking

2. **WASM builds** use emscripten to compile PROJ into WebAssembly
   - **Output**: `resources/wasm/` and `src/cljc/net/willcohen/proj/`
   - **Requirements**: emscripten tools in PATH, supplied by the Nix dev shell

3. **Cross-platform builds** use Nix for reproducible builds
   - **Resource requirements**: 150GB disk, 8GB RAM

## Testing

```bash
bb test:all           # Everything

bb test:ffi           # Native FFI
bb test:graal         # GraalVM WebAssembly
bb test:node          # JavaScript / Node.js
bb test:playwright    # Browser integration (requires a display)

bb test:jar           # JAR as a downstream dependency
bb test:npm           # npm package as a downstream dependency
bb test:linux         # Linux platforms, in a public container image
```

The test framework runs identical tests against all implementations. This makes sure that behavior is the same on all platforms.

## Architecture Notes

### File Organization

```
clj-proj/
├── src/
│   ├── clj/net/willcohen/proj/impl/    # JVM-specific implementations
│   │   ├── native.clj                  # Panama FFM bindings
│   │   ├── logging.clj                 # PROJ log callback
│   │   ├── network.clj                 # GraalVM WASM grid fetching
│   │   └── struct.clj                  # Native struct definitions
│   ├── cljc/net/willcohen/proj/        # Cross-platform core
│   │   ├── proj.cljc                   # Public API + dispatch
│   │   ├── wasm.cljc                   # WASM interface (GraalVM + CLJS workers)
│   │   ├── handler.cljc                # Workload-pool handler spec
│   │   ├── fndefs.cljc                 # PROJ function definitions
│   │   ├── macros.cljc                 # Macros for JVM and squint
│   │   ├── proj-loader.mjs             # Main-thread WASM orchestrator
│   │   ├── proj-handler.mjs            # Generated worker-router handler
│   │   ├── proj-handler-overrides.mjs  # Hand-written half of the handler
│   │   ├── esbuild.config.mjs          # Bundler config
│   │   ├── *.mjs                       # squint-generated JS modules
│   │   └── dist/                       # esbuild bundle output (npm package)
│   └── java/net/willcohen/proj/
│       └── PROJ.java                   # Java API wrapper
├── resources/
│   ├── {platform}/                     # Native libraries per platform
│   ├── wasm/                           # WASM artifacts read by GraalVM
│   │   ├── proj-emscripten.js          # WASM JS glue
│   │   ├── proj-emscripten.wasm        # WASM binary
│   │   └── proj-loader.mjs             # proj-loader.mjs for classpath
│   ├── proj.db                         # PROJ database
│   └── proj.ini                        # PROJ configuration
├── deps.edn                            # Clojure dependencies
├── bb.edn                              # Babashka build tasks
├── build.clj                           # Clojure build configuration
└── flake.nix                           # Nix development environment
```

### Key Implementation Files

**Core API & Dispatch:**
- `src/cljc/net/willcohen/proj/proj.cljc` - Main public API and dispatch logic
- `src/cljc/net/willcohen/proj/fndefs.cljc` - PROJ function definitions and constants
- `src/cljc/net/willcohen/proj/macros.cljc` - Code generation macros for multi-platform support
- `src/cljc/net/willcohen/proj/wasm.cljc` - GraalVM context management (CLJ) and worker pool (CLJS)
- `src/cljc/net/willcohen/proj/handler.cljc` - Per-worker init and destroy for the workload-pool `:proj` handler

**JVM Implementations:**
- `src/clj/net/willcohen/proj/impl/native.clj` - Panama FFM implementation for native libraries
- `src/clj/net/willcohen/proj/impl/struct.clj` - Native struct definitions for FFI
- `src/clj/net/willcohen/proj/impl/logging.clj` - Native upcall for PROJ log routing
- `src/clj/net/willcohen/proj/impl/network.clj` - Grid fetch callbacks for native FFI and GraalVM WASM

**JavaScript Workers:**
- `src/cljc/net/willcohen/proj/proj-loader.mjs` - Main-thread orchestrator (init, worker pool)
- `src/cljc/net/willcohen/proj/proj-handler.mjs` - Generated worker-router handler. It runs every ccall
- `src/cljc/net/willcohen/proj/proj-handler-overrides.mjs` - Hand-written half of that handler
- `fetch_worker.mjs` (from clj-native) - Node.js sync HTTP bridge through SharedArrayBuffer + Atomics

**Java API:**
- `src/java/net/willcohen/proj/PROJ.java` - Java wrapper class

### Call Flow

`proj.cljc` is the public API for all platforms. On the JVM, `init!` tries
native FFI first. If that fails, it uses GraalVM WASM. On ClojureScript,
`init!` initializes the worker pool. After init, all calls dispatch through
the active backend.

```
JVM — Native FFI (preferred):

  proj.cljc         Public API. Blocking calls. Dispatches based on
    │                @implementation (:ffi or :graal).
    ▼
  native.clj        Panama FFM through dtype-next. Extracts the
    │                platform-specific shared libraries from
    │                resources/{platform}/ to a temp dir, then binds them.
    │                Direct C calls, no WASM.
    │
    ├─ logging.clj   Native upcall that routes PROJ's log output to
    │                 clojure.tools.logging.
    │
    └─ struct.clj    Native struct definitions (PJ_COORD and so on) for
                     zero-copy memory access through dtype-next tensors.
                     Native upcalls to Java HttpClient fetch the grids.
```

```
JVM — GraalVM WASM (fallback, or forced with force-graal!):

  proj.cljc         Same public API, same dispatch.
    │
    ▼
  wasm.cljc         Creates a GraalVM polyglot context with JS + WASM support.
    │                Loads proj-emscripten.wasm, proj.db, and proj.ini from
    │                the classpath (resources/wasm/).
    ▼
  proj-loader.mjs   load() runs the Emscripten module directly in the
    │                polyglot context. No workers, no postMessage. JVM to
    │                JS interop goes through ProxyArray and ProxyObject.
    │
    ├─ network.clj   Grid fetch callbacks. ProxyExecutable callbacks in the
    │                 WASM function table call into Java, where network.clj
    │                 does the HTTP with Java's HttpClient.
    │
    └─ logging.clj   Same log routing as FFI, adapted for GraalVM callbacks.
```

```
Browser / Node.js (ClojureScript):

  proj.cljc         Public API. All operations return Promises.
    │
    ▼
  wasm.cljc         Main thread. Spawns the worker-router pool and routes
    │                each call to the worker that owns the relevant PJ
    │                context. worker-router owns the call protocol, so
    │                there is no hand-rolled postMessage ID table here.
    ▼
  proj-loader.mjs   Reads proj.db and proj.ini once on the main thread.
    │                init-workers! forwards them into each worker through
    │                the handler init args.
    ▼
  proj-handler.mjs  Runs in a Web Worker (browser) or worker_thread (Node.js).
    │                All PROJ ccall/malloc/free operations happen here.
    │                Loads the Emscripten module and writes proj.db/proj.ini
    │                to Emscripten's virtual filesystem. clj-native's
    │                generator makes it from proj-handler-overrides.mjs. Its
    │                runtime queue serializes every call.
    ▼
  fetch_worker.mjs  Node.js only, supplied by clj-native. A second
                     worker_thread that connects Emscripten's synchronous
                     XMLHttpRequest (used for grid fetch) to Node.js async
                     http/https through SharedArrayBuffer + Atomics.wait and
                     notify. Browsers do not need this, because Web Workers
                     can use Emscripten's built-in FETCH support.
```

### WASM Build Output

`bb build --wasm` makes one single-threaded WASM build from the PROJ
source. `--wasm-browser` and `--wasm-graal` are aliases of the same task.

The task writes `proj-emscripten.js` and `proj-emscripten.wasm` to two
directories:

- `src/cljc/net/willcohen/proj/`, where the squint and esbuild pipeline
  bundles them into `dist/` for ClojureScript.
- `resources/wasm/`, where GraalVM reads them from the classpath.

One build is sufficient for all three lanes. GraalVM's polyglot engine does
not support pthreads. Browsers get no benefit from a `SharedArrayBuffer`
memory here. Thus a second variant has no purpose.

### Performance Considerations

- **Initialization**: Native FFI is almost immediate. GraalVM takes 5-7 seconds
- **Transformations**: Native is fastest, followed by direct WASM, then GraalVM
- **Memory**: Coordinate arrays use platform-specific optimizations

## Development

### 1. Nix Flake and Direnv

A nix flake pins the build dependencies. This prevents errors from changed
dependency versions. The flake operates with [direnv](https://direnv.net)
(see .envrc's `use flake`).

### 2. Local REPL

Development REPLs with different configurations:

```bash
# Rich development REPL with Portal and other tools
bb dev

# Basic nREPL with Portal (port 7888)
bb nrepl

# Standard Clojure REPL
clj
```

An editor with Clojure support can help: Emacs with CIDER, VSCode with Calva and Portal extensions, IDEA with Cursive.

The `bb nrepl` task starts an nREPL server on port 7888. Portal is included for data visualization and debugging.

### 3. Demo Server

Run the browser demo locally:

```bash
bb demo  # Serves at http://localhost:8080/docs/
```

### 4. Documentation

Generate API documentation (work in progress):

```bash
bb quickdoc  # Generates docs from source
```

### 5. Task Reference

Run `bb tasks` for the complete list. Key commands:

**Build & Package:**
- `bb build --help` - Show build options (native/wasm/cross)
- `bb build:all` - Build native + WASM + cross-platform artifacts
- `bb jar` - Build JAR file for JVM
- `bb squint` - Build JavaScript ES6 module
- `bb pom` - Generate/update pom.xml

**Testing:**
- `bb test:all` - Run all tests
- `bb test:ffi` / `bb test:node` / `bb test:graal` - Test specific implementations
- `bb test-run` - Complete build + test cycle
- `bb pre-deploy` - Full build, test, and package verification before deploy

**Development:**
- `bb dev` - Rich REPL with Portal
- `bb nrepl` - nREPL server (port 7888)
- `bb demo` - Browser demo (localhost:8080)

**Deployment:**
- `bb deploy:dry-run` - Check auth, show JAR/npm contents, npm publish dry-run
- `bb deploy` - Tag, push, deploy to Clojars + npm
- `bb version-bump <version>` - Bump version across all files

**CI:**
- `bb download-ci-artifacts` - Download all build artifacts (source archives, native libs, WASM) from the most recent CI run (the `gh` CLI is necessary)

**Utilities:**
- `bb clean --help` - Show clean options
- `bb jar-contents` - List files in JAR
- `bb npm-contents` - List files in npm package
- `bb proj:clone --help` - Local PROJ development

### 7. The clj-native JavaScript Dependency

The JavaScript side uses clj-native through its published npm package.
`src/cljc/net/willcohen/proj/package.json` pins an exact `ffi-wasm`
version, and a plain `npm install` gets it from the registry.

To develop against a clj-native checkout, point the dependency at a built
tarball. This is the npm twin of the `:dev` alias in `deps.edn` and of the
flake's `--override-input`:

```bash
cd ../clj-native && npm pack
```

The `prepack` script runs `bb build:js` first, which compiles clj-native's
`.cljc` sources to the `.mjs` modules that go into the package. Then set
`"ffi-wasm"` to `"file:../../../../../../clj-native/ffi-wasm-<version>.tgz"`
in `package.json` and run `npm install --prefix src/cljc/net/willcohen/proj`.
Do not commit that change. After each clj-native edit, run the pack and the
install again.

worker-router is a published package (`npm:@wcohen/worker-router`), so a plain
`npm install` gets it. For work on worker-router itself, link
`node_modules/worker-router` to a local checkout. Run `npm run build` there.
Then run `bb squint` here to rebuild the bundle.

### 8. Local PROJ Development Workflow

clj-proj has a workflow for developers of the PROJ C library. The workflow lets you examine local PROJ changes against the bindings before you send the changes upstream.

**Setup:**
```bash
# Clone PROJ repository locally
bb proj:clone                    # Clone to vendor/PROJ (master branch)
bb proj:clone --branch=feature   # Clone specific branch
bb proj:clone --update           # Update existing clone
```

**Development Workflow:**
```bash
# Make changes to PROJ C code
cd vendor/PROJ
# ... edit C files, add features, fix bugs ...
git commit -m "experimental change"
cd ../..

# Test changes against clj-proj
bb build --native --local-proj --debug    # Use local PROJ instead of release
bb test:ffi                                # Verify bindings still work

# Test WASM compatibility
bb build --wasm --local-proj
bb test:node

# Cross-platform verification
bb build --cross --local-proj             # Test musl builds with local PROJ
```

**Local PROJ Tasks:**
- `proj:clone` - Clone OSGeo/PROJ repository with options (`--help` for details)

**Local PROJ Build Flags:**
- `--local-proj` - Use `vendor/PROJ` instead of released PROJ version (applicable to all build tasks)

**Directory Structure:**
```
clj-proj/
├── vendor/           # gitignored - your local development area
│   └── PROJ/         # cloned OSGeo/PROJ repository
├── bb.edn
└── ...
```

## License

```
Copyright (c) 2024, 2025, 2026 Will Cohen

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
the Software, and to permit persons to whom the Software is furnished to do so,
subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

```
--

This project uses code from [PROJ](https://github.com/OSGeo/PROJ), which is
distributed under these terms:

```
All source, data files and other contents of the PROJ package are 
available under the following terms.  Note that the PROJ 4.3 and earlier
was "public domain" as is common with US government work, but apparently
this is not a well defined legal term in many countries. Frank Warmerdam placed
everything under the following MIT style license because he believed it is
effectively the same as public domain, allowing anyone to use the code as
they wish, including making proprietary derivatives.

Initial PROJ 4.3 public domain code was put as Frank Warmerdam as copyright
holder, but he didn't mean to imply he did the work. Essentially all work was
done by Gerald Evenden.

Copyright information can be found in source files.

 --------------

 Permission is hereby granted, free of charge, to any person obtaining a
 copy of this software and associated documentation files (the "Software"),
 to deal in the Software without restriction, including without limitation
 the rights to use, copy, modify, merge, publish, distribute, sublicense,
 and/or sell copies of the Software, and to permit persons to whom the
 Software is furnished to do so, subject to the following conditions:

 The above copyright notice and this permission notice shall be included
 in all copies or substantial portions of the Software.

 THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
 OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
 THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 DEALINGS IN THE SOFTWARE.
 ```
 
--

This project (in particular, the web interface) uses code from [wasm-proj](https://github.com/jjimenezshaw/wasm-proj), which is
distributed under these terms:

```
MIT License

Copyright (c) 2025 Javier Jimenez Shaw

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

--
 
This project uses code from [libtiff](https://gitlab.com/libtiff/libtiff),
which is distributed under these terms:

``` 
Copyright © 1988-1997 Sam Leffler
Copyright © 1991-1997 Silicon Graphics, Inc.

Permission to use, copy, modify, distribute, and sell this software and 
its documentation for any purpose is hereby granted without fee, provided
that (i) the above copyright notices and this permission notice appear in
all copies of the software and related documentation, and (ii) the names of
Sam Leffler and Silicon Graphics may not be used in any advertising or
publicity relating to the software without the specific, prior written
permission of Sam Leffler and Silicon Graphics.

THE SOFTWARE IS PROVIDED "AS-IS" AND WITHOUT WARRANTY OF ANY KIND, 
EXPRESS, IMPLIED OR OTHERWISE, INCLUDING WITHOUT LIMITATION, ANY 
WARRANTY OF MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE.

IN NO EVENT SHALL SAM LEFFLER OR SILICON GRAPHICS BE LIABLE FOR
ANY SPECIAL, INCIDENTAL, INDIRECT OR CONSEQUENTIAL DAMAGES OF ANY KIND,
OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS,
WHETHER OR NOT ADVISED OF THE POSSIBILITY OF DAMAGE, AND ON ANY THEORY OF 
LIABILITY, ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE 
OF THIS SOFTWARE.

```

--

This project bundles SQLite, which is in the public domain. See 
[SQLite Copyright](https://www.sqlite.org/copyright.html) for details.

--

This project uses [zlib](https://zlib.net), which is distributed under these terms:

```
Copyright (C) 1995-2024 Jean-loup Gailly and Mark Adler

This software is provided 'as-is', without any express or implied
warranty.  In no event will the authors be held liable for any damages
arising from the use of this software.

Permission is granted to anyone to use this software for any purpose,
including commercial applications, and to alter it and redistribute it
freely, subject to the following restrictions:

1. The origin of this software must not be misrepresented; you must not
   claim that you wrote the original software. If you use this software
   in a product, an acknowledgment in the product documentation would be
   appreciated but is not required.
2. Altered source versions must be plainly marked as such, and must not be
   misrepresented as being the original software.
3. This notice may not be removed or altered from any source distribution.
```


--

This project statically links [musl libc](https://musl.libc.org/) for Linux builds,
which is distributed under these terms:

```
Copyright © 2005-2020 Rich Felker, et al.

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject to
the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```

--

This project statically links MinGW-w64 runtime libraries for Windows builds.
The MinGW-w64 runtime is distributed under different permissive licenses:

```
MinGW-w64 runtime licensing
***************************

This program or library was built using MinGW-w64 and statically
linked against the MinGW-w64 runtime. Some parts of the runtime
are under licenses which require that the copyright and license
notices are included when distributing the code in binary form.
These notices are listed below.


========================
Overall copyright notice
========================

Copyright (c) 2009, 2010, 2011, 2012, 2013 by the mingw-w64 project

This license has been certified as open source. It has also been designated
as GPL compatible by the Free Software Foundation (FSF).

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

   1. Redistributions in source code must retain the accompanying copyright
      notice, this list of conditions, and the following disclaimer.
   2. Redistributions in binary form must reproduce the accompanying
      copyright notice, this list of conditions, and the following disclaimer
      in the documentation and/or other materials provided with the
      distribution.
   3. Names of the copyright holders must not be used to endorse or promote
      products derived from this software without prior written permission
      from the copyright holders.
   4. The right to distribute this software or to use it for any purpose does
      not give you the right to use Servicemarks (sm) or Trademarks (tm) of
      the copyright holders.  Use of them is covered by separate agreement
      with the copyright holders.
   5. If any files are modified, you must cause the modified files to carry
      prominent notices stating that you changed the files and the date of
      any change.

Disclaimer

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS ``AS IS'' AND ANY EXPRESSED
OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO
EVENT SHALL THE COPYRIGHT HOLDERS BE LIABLE FOR ANY DIRECT, INDIRECT,
INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA,
OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE,
EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

See [MinGW-w64 runtime licensing](https://sourceforge.net/p/mingw-w64/mingw-w64/ci/master/tree/COPYING.MinGW-w64-runtime/COPYING.MinGW-w64-runtime.txt)

--

### Data Files

This project includes PROJ data files (proj.db, proj.ini) that contain
coordinate system definitions from different sources (for example, EPSG).
These files are distributed under the same terms as PROJ (MIT/X11 style
license).
