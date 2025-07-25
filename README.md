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

#### Platform-Specific Optimization
The library includes platform-specific optimizations:
- **macOS**: Uses `install_name_tool` to fix library paths
- **Native memory**: Leverages `dtype-next` for efficient array operations

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

Planned native platform support, currently GraalWasm fallback only:
- Linux x64 and arm64 (build still failing during cross-compilation)

Possible native platform support, currently GraalWasm fallback only:
- macOS/darwin Intel (x86_64)
- Windows x64 and arm64

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

Idiomatic Java API is not yet present, but is possible.

### JDK 23+ with GraalVM WebAssembly

On platforms where no native library is available, this library falls back to
running the WebAssembly transpiled version of PROJ through GraalVM's WebAssembly support.

Users needing this transpiled PROJ must use at least JDK 23 due to GraalVM's requirements
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

## Building

The project uses Babashka (bb) tasks for all build, test, and development operations:

```bash
bb tasks  # List all available tasks with descriptions

# For tasks with options (build, clean), use --help to see details:
bb build --help  # Shows build options
bb clean --help  # Shows clean options
```

### Quick Start

```bash
# Complete test run (builds everything, runs all tests)
bb test-run

# Build only what you need
bb build:all      # Build native and WASM artifacts
bb jar           # Create JAR file
bb cherry        # Build JavaScript/ES6 module
```

### Build Commands

```bash
# Build native libraries for current platform
bb build --native
bb build -n        # Short alias

# Build WebAssembly version
bb build --wasm
bb build -w        # Short alias

# Build with debug output
bb build --native --debug
bb build --wasm --debug

# Build all artifacts (native and WASM)
bb build:all

# Cross-compile for other platforms (requires Docker/Podman)
# If on Mac, podman will need a machine initialized with
# `podman machine init`, and will need at least 4GB of memory allocated:
# `podman machine set --memory=4096
bb build --cross-platform linux/amd64 # not quite working
bb build --cross-platform linux/aarch64 # not quite working
bb build --cross-platform windows/amd64 # windows may need rethinking
bb build --cross   # Build for all default platforms

# Clean build artifacts
bb clean           # Clean everything
bb clean --native  # Clean only native artifacts
bb clean --wasm    # Clean only WASM artifacts
bb clean --resources  # Clean proj.db, proj.ini
```

### Packaging Commands

```bash
# Build JAR file
bb jar

# Update pom.xml
bb pom

# Build JavaScript ES6 module
bb cherry         # Compiles ClojureScript and bundles with esbuild
bb update-macro-fn-keys  # Update macros (runs automatically with cherry)

# Validate package contents
bb jar-contents   # List all files in the JAR
bb npm-contents   # List all files that would be in npm package
```

### Build Process Details

1. **Native builds** compile PROJ with its dependencies (SQLite, LibTIFF) for the host platform
   - Artifacts go to `resources/{platform}/` (e.g., `resources/darwin-aarch64/`)
   - Automatically detects host OS and architecture
   - Builds are cached - use `bb clean` to force rebuild

2. **WASM builds** use emscripten to compile PROJ into WebAssembly with JavaScript glue
   - Requires emscripten tools (`emcc`, `emcmake`, `emmake`) in PATH
   - Artifacts go to `resources/wasm/` AND `src/cljc/net/willcohen/proj/`
   - The `cherry` task builds the JavaScript ES6 wrapper module

3. **Cross-platform builds** use Docker containers with Nix for reproducible builds
   - Requires Docker or Podman installed
   - Uses NixOS flake environments for consistent toolchains
   - Windows build appear possible but not yet functional

The build system handles:
- Downloading and compiling dependencies (SQLite, LibTIFF, zlib)
- Platform-specific configuration and library naming
- Library path management (e.g., `@loader_path` on macOS)
- Artifact organization into appropriate directories

## Testing

Run tests individually or all at once:

```bash
# Run all tests (includes unit, integration, and downstream tests)
bb test:all

# Test native FFI implementation
bb test:ffi

# Test GraalVM WebAssembly implementation  
bb test:graal

# Test JavaScript/Node.js implementation
bb test:node

# Run browser integration tests
bb test:playwright

# Test JAR as a downstream Clojure dependency
bb test:jar

# Test npm package as a downstream JavaScript dependency  
bb test:npm

# Test on Linux platforms via Docker
bb test:linux
```

The test framework uses a macro to run the same tests against each implementation, ensuring consistent behavior across platforms. The downstream tests (`test:jar` and `test:npm`) verify that the published artifacts work correctly when consumed by real projects.

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

### 6. Complete Task Reference

Run `bb tasks` to see all available tasks. Here's a quick reference:

**Build & Package Tasks:**
- `build` - Build artifacts with options (use `--help` for details)
- `build:all` - Build both native and WASM artifacts
- `jar` - Build JAR file for JVM distribution
- `pom` - Generate/update pom.xml
- `cherry` - Compile ClojureScript and bundle with esbuild
- `update-macro-fn-keys` - Update macros (runs automatically with cherry)

**Test Tasks:**
- `test:all` - Run all test suites
- `test:ffi` - Test native FFI implementation
- `test:graal` - Test GraalVM WASM implementation
- `test:node` - Test JavaScript/Node.js implementation
- `test:cljs` - Run ClojureScript tests in Node.js
- `test:playwright` - Run browser integration tests
- `test:jar` - Test JAR as downstream dependency
- `test:npm` - Test npm package as downstream dependency
- `test:linux` - Test on Linux platforms via Docker
- `test-run` - Complete build and test cycle

**Development Tasks:**
- `dev` - Rich REPL with Portal and development tools
- `nrepl` - Basic nREPL server on port 7888
- `demo` - Browser demo at http://localhost:8080/docs/

**Utility Tasks:**
- `clean` - Clean artifacts with options (use `--help` for details)
- `jar-contents` - List all files in the JAR
- `npm-contents` - List files that would be in npm package
- `download-grids` - Download PROJ grid files (work in progress)
- `quickdoc` - Generate API documentation

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
