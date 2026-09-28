# Change Log
This file documents notable changes to this project. This change log uses the
conventions of [keepachangelog.com](http://keepachangelog.com/).

## [Unreleased]

### Added
- THIRD-PARTY-NOTICES.md holds the license notices of the code and data in
  the jar and the npm package, with the Emscripten runtime of the wasm.
- `proj-context-set-search-paths`, `proj-context-get-user-writable-directory`,
  `proj-assign-context` and `proj-errno-reset`.

### Changed
- clj-native 0.0.2 (`net.willcohen/native`, npm `ffi-wasm`). On the GraalVM
  backend, a PROJ exception makes the call throw.
- The Linux libs load on glibc 2.28 and later. A musl host, for example
  Alpine, gets a separate `linux-<arch>-musl` lib. The Linux and Windows libs
  contain the LLVM C++ runtime, and a host needs no libstdc++ or libgcc.
- Dependencies: GraalVM 25.3.4.1 (js, polyglot, wasm and truffle-runtime),
  Clojure 1.12.6, tools.logging 1.3.1 and dtype-next 11.026. The optimizing
  runtime of the GraalVM backend needs a GraalVM CE 25.3.4.1 JVM.
- squint-cljs 0.14.210.
- An `options` argument of `proj-identify`, `proj-create-from-name` and six
  other functions takes nil or a vector of strings.
- `proj-string-destroy` takes a pointer.

### Removed
- The `xhr2` dependency of proj-wasm. ffi-wasm 0.0.2 does not use it.
- The `shutdown` method of the proj-wasm worker handler. To stop the fetch
  worker, terminate the pool.
- `proj-coord`, `proj-xy-dist` and `proj-get-suggested-operation`. PROJ passes
  their `PJ_COORD` by value, but the bindings passed a pointer.

### Fixed
- The jar contains the clj-kondo exports. The 0.1.0-alpha9 and 0.1.0-alpha10
  jars did not.
- SQLite in the wasm, and SQLite and libtiff in the Linux libs, built with no
  optimization. They now build with `-O2`.
- GraalVM backend: when a second wasm library such as clj-gdal was loaded,
  calls with a coordinate array or a string list threw.
- The Linux libs of 0.1.0-alpha8 to 0.1.0-alpha10 did not load on older glibc.
  linux-amd64 needed glibc 2.38 and did not load on Debian 12 or Ubuntu 22.04.
  linux-aarch64, a musl build, needed glibc 2.33, and on a glibc host its
  mutexes had the wrong size. Both libs had a run path into the build
  directories.
- The macOS lib had a run path into the build directory.
- FFI backend: a string-list function that returned NULL, for example
  `proj-get-codes-from-database` with `:type 0`, threw "Pointer value is
  zero!". It now returns an empty list.
- GraalVM backend: each call with a string-list argument, for example
  `proj-as-wkt` with `:options`, did not free its block of wasm memory.
- ClojureScript: when `proj-create-crs-to-crs` failed or returned no PJ, for
  example on an invalid CRS, it left a PROJ context on its worker.
- ClojureScript: the pool tracked contexts by an id that repeats across
  workers. It could count a context on the wrong worker, and then send new
  contexts to a worker that already had more.
- JVM: a PJ or a context that a call returned stayed in native memory after
  it became unreachable, until an explicit release or the end of the process.
- The jar contains `META-INF/LICENSE` and `META-INF/THIRD-PARTY-NOTICES.md`,
  and the npm package contains `dist/THIRD-PARTY-NOTICES.md`. The jar had no
  license text.
- JVM: when two threads used one context at the same time, a PROJ call could
  run two times, and the result of the first run leaked.
- The list of a string-list function, for example
  `proj-get-authorities-from-database`, was never freed.
- FFI backend: the eight functions with an `options` argument, for example
  `proj-suggests-code-for`, threw "Pointer value is zero!" when the call gave
  no options.
- `proj-suggests-code-for` did not free its result. `proj-string-destroy` freed
  a copy of its argument, not the string.
- `proj-get-units-from-database` with no category, and
  `PROJ.getUnitsFromDatabase` with a null category, returned no units. They
  now list the units of every category.
- `context-database-path` always returned nil. It now returns the path of the
  database that the context uses.
- GraalVM backend: a pool worker whose PROJ boot failed kept its polyglot
  Context open.
- ClojureScript: `proj-destroy` and `proj-context-destroy` freed a tracked PJ or
  context, but did not release its tracking. A later release, for example
  `Symbol.dispose`, freed it again. `proj-context-destroy` of a tracked context
  now returns at once, and the worker frees the context after its PJs.
- ClojureScript: a call with PJ arguments on two workers makes a copy of each
  argument on one worker. The call did not free the copies, and wrote them
  into the options of the caller.
- GraalVM backend and ClojureScript: the `get_header` network callback did not
  free its strings, three for each grid file that PROJ opened.
- ClojureScript: when `init` failed, the caller got the error, but Node.js also
  stopped with `ERR_UNHANDLED_REJECTION`.
- ClojureScript: `context-set-database-path` and `context-set-enable-network`
  sent the call for a context on worker 1 or higher to worker 0, with the
  pointer of the other worker.
- ClojureScript: PROJ got NULL for an `options` argument, for example
  `MULTILINE=NO` of `proj-as-wkt`, and ignored the options.
- Browser: when the fetch of `proj.db` or `proj.ini` failed, the worker used
  the error page as the file. Now `init` rejects with the HTTP status.
- GraalVM backend: `coord->coord-array` of `[x y]` did not set z and t, which
  kept the old values of the heap. Now they are 0.
- PROJ did not read the bundled `proj.ini`, because no search path held its
  directory. Each context now has search paths.
- The errno check read the errno of the context after a NULL result. Because
  PROJ keeps errno after a failure, the check threw the code of an earlier
  call, for example the ENOENT of a new context. The check now clears the
  errno before the call. `proj-as-wkt` of a PJ from `proj-create-crs-to-crs`
  now gives no WKT and no error, as in PROJ.
- ClojureScript: `proj-coordoperation-get-towgs84-values` gave no values. The
  worker passed the output pointer as the last argument, not as the third.

## [0.1.0-alpha10] - 2026-09-15

### Changed
- Coordinate batches cross dispatch and the worker boundary as `Float64Array`
  instead of boxed plain arrays; `set-coord-array` fills the buffer in place
- squint-cljs moves from 0.14.206 to 0.14.208; the browser test importmaps
  load the same version
- PROJ 9.9.0 (was 9.8.1)

### Added

- Binding for `proj_crs_is_dynamic`, new in PROJ 9.9.
  `proj_create_linear_3D_affine_parametric_conversion` is temporarily
  unbound pending a different way to pass arguments: its 38 exceed the
  20-parameter cap on a dt-ffi wrapper

## [0.1.0-alpha9] - 2026-08-24

### Added
- `handler.cljc`: per-worker init and destroy for the clj-native workload-pool `:proj` handler, plus `proj/transform-batch`, which reuses a cached transformer against the per-worker PROJ Context
- New `init!` options: `:pool` adopts a caller-supplied worker-router pool, and `:debug-level` and `:debug-categories` select worker logging. `:max-live-ctxs` (default 128) and `:min-age-ms` (default 100) bound the live-context set, and `getPoolDetail()` reports why the pool can or cannot evict each entry
- Exported clj-kondo hooks under `resources/clj-kondo.exports`, so consumers resolve the generated PROJ surface. `bb kondo:fndefs` regenerates the hook copy of fndefs, and `bb lint` fails when it is stale
- Test suites for the LRU helpers, the worker-router handler, resource tracking, the FFI-to-GraalVM fallback, the Truffle runtime, and the Java-to-Clojure binding

### Changed
- CLJS compiler: cherry → squint. `macros.clj` and `macros.cljs` collapse into one `macros.cljc`
- JS workers now run on worker-router (`npm:@wcohen/worker-router@^0.0.2`). clj-native's generator makes `proj-handler.mjs` from the hand-written `proj-handler-overrides.mjs`, and worker-router owns the call protocol. This replaces the hand-rolled `proj-worker.mjs`, `fetch-worker.mjs`, and postMessage ID table
- Native FFI now goes through dtype-next on the JDK Panama FFM backend. clj-native dropped JNA
- Platform detection, library extraction, dispatch, and WASM glue move to clj-native (Clojars `net.willcohen/native` 0.0.1, npm `ffi-wasm` 0.0.1). `native.clj` and `wasm.cljc` keep only the PROJ-specific parts, and the Nix flake takes clj-native as an input
- One single-threaded WASM build serves the browser, Node.js, and GraalVM. Browsers need no Cross-Origin Isolation headers, so the demo no longer ships `coi-serviceworker.js`
- Minimum JDK is 25. GraalVM artifacts move to 25.2.4 with the optimizing `truffle-runtime` added, dtype-next to 11.025, and CI runs GraalVM CE 25.2.4 to match the artifact pins
- The Clojars jar no longer ships the worker JS or the WASM debug map. The npm package lists its shipped files explicitly and adds a `./proj-handler` export
- Slimmed `deps.edn`: dropped the unused ClojureScript dependency, redundant classpath roots, the inert top-level `:jvm-opts`, and the diverged `:nrepl` alias (use `bb nrepl`). Scoped `:test` to `test/cljc` and pinned `deps-deploy`
- The version has one source: `def version` in `build.clj`. The bb tasks read it, and `bb version-bump` rewrites the other files from it
- CI adds a lint job that runs `bb lint` on a fresh clone. All actions move to their current major versions, and Node.js moves from 20 to 26
- The Node.js suite moved from `test/js/proj.test.mjs` to cljs.test files under `test/cljc`. One `proj_test.cljc` now runs on the JVM and under squint plus Node

### Removed
- `Containerfile`. Cross builds run in the container that clj-native supplies, and `bb test:linux` pulls a public image
- `spec.cljc`. The clojure.spec definitions had no callers
- `test-downstream-user/`. Nothing invoked it, and `bb test:jar` runs the downstream checks inline
- `src/c/proj_network_stubs.c`. The GraalVM network callbacks now install through `Module.addFunction`, so the WASM build carries no C stubs
- ClojureScript code that read the Emscripten module from the main-thread `p` atom, which the worker pool never sets: `get-value`, `pointer->string`, and the CLJS branches of `string-array-pointer->strs`, `wasm/malloc`, `wasm/heapf64`, `wasm/alloc-coord-array`, and `wasm/set-coord-array` (the wasm functions are now JVM-only)
- Unused vars: `dispatch-to-platform-with-args`, a pure passthrough to `call-native`, and the `impl.native` aliases `get-os`, `get-arch`, `init-ffi!`, and `reset-proj`

### Fixed
- CLJS context-bearing PROJ calls were misrouted: the context predicate misclassified the argtype shape, and cross-worker calls trapped inside `pj_vlog`
- CLJS `extract-args` silently dropped `skip-first?`. This caused off-by-one ccall arg counts on opt-in call sites
- JVM struct-list dispatch (`proj_get_units_from_database`, `proj_get_celestial_body_list_from_database`, `proj_get_crs_info_list_from_database`) silently returned null because the special-argtype detector mishandled keyword first-elements
- ClojureScript entered `init-proj` again on every native call: `wasm/ensure-proj-initialized!` tested the `p` atom, which is never set when each worker owns its own Emscripten module. It now tests the pool
- FFI network callbacks were registered again for each PROJ context, and only the newest set stayed GC-reachable. One registration now serves every context
- The FFI log callback used a check-then-`reset!` that raced concurrent context creation. It now registers through `swap!`
- The FFI `get_header` callback kept its returned C string in one shared slot, which a concurrent grid fetch could drop before PROJ read it. The slot is now per thread
- ClojureScript `attach-context-to-result` returned nil for a result that is not a JS object, which dropped the value. It now passes such a result through, like the JVM branch
- ClojureScript context destroy-fns were declared beside the object they dispose, so the FinalizationRegistry heldValue pinned its own target and contexts never finalized. The builder is now top-level, as on the PJ path

## [0.1.0-alpha8] - 2026-04-14

### Added
- GitHub Actions CI: build native (linux-amd64, linux-aarch64, darwin-aarch64, windows-amd64 cross-compile), WASM, and run tests
- Struct-aware return type system: C struct array functions auto-generated from `:struct-fields` metadata in fndefs
- Out-param dispatch system: C functions with output parameters (`out_*`) automatically allocate, call, read, and free heap memory. Callers pass only input args and receive typed maps. 11 functions: `proj_get_area_of_use`, `proj_get_area_of_use_ex`, `proj_cs_get_axis_info`, `proj_ellipsoid_get_parameters`, `proj_prime_meridian_get_parameters`, `proj_coordoperation_get_method_info`, `proj_coordoperation_get_param`, `proj_coordoperation_get_grid_used`, `proj_uom_get_info_from_database`, `proj_grid_get_info_from_database`, `proj_coordoperation_get_towgs84_values`
- Java API: CRS decomposition (`getEllipsoid`, `getPrimeMeridian`, `crsGetCoordinateSystem`, `crsGetCoordoperation`) and all out-param functions
- `proj_get_units_from_database`
- `proj_get_celestial_body_list_from_database`
- `proj_create`: raw binding for PROJ strings, WKT, and pipeline definitions (for example, `+proj=pipeline +step +proj=robin`)
- Test coverage for object inspection, CRS decomposition, operation factory, `create-from-wkt`, `set-coord!`, `set-col!`, out-param functions across all platforms (CLJ FFI, GraalVM, Node.js, Playwright, Java)
- `bb deploy` and `bb deploy:dry-run` tasks for release workflow

### Removed
- `download-grids` task (was WIP, never completed)

### Changed
- BREAKING: Return key casing is now idiomatic per platform. Clojure: kebab-case keywords (`:west-lon-degree`). Java: camelCase strings (`"westLonDegree"`). JS camelCase aliases: camelCase keys (`westLonDegree`). JS snake_case aliases: snake_case keys (`west_lon_degree`). For example, the out-param key `:west_lon_degree` is now `:west-lon-degree`. The Java keys `"auth-name"` and `"semi_major_metre"` are now `"authName"` and `"semiMajorMetre"`.
- BREAKING: `get-crs-info-list-from-database` renamed to `proj-get-crs-info-list-from-database` (Clojure) / `projGetCrsInfoListFromDatabase` (JS)
- JS tests now use idiomatic camelCase (`projCreateCrsToCrs`) instead of snake_case (`proj_create_crs_to_crs`). The two forms stay functional
- Struct-list dispatch replaces hand-written CRS info list wrapper (~120 lines removed)
- FFI struct field access through the dtype-next struct system instead of hardcoded byte offsets
- `string-array-to-polyglot-array` renamed to `string-list-to-native-array`, now cross-platform
- PROJ 9.8.1 (was 9.8.0)

### Fixed
- JVM: string-returning PROJ functions that return NULL (for example, `proj_as_proj_string` on concatenated operations) now return nil. Before, FFI threw an exception and GraalVM returned `""`
- `coord->coord-array`: missing `:browser` case in CLJS dispatch
- `coord->coord-array`: missing auto-initialization before dispatch
- `set-coord!`: wrapped JVM-only (was incorrectly cross-platform)
- `toggle-graal!`: now resets `implementation` to nil, the same as `force-graal!`/`force-ffi!`
- JS: null C string pointers in struct results now return `null` (was `""`)
- JS: null struct-list result now returns `[]` and frees allocated memory
- Nil string args now handled per-platform in `extract-args`: FFI gets `""` (dtype-next rejects nil), WASM gets 0 (NULL, necessary for "all/any" semantics)
- Nil pointer args now default to 0 (null pointer) in `extract-args`
- `proj_create_from_wkt`: pointer args changed to `:pointer?` (nullable)


## [0.1.0-alpha7] - 2026-03-06

### Added
- CLJS: Automatic cross-worker PJ reconciliation. When PJ args to a function are on different workers (for example, after round-robin context creation), the library recreates them on the target worker through PROJJSON export and a `proj_create_crs_to_crs`/`proj_get_source_crs` roundtrip. The result is ISO-19111 compatible objects. A `console.warn` message recommends explicit contexts for better performance.
- `force-worker-idx` parameter on `proj-emscripten-helper` and `def-wasm-fn-runtime` to route calls to a specific worker

### Fixed
- CLJS: Auto-create PROJ context when none is given. This corrects "Cannot find proj.db" errors for context-requiring functions called without an explicit context (for example, `projCreateCrsToCrs({source_crs: "EPSG:4326", target_crs: "EPSG:3857"})`)
- Moved auto-context creation from `extract-args` to `dispatch-proj-fn` for JVM and CLJS
- CLJS: Auto-created contexts now pin to the same worker as existing PJ args. This corrects empty results from functions (for example, `projAsWkt`) when you call them without an explicit context on a PJ object from a different worker
- CLJS: `getCrsInfoListFromDatabase` now auto-creates a context when you call it without one

## [0.1.0-alpha6] - 2026-03-05

### Fixed
- Browser: cross-origin CDN worker loading through a blob URL workaround (workers must be same-origin, so the library now creates a same-origin shim that imports the cross-origin worker script)

## [0.1.0-alpha5] - 2026-03-05

### Added
- camelCase JavaScript API aliases auto-generated for all PROJ functions (for example, `projTransArray`, `projCreateCrsToCrs`), plus manual aliases for helper functions (`setCoords`, `coordArray`, `contextCreate`, and so on)
- Network grid fetching (NADCON, NTv2, and so on) from cdn.proj.org across all platforms
  - FFI: JNA callbacks send HTTP range requests to Java HttpClient
  - GraalVM: Java HttpClient callbacks through compiled C stubs (`proj_network_stubs.c` with EM_JS)
  - Node.js: synchronous fetch in worker threads through `Atomics.wait()`
  - Browser: worker architecture with automatic pthreads/single-threaded mode detection
- Worker pool architecture for JavaScript (`proj-worker.mjs`, `fetch-worker.mjs`) with context-to-worker affinity
- PROJ logging callback for FFI through JNA (`logging.clj`)
- GraalVM network callbacks (`network.clj`) with `ProxyExecutable` callbacks dispatched through C stubs
- Playwright test server with configurable COOP/COEP for pthreads/single-threaded mode testing
- Grid fetch comparison tests on all platforms (OFF vs ON, with a check of the ~14m NADCON shift for Boston)
- `getWorkerMode()` / `getWorkerCount()` to examine the worker pool at runtime
- Windows x64 tested and operational

### Changed
- PROJ 9.8.0 (was 9.7.1), SQLite 3.51.2 (was 3.51.1), zlib 1.3.2 (was 1.3.1), GraalVM 25.0.2 (was 25.0.1)
- Removed `graal.clj`. GraalVM dispatch now uses the shared `wasm.cljc` code path
- `context-create` accepts `{:network false}` option across all platforms
- BREAKING: Coordinate arrays are now JS-side Float64Arrays instead of allocated WASM memory. `proj_trans_array` transfers data to the correct worker on demand and allocates it there. `coord:` now takes the coord array object directly instead of `coords.malloc`. Results are read with `getCoords(coords, idx)` instead of `coords.array[i]`.
- Playwright tests: dual-server config (with/without COOP/COEP), CDN-style loading tests

## [0.1.0-alpha4] - 2025-12-05

### Fixed
- Browser: WASM loader now resolves `proj.db`, `proj.ini`, and `proj-emscripten.wasm` relative to module URL instead of HTML page (corrects CDN loading)

## [0.1.0-alpha3] - 2025-12-04

### Added
- `proj_create_crs_to_crs_from_pj` function
- **Java API**: `PROJ.java` wrapper, `PROJTest.java` tests, `bb test:java-ffi`, `bb test:java-graal`, `bb test:clj-ffi`

- **Container-Based Build System**:
  - `Containerfile` with builds for native, WASM, and development targets
  - Cross-platform compilation support for `linux/amd64`, `linux/aarch64`, and `windows/amd64`
  - Local PROJ development workflow with `--build-arg USE_LOCAL_PROJ=1`

- **Local PROJ Development Workflow**:
  - `bb proj:clone` task to clone OSGeo/PROJ repository to `vendor/PROJ`
  - `--local-proj` flag for all build tasks to use local PROJ instead of release version

### Changed
- PROJ 9.7.1, GraalVM 25.0.1, Clojure 1.12.3

### Fixed
- `extract-args` now uses `:argsemantics` defaults (corrects `proj_create_from_database` NPE)
- `proj_create_from_database` options parameter changed to `:pointer?` for correct null handling
- ClojureScript: context/nil pointer conversion in ccall
- test:playwright copies the necessary resources

## [0.1.0-alpha2] - 2025-07-24

### Added
- **Babashka Build System**: Complete replacement of shell scripts with `bb.edn` tasks
  - `bb build` command with `--native`, `--wasm`, and `--cross` options
  - `bb test:ffi`, `bb test:graal`, `bb test:cljs`, `bb test:playwright` for tests
  - `bb jar`, `bb pom`, `bb cherry`, `bb nrepl` and other development tasks
  - `bb test:all` and `bb build:all` meta-tasks for eventual CI/CD workflows
  - `bb test-run` for the complete build and test pipeline (without deployment)

- **Macro-Based Code Generation**: Complete architectural refactor
  - New `fndefs.cljc` containing all PROJ function definitions as data
  - `macros.clj` and `macros.cljs` for compile-time and runtime code generation
  - Single source of truth for all PROJ function signatures

- **Runtime Dispatch System**: New unified architecture in `proj.cljc`
  - `dispatch-proj-fn` central router for all function calls
  - `extract-args` for flexible parameter handling (supports the two styles `:source-crs` and `:source_crs`)
  - Platform-specific dispatch with automatic implementation selection
  - Consistent error handling and return value processing

- **WebAssembly Module**: New `wasm.cljc` namespace
  - Unified WASM support for GraalVM and ClojureScript
  - Embedded resources (proj.db, proj.ini) directly in WASM for simpler deployment
  - New `proj-loader.mjs` for ES6 module loading
  - Automatic initialization with callbacks for async operations

- **Testing Infrastructure**
  - Playwright tests for browser-based WASM validation
  - Node.js test suite with ES modules support
  - Unified CLJ tests that run across Graal and FFI
  - Browser example in `examples/browser/index.html`

- **JavaScript/NPM Support**
  - ES6 module distribution through esbuild (replaces webpack)
  - Cherry compiler integration for ClojureScript compilation
  - `init` function alias for the JavaScript API
  - NPM package with correct exports and module structure

- **Developer Experience**
  - Improved documentation

### Changed
- **Build System**: Complete migration from shell scripts to Babashka
  - Removed shell scripts (for example, 1-build-proj-c.sh)
  - Consolidated all build logic into bb.edn tasks
  - Started improvements to cross-platform builds with Docker/Podman support
  - Simpler dependency management

- **Project Structure**
  - Moved from `src/js/proj-emscripten/` to consolidated WASM support in core
  - Replaced the separate webpack configurations with a single esbuild config
  - Removed the Java enum file (`Enums.java`), not necessary with the macro system
  - Simpler directory structure, with all core code in `src/cljc/net/willcohen/proj/`

- **Implementation Files**
  - `graal.clj`: Refactored to use macro-generated functions
  - `native.clj`: Simpler with the macro system
  - `proj.cljc`: Major refactor for runtime dispatch
  - All implementations now use common function definitions

- **Documentation**
  - README.md updated with Babashka commands
  - Added a "How It Works" section on runtime dispatch
  - Updated all usage examples to use the new `init` function

- **Dependencies**
  - Updated to PROJ 9.6.2
  - Updated all Clojure/ClojureScript dependencies
  - Added cherry compiler for ClojureScript builds
  - Replaced webpack dependencies with esbuild

### Fixed
- Cross-platform parameter naming inconsistencies
- Resource loading issues in WASM environments
- Build reproducibility issues with shell scripts

### Removed
- All shell-based build scripts (replaced by Babashka)
- Separate `proj-emscripten` JavaScript package
- Webpack build configurations
- Manual function implementations (replaced by macro generation)
- Java enum definitions

## 0.1.0-alpha1 - 2024-12-15
### Added
- Initial proof-of-concept functionality, released to NPM and Clojars.

[Unreleased]: https://github.com/willcohen/clj-proj/compare/0.1.0-alpha10...HEAD
[0.1.0-alpha10]: https://github.com/willcohen/clj-proj/compare/0.1.0-alpha9...0.1.0-alpha10
[0.1.0-alpha9]: https://github.com/willcohen/clj-proj/compare/0.1.0-alpha8...0.1.0-alpha9
[0.1.0-alpha8]: https://github.com/willcohen/clj-proj/compare/0.1.0-alpha7...0.1.0-alpha8
[0.1.0-alpha7]: https://github.com/willcohen/clj-proj/compare/0.1.0-alpha6...0.1.0-alpha7
[0.1.0-alpha6]: https://github.com/willcohen/clj-proj/compare/0.1.0-alpha5...0.1.0-alpha6
[0.1.0-alpha5]: https://github.com/willcohen/clj-proj/compare/0.1.0-alpha4...0.1.0-alpha5
[0.1.0-alpha4]: https://github.com/willcohen/clj-proj/compare/0.1.0-alpha3...0.1.0-alpha4
[0.1.0-alpha3]: https://github.com/willcohen/clj-proj/compare/0.1.0-alpha2...0.1.0-alpha3
[0.1.0-alpha2]: https://github.com/willcohen/clj-proj/compare/0.1.0-alpha1...0.1.0-alpha2