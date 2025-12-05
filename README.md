# clj-proj

## Current Versions of Primary Packages

![NPM Version](https://img.shields.io/npm/v/proj-wasm)

![Clojars Version](https://img.shields.io/clojars/v/net.willcohen%2Fproj)

This project provides a native (or transpiled) version of PROJ for both the JVM
and JS ecosystems.

The goal of this project is to provide a long-missing component of geospatial
analysis for these platforms: a performant version of PROJ that can closely
follow upstream development.

This currently provides bindings to the JVM via Clojure using a [package
published to Clojars](https://clojars.org/net.willcohen/proj), and to pure
Javascript via [an ES6 module called
`proj-wasm`](https://www.npmjs.com/package/proj-wasm) published to NPM, which provides a clean
interface to an internal transpiled WASM module.


## EARLY DEVELOPMENT 

This project is in its initial phases, with partial functionality
built out, and incomplete testing. Feedback from
testers is welcome and encouraged.

Consider all APIs and structuring of this library to be an early
work-in-progress, subject to potentially substantial change while basic
functionality continues to be developed.

## How It Works

clj-proj provides PROJ functionality through a multi-implementation architecture that automatically selects the best available backend at runtime:

### Implementation Strategy

The library provides a unified API (`net.willcohen.proj.proj`) that automatically selects the best available backend at runtime:

1. **Native FFI (via JNA)** - Direct calls to compiled PROJ libraries
2. **GraalVM WebAssembly** - Runs emscripten-compiled PROJ in JVM
3. **JavaScript/WebAssembly** - Direct WASM for Node.js and browsers

During initialization, the library detects the environment and available backends:

```clojure
;; The implementation atom tracks which backend is active
(def implementation (atom nil))

;; Initialization automatically selects the best backend
(defn init! []
  #?(:clj 
     ;; JVM: Try native FFI first, fall back to GraalVM WASM
     (if @force-graal
       (do (wasm/load-wasm) (reset! implementation :graal))
       (try 
         (native/init-proj)
         (reset! implementation :ffi)
         (catch Exception e
           (wasm/load-wasm)
           (reset! implementation :graal))))
     :cljs
     ;; JavaScript: Detect Node.js or browser environment
     (do (wasm/init)
         (reset! implementation (if (node?) :node :browser)))))
```

### Runtime Dispatch System

The library uses a runtime dispatch architecture where all PROJ functions flow through a central dispatcher:

```clojure
;; Macros generate thin wrapper functions
(defn proj-create-crs-to-crs [opts]
  (dispatch-proj-fn :proj_create_crs_to_crs 
                    (get fndefs :proj_create_crs_to_crs) 
                    opts))

;; The dispatcher orchestrates the entire call flow
(defn dispatch-proj-fn [fn-key fn-def opts]
  (ensure-initialized!)
  (let [args (extract-args (:argtypes fn-def) opts)
        result (dispatch-to-platform-with-args fn-key fn-def args)]
    (process-return-value-with-tracking result fn-def)))
```

The dispatch system handles:
- **Argument extraction**: Converting Clojure maps to function arguments with defaults
- **Platform routing**: Sending calls to the appropriate backend implementation
- **Return processing**: Converting C types back to Clojure data structures
- **Context management**: Ensuring thread-safe access to PROJ contexts

### Resource Management

PROJ returns pointers that must be freed. The library automatically tracks and cleans up these resources:

- **JVM**: Uses `tech.v3.resource` for automatic cleanup during garbage collection
- **JavaScript**: Uses `resource-tracker` library for automatic cleanup

```clojure
;; When a function returns a pointer, it's automatically tracked internally
;; No manual cleanup needed - this happens behind the scenes:
(resource/track result-pointer
  {:dispose-fn (fn [] (proj-destroy pointer-address))
   :track-type :auto})  ; Cleaned up on GC
```

You never need to call `proj-destroy` or similar cleanup functions manually. All resources are automatically cleaned up when they go out of scope or during garbage collection.

### Context Management

PROJ uses contexts for thread safety and operation tracking. The library provides flexible context handling:

```clojure
;; Use an explicit context (stored in an atom)
(def ctx (context-create))
(proj-get-authorities-from-database {:context ctx})

;; Or let the library create a temporary context
(proj-get-authorities-from-database {})  ; Creates context internally
```

For functions that require atomic context access, the library uses the `cs` (context-swap) wrapper:
- Ensures thread-safe access by wrapping operations in an atom's `swap!`
- Tracks operation counts and results
- Handles platform-specific context requirements

Context atoms maintain state including:
- The native context pointer
- Operation counter for tracking calls
- Result storage for atomic operations

### Coordinate Transformation Implementation

The library provides efficient handling of both single and batch coordinate transformations:

```clojure
;; Single coordinate transformation
(trans-coord transformation [longitude latitude])

;; Batch transformation with coordinate arrays
(def coords (coord-array 1000 2))  ; 1000 2D coordinates
(set-coords! coords [[lon1 lat1] [lon2 lat2] ...])
(proj-trans-array {:P transformation :coord coords :n 1000})
```

Coordinate arrays are implemented differently per platform:
- **FFI**: Uses `dtype-next` tensors for zero-copy native memory access
- **GraalVM**: Allocates memory in the WASM heap
- **ClojureScript**: Direct typed array manipulation

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
The test framework ensures consistent behavior across all implementations:
```clojure
(defmacro with-each-implementation [& body]
  ;; Runs the same test against FFI, GraalVM, and ClojureScript
  ...)
```

### Flexible API

The library provides a developer-friendly API with several conveniences:

- **Parameter naming flexibility**: Use either underscores or hyphens
  ```clojure
  ;; Both work identically:
  (proj-create-crs-to-crs {:source_crs "EPSG:4326" :target_crs "EPSG:2249"})
  (proj-create-crs-to-crs {:source-crs "EPSG:4326" :target-crs "EPSG:2249"})
  ```

- **Optional parameters with defaults**: Functions provide sensible defaults
  ```clojure
  ;; Context is optional - library creates one if needed
  (proj-get-authorities-from-database {})
  
  ;; Or provide your own
  (proj-get-authorities-from-database {:context my-ctx})
  ```

- **Consistent error handling**: All platforms handle errors uniformly
  - C++ exceptions from WASM are caught and converted
  - Native errors are wrapped in Clojure exceptions
  - Helpful error messages across all backends

## Platform-Specific Details

### JVM (Java / Clojure)

The JVM implementation supports two backends:

1. **Native FFI (Preferred)** - Available on supported platforms
2. **GraalVM WebAssembly (Fallback)** - For platforms without native libraries

Currently supported platforms (native):
- macOS/darwin Apple Silicon (arm64)
- Linux x64 and arm64
- Windows x64

Not yet implemented:
- macOS/darwin Intel (x86_64) - Not built/tested
- Windows ARM64 - Cross-compiler not available in nixpkgs

### JDK 11+ with native library

On platforms with a native precompiled PROJ available, this library utilizes
JNA. This is the preferred option. Once Panama stabilizes, this library may use
that instead when present.

#### How Native FFI Works

The native implementation:
1. Extracts platform-specific libraries from resources to a temp directory
2. Configures JNA to load from that directory
3. Uses `dtype-next` for efficient native interop and memory management

The library includes pre-compiled PROJ libraries for each platform in `resources/{platform}/`. At runtime, it detects the OS and architecture, then loads the appropriate libraries.

#### Usage

On a computer where the native library was built:
```clojure
(require '[net.willcohen.proj.proj :as proj])

;; Initialization happens automatically on first use in Clojure/JVM
;; For explicit initialization, you can call:
;; (proj/init!)  ; Primary function
;; (proj/init)   ; Convenience alias (same as init!)

;; Create a coordinate transformation
(def ctx (proj/context-create))
(def transformer (proj/proj-create-crs-to-crs {:context ctx
                                               :source-crs "EPSG:4326"
                                               :target-crs "EPSG:2249"}))

;; Transform a single coordinate 
(def coords (proj/coord-array 1))
;; EPSG:4326 uses lat/lon order, not lon/lat!
(proj/set-coords! coords [[42.3603222 -71.0579667]]) ; Boston City Hall (lat, lon)
(proj/proj-trans-array {:P transformer :coord coords :n 1})
;; coords now contains transformed coordinates in EPSG:2249 (MA State Plane)

;; Query available authorities
(proj/proj-get-authorities-from-database)
;; => ["EPSG" "ESRI" "PROJ" "OGC" ...]

;; No manual cleanup needed! Resources are automatically tracked and 
;; cleaned up when they go out of scope or during garbage collection
```

### Java API

A Java wrapper class (`net.willcohen.proj.PROJ`) provides idiomatic Java access to the library:

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
// => ["EPSG", "ESRI", "PROJ", "OGC", ...]

// Create transformation from CRS objects (for advanced use)
Object sourceCrs = PROJ.createFromDatabase(ctx, "EPSG", "4326");
Object targetCrs = PROJ.createFromDatabase(ctx, "EPSG", "2249");
Object transformFromPj = PROJ.createCrsToCrsFromPj(ctx, sourceCrs, targetCrs);

// No manual cleanup needed - resources are automatically tracked!
```

The Java API mirrors the Clojure API and supports:
- All initialization and backend control methods (`init()`, `forceGraal()`, `forceFfi()`)
- Context management (`contextCreate()`, `isContext()`)
- CRS transformations (`createCrsToCrs()`, `createCrsToCrsFromPj()`, `createFromDatabase()`)
- Coordinate arrays (`coordArray()`, `setCoords()`, `transArray()`)
- Database queries (`getAuthoritiesFromDatabase()`, `getCodesFromDatabase()`)
- Direction constants (`PJ_FWD`, `PJ_INV`, `PJ_IDENT`)

### JDK 21+ with GraalVM WebAssembly

On platforms where no native library is available, this library falls back to
running the WebAssembly transpiled version of PROJ through GraalVM's WebAssembly support.

Users needing this transpiled PROJ must use at least JDK 21 due to GraalVM's requirements
and should enable JVMCI to improve performance.

#### How GraalVM Implementation Works

When native libraries aren't available, the GraalVM implementation:
1. Creates a polyglot context with JavaScript and WebAssembly support
2. Loads the emscripten-compiled PROJ module with embedded resources
3. Manages type conversion between JVM and JavaScript using ProxyArray
4. Handles C++ exceptions from WASM code gracefully

The main challenge is initialization performance - loading the WASM binary (3.6MB) and PROJ database (9.4MB) takes several seconds.

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
(proj/set-coords! coords [[42.3603222 -71.0579667]]) ; Boston City Hall
(proj/proj-trans-array {:P transformer :coord coords :n 1})
;; coords now contains transformed coordinates

;; No manual cleanup needed - resources are automatically managed!
```

Note: GraalVM initialization takes 5-7 seconds as it loads the WASM module. You may see Truffle/GraalVM diagnostic output during initialization.

### JavaScript / ClojureScript

The JavaScript implementation uses emscripten-compiled PROJ with several key characteristics:

- **Direct WASM execution**: Runs compiled PROJ directly in JavaScript environments
- **Embedded resources**: PROJ database and configuration files built into the WASM module
- **Cherry-cljs compilation**: ClojureScript code compiles to clean ES6 modules

#### Environment-Specific Behavior

The library automatically detects and adapts to different JavaScript environments:

- **Node.js**: Direct WASM module loading
- **Browser**: WASM loading with proper CORS headers required
- **Environment detection**: Automatic at initialization

#### Usage

For Node.js, create `index.mjs`:
```javascript
import * as proj from "proj-wasm";

// Initialize PROJ (required before any operations in JavaScript)
await proj.init();  // Convenience alias for init! (also available as init_BANG_)

// Create a context and transformation
const context = proj.context_create();
const transformer = proj.proj_create_crs_to_crs({
  source_crs: "EPSG:4326",
  target_crs: "EPSG:2249",
  context: context
});

// Transform coordinates (EPSG:4326 uses lat/lon order)
const coords = proj.coord_array(1);
proj.set_coords_BANG_(coords, [[42.3603222, -71.0579667]]); // Boston City Hall (lat, lon)
proj.proj_trans_array({ P: transformer, coord: coords, n: 1 });

// Get the transformed coordinates
console.log("Transformed:", coords);

// Resources are automatically cleaned up - no manual cleanup needed!
// The resource-tracker library handles cleanup when objects go out of scope
```

For browsers, the same code works but ensure CORS headers are properly configured for WASM file loading.

```bash
$ node index.mjs
# Transformed coordinates will be displayed
```

# clj-proj Build Guide

## Prerequisites

**Docker/Podman users**: 
- Install Docker or Podman
- **Resource requirements**: 150GB disk, 8GB RAM  
- **Podman setup**: `podman machine init --disk-size 150 --memory 8192`

**Babashka + Nix users**:
- Install [Nix](https://nixos.org/download.html) and [direnv](https://direnv.net/)
- **One-time setup**: `direnv allow`

## Building

### Quick Reference

```bash
bb tasks          # List all available commands
bb build --help   # Show build options
bb clean --help   # Show clean options
```

**Docker/Podman users**: First build the development container:
```bash
docker build --target dev -t clj-proj:dev .
# or: podman build --target dev -t clj-proj:dev .
```

**Rebuild the container when:**
- Containerfile changes
- flake.nix dependencies change  
- After pulling latest changes that modify build environment
- Add `--no-cache` flag to force complete rebuild if needed

### Common Build Tasks

**Native libraries (current platform):**
```bash
# Babashka + Nix
bb build --native

# Docker/Podman alternative  
docker run --rm -v $(pwd):/workspace clj-proj:dev bb build --native
```

**WebAssembly build:**
```bash
# Babashka + Nix  
bb build --wasm

# Docker/Podman alternative
docker run --rm -v $(pwd):/workspace clj-proj:dev bb build --wasm
```

**Cross-platform builds:**
```bash
# Babashka + Nix (uses Docker internally)
bb build --cross-platform linux/amd64     # Working
bb build --cross-platform linux/aarch64   # Working
bb build --cross-platform windows/amd64   # Working
bb build --cross                          # Build all default platforms

# Docker/Podman direct
docker build --platform linux/amd64 --target export --output type=local,dest=./artifacts .
docker build --platform linux/arm64 --target export --output type=local,dest=./artifacts .
```

**Complete build + test:**
```bash
# Babashka + Nix
bb test-run       # Builds everything, runs all tests

# Docker/Podman
docker build --target test-all .
```

### Development Setup

**Babashka + Nix:**
```bash
direnv allow      # One-time setup
bb dev            # Rich REPL with Portal
bb demo           # Browser demo at localhost:8080
```

**Docker/Podman:**
```bash
# First-time setup: build the development container
docker build --target dev -t clj-proj:dev .

# Then start interactive development environment
docker run -it --rm -v $(pwd):/workspace -p 7888:7888 -p 8080:8080 clj-proj:dev
# Inside container: bb dev, bb demo, etc.
```

### Packaging

**JVM (JAR file):**
```bash
# Babashka + Nix
bb jar

# Docker/Podman  
docker run --rm -v $(pwd):/workspace clj-proj:dev bb jar
```

**JavaScript (ES6 module):**
```bash
# Babashka + Nix
bb cherry

# Docker/Podman
docker run --rm -v $(pwd):/workspace clj-proj:dev bb cherry
```

### Build Process Overview

1. **Native builds** compile PROJ + dependencies (SQLite, LibTIFF) for the host platform
   - **Output**: `resources/{platform}/` (e.g., `resources/darwin-aarch64/`)
   - **Linux**: Fully static linking
   - **Windows**: Static linking (still has runtime dependency issues, being fixed)

2. **WASM builds** use emscripten to compile PROJ into WebAssembly
   - **Output**: `resources/wasm/` and `src/cljc/net/willcohen/proj/`
   - **Requirements**: emscripten tools in PATH (automatically provided in containers)

3. **Cross-platform builds** use Docker containers with Nix for reproducible builds
   - **Requirements**: Docker or Podman installed  
   - **Resource requirements**: 150GB disk, 8GB RAM
   - **Podman setup**: `podman machine init --disk-size 150 --memory 8192`

## Testing

**Run all tests:**
```bash
# Babashka + Nix  
bb test:all

# Docker/Podman
docker build --target test-all .
```

**Test specific implementations:**
```bash
# Native FFI
bb test:ffi                    # Babashka + Nix
docker run --rm -v $(pwd):/workspace clj-proj:dev bb test:ffi

# GraalVM WebAssembly  
bb test:graal                  # Babashka + Nix
docker run --rm -v $(pwd):/workspace clj-proj:dev bb test:graal

# JavaScript/Node.js
bb test:node                   # Babashka + Nix
docker run --rm -v $(pwd):/workspace clj-proj:dev bb test:node

# Browser integration
bb test:playwright             # Babashka + Nix (requires display)
# (Not available in headless containers)
```

**Test packaged artifacts:**
```bash  
# JAR as downstream dependency
bb test:jar                    # Babashka + Nix
docker run --rm -v $(pwd):/workspace clj-proj:dev bb test:jar

# npm package as downstream dependency
bb test:npm                    # Babashka + Nix  
docker run --rm -v $(pwd):/workspace clj-proj:dev bb test:npm

# Linux cross-platform testing
bb test:linux                  # Uses Docker internally
```

The test framework runs identical tests against all implementations, ensuring consistent behavior across platforms.

## Architecture Notes

### File Organization

```
clj-proj/
├── src/
│   ├── clj/net/willcohen/proj/impl/    # JVM-specific implementations
│   │   ├── native.clj                  # JNA/FFI implementation
│   │   ├── graal.clj                   # GraalVM WASM implementation
│   │   └── struct.clj                  # Native struct definitions
│   └── cljc/net/willcohen/proj/        # Cross-platform core
│       ├── proj.cljc                   # Main public API
│       ├── wasm.cljc                   # WASM loader/interface
│       ├── spec.cljc                   # clojure.spec definitions
│       ├── fndefs.cljc                 # PROJ function definitions
│       ├── macros.clj                  # JVM macro definitions
│       ├── macros.cljs                 # ClojureScript macros
│       └── *.mjs                       # Generated JavaScript modules
├── resources/
│   ├── {platform}/                     # Native libraries per platform
│   ├── wasm/                           # WASM build artifacts
│   │   ├── proj-emscripten.js         # WASM JavaScript glue
│   │   ├── proj-emscripten.wasm       # WASM binary
│   │   └── proj-loader.js             # WASM loader
│   ├── proj.db                         # PROJ database
│   └── proj.ini                        # PROJ configuration
├── deps.edn                            # Clojure dependencies
├── bb.edn                              # Babashka build tasks
├── build.clj                           # Clojure build configuration
└── flake.nix                           # Nix development environment
```

### Key Implementation Files

- `src/cljc/net/willcohen/proj/proj.cljc` - Main public API and dispatch logic
- `src/cljc/net/willcohen/proj/fndefs.cljc` - PROJ function definitions and constants
- `src/cljc/net/willcohen/proj/macros.clj[s]` - Code generation macros for multi-platform support
- `src/cljc/net/willcohen/proj/wasm.cljc` - WebAssembly loader and interface
- `src/clj/net/willcohen/proj/impl/native.clj` - JNA/FFI implementation for native libraries
- `src/clj/net/willcohen/proj/impl/graal.clj` - GraalVM WebAssembly implementation
- `src/clj/net/willcohen/proj/impl/struct.clj` - Native struct definitions for FFI

### Performance Considerations

- **Initialization**: Native FFI is near-instant, GraalVM takes 5-7 seconds
- **Transformations**: Native is fastest, followed by direct WASM, then GraalVM
- **Memory**: Coordinate arrays use platform-specific optimizations

## Development

### 1. Nix Flake and Direnv

To avoid an ever evolving set of dependencies where specific versions can cause errors with the build process,
a nix flake has been provided that will work with [direnv](https://direnv.net). (See .envrc's `use flake`).

This should allow for a development environment that is consistent and known to work.

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

It may be helpful to use an editor with Clojure functionality: Emacs with CIDER, VSCode with Calva and Portal extensions, IDEA and Cursive.

The `bb nrepl` task starts an nREPL server on port 7888 with Portal included for data visualization and debugging.

### 3. Demo Server

Run the browser demo locally:

```bash
bb demo  # Serves at http://localhost:8080/docs/
```

Navigate to http://localhost:8080/docs/ to see the library in action.

### 4. Documentation

Generate API documentation (work in progress):

```bash
bb quickdoc  # Generates docs from source
```

### 5. Other Utilities

```bash
# Download PROJ grid files from CDN (work in progress)
bb download-grids
```

### 6. Task Reference

Run `bb tasks` for complete list. Key commands:

**Build & Package:**
- `bb build --help` - Show build options (native/wasm/cross)
- `bb build:all` - Build native + WASM artifacts  
- `bb jar` - Build JAR file for JVM
- `bb cherry` - Build JavaScript ES6 module
- `bb pom` - Generate/update pom.xml

**Testing:**
- `bb test:all` - Run all tests
- `bb test:ffi` / `bb test:node` / `bb test:graal` - Test specific implementations
- `bb test-run` - Complete build + test cycle

**Development:**
- `bb dev` - Rich REPL with Portal
- `bb nrepl` - nREPL server (port 7888)
- `bb demo` - Browser demo (localhost:8080)

**Utilities:**
- `bb clean --help` - Show clean options
- `bb jar-contents` - List files in JAR
- `bb npm-contents` - List files in npm package
- `bb proj:clone --help` - Local PROJ development

### 7. Local PROJ Development Workflow

For developers working on the PROJ C library itself, clj-proj provides a workflow to test local PROJ changes against the bindings before submitting upstream.

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
- `--local-proj` - Use `vendor/PROJ` instead of released PROJ version (works with any build task)

**Directory Structure:**
```
clj-proj/
├── vendor/           # gitignored - your local development area
│   └── PROJ/         # cloned OSGeo/PROJ repository
├── bb.edn
└── ...
```

This workflow enables tight integration testing between PROJ C library development and clj-proj bindings, catching API changes and build issues early in the development cycle.

## License

```
Copyright (c) 2024, 2025 Will Cohen

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
distributed under the following terms:

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
 
This project uses code from [libtiff](https://gitlab.com/libtiff/libtiff),
which distributed under the following terms:

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

This project uses [zlib](https://zlib.net), which is distributed under the following terms:

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
which is distributed under the following terms:

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
The MinGW-w64 runtime is distributed under various permissive licenses:

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

This project includes PROJ data files (proj.db, proj.ini) which contain
coordinate system definitions from various sources including EPSG. These
are distributed under the same terms as PROJ itself (MIT/X11 style license).
