# Change Log
This file documents notable changes to this project. This change log uses the
conventions of [keepachangelog.com](http://keepachangelog.com/).

## [Unreleased]

### Fixed
- `proj-context-errno-string` takes `:context`.

## [0.1.0-alpha11] - 2026-09-29

### Added
- THIRD-PARTY-NOTICES.md in the jar and the npm package.
- `proj-context-set-search-paths`, `proj-context-get-user-writable-directory`,
  `proj-assign-context` and `proj-errno-reset`.

### Changed
- clj-native 0.0.2 (`net.willcohen/native`, npm `ffi-wasm`). On the GraalVM
  backend, a PROJ exception makes the call throw.
- The Linux libs load on glibc 2.28 and later. A musl host, for example
  Alpine, gets a `linux-<arch>-musl` lib. The Linux and Windows libs contain
  the LLVM C++ runtime.
- GraalVM 25.3.4.1, Clojure 1.12.6, tools.logging 1.3.1, dtype-next 11.026 and
  squint-cljs 0.14.210. The optimizing runtime needs a GraalVM CE 25.3.4.1 JVM.
- An `options` argument takes nil or a vector of strings.
- `proj-string-destroy` takes a pointer.

### Removed
- The `xhr2` dependency of proj-wasm.
- The `shutdown` method of the worker handler. To stop the fetch worker,
  terminate the pool.
- `proj-coord`, `proj-xy-dist` and `proj-get-suggested-operation`, which passed
  their `PJ_COORD` incorrectly.

### Fixed
- The jar contains the clj-kondo exports and the license.
- The Linux libs of 0.1.0-alpha8 to 0.1.0-alpha10 did not load on older glibc.
  The Linux and macOS libs had a run path into the build directories.
- SQLite and libtiff build with `-O2`.
- PROJ did not read the bundled `proj.ini`.
- The errno check could throw the error of an earlier call. `proj-as-wkt` of a
  PJ from `proj-create-crs-to-crs` now returns nil with no error, as in PROJ.
- Memory leaks in string-list results, in `proj-suggests-code-for` and in the
  `get_header` network callback.
- `proj-get-units-from-database` with no category returned no units.
- `context-database-path` always returned nil.
- JVM: the GC frees an unreachable PJ or context.
- JVM: a call on one context from two threads could run two times.
- FFI backend: a NULL string list, or a call with no options, threw "Pointer
  value is zero!".
- GraalVM backend: calls failed when a second wasm library, for example
  clj-gdal, was loaded.
- GraalVM backend: string-list arguments leaked wasm memory.
- GraalVM backend: `coord->coord-array` of `[x y]` did not set z and t to 0.
- GraalVM backend: a pool worker with a failed boot kept its polyglot Context
  open.
- squint: a failed `proj-create-crs-to-crs` and a call across two workers
  leaked PROJ objects on the workers.
- squint: an explicit destroy of a tracked PJ or context freed it a second
  time at `Symbol.dispose`.
- squint: the pool could count a context on the wrong worker.
- squint: a failed `init` also stopped Node.js with `ERR_UNHANDLED_REJECTION`.
- squint: `context-set-database-path` and `context-set-enable-network` always
  went to worker 0.
- squint: PROJ ignored an `options` argument.
- squint: `proj-coordoperation-get-towgs84-values` gave no values.
- Browser: `init` rejects when the fetch of `proj.db` or `proj.ini` fails.

## [0.1.0-alpha10] - 2026-09-15

### Added
- A binding for `proj_crs_is_dynamic`, new in PROJ 9.9.
  `proj_create_linear_3D_affine_parametric_conversion` has no binding. It has
  38 arguments, and a dt-ffi wrapper takes at most 20.

### Changed
- PROJ 9.9.0 and squint-cljs 0.14.208.
- Coordinate batches go to the workers as a `Float64Array`.

## [0.1.0-alpha9] - 2026-08-24

### Added
- `handler.cljc`, the `:proj` handler for the clj-native workload pool, and
  `proj/transform-batch`.
- `init!` options: `:pool`, `:debug-level`, `:debug-categories`,
  `:max-live-ctxs` (default 128) and `:min-age-ms` (default 100).
  `getPoolDetail()` shows the state of the pool.
- clj-kondo hooks for the generated functions, in
  `resources/clj-kondo.exports`.

### Changed
- squint replaces cherry as the compiler. The macros are one `macros.cljc`.
- The JS workers run on worker-router, and clj-native generates
  `proj-handler.mjs`.
- The FFI backend uses dtype-next on the JDK Panama FFM API. JNA is removed.
- clj-native (`net.willcohen/native` 0.0.1, npm `ffi-wasm` 0.0.1) holds the
  platform, dispatch and WASM code.
- One single-threaded WASM build serves the browser, Node.js and GraalVM.
  Browsers need no Cross-Origin Isolation headers.
- The minimum JDK is 25. GraalVM 25.2.4 with `truffle-runtime`, and dtype-next
  11.025.
- The jar does not ship the worker JS or the WASM debug map. The npm package
  has a `./proj-handler` export.

### Removed
- `Containerfile`, `spec.cljc` and the C network stubs of the WASM build.
- The squint functions that read the Emscripten module on the main thread, for
  example `get-value` and `pointer->string`. The wasm heap functions are JVM
  only.

### Fixed
- squint: a call with a context could go to the wrong worker.
- squint: each native call started `init-proj` again.
- squint: contexts were never finalized.
- JVM: the struct-list functions returned null.
- FFI backend: the network, log and `get_header` callbacks could lose their
  registration or their result across contexts and threads.

## [0.1.0-alpha8] - 2026-04-14

### Added
- GitHub Actions CI for the native libs, the WASM and the tests.
- Functions that return a C struct array, generated from `:struct-fields` in
  fndefs.
- Out-param functions, for example `proj-get-area-of-use`: the call gives the
  input arguments and gets a map.
- `proj-create`, `proj-get-units-from-database` and
  `proj-get-celestial-body-list-from-database`.
- Java API: the CRS decomposition and the out-param functions.
- `bb deploy` and `bb deploy:dry-run`.

### Changed
- BREAKING: result keys follow the platform. Clojure uses kebab-case keywords
  (`:west-lon-degree`), Java uses camelCase strings (`"westLonDegree"`), and JS
  uses the case of the function alias.
- BREAKING: `get-crs-info-list-from-database` is now
  `proj-get-crs-info-list-from-database` (`projGetCrsInfoListFromDatabase` in
  JS).
- `string-array-to-polyglot-array` is now `string-list-to-native-array`.
- PROJ 9.8.1.

### Removed
- The `download-grids` task.

### Fixed
- JVM: a string function that returns NULL returns nil.
- `coord->coord-array` did not start the initialization, and failed in the
  browser.
- `set-coord!` is JVM only.
- `toggle-graal!` resets `implementation`, as `force-graal!` and `force-ffi!`
  do.
- JS: a NULL string in a struct result is `null`, and a NULL struct list is
  `[]`.
- `extract-args` passes nil string and nil pointer arguments correctly on each
  platform.

## [0.1.0-alpha7] - 2026-03-06

### Added
- cherry: the library copies PJ arguments on different workers to one worker.
  Explicit contexts are faster.
- A `force-worker-idx` parameter sends a call to a specific worker.

### Fixed
- cherry: a call with no context creates one on the worker of its PJ arguments.
  This fixes "Cannot find proj.db" and empty results.

## [0.1.0-alpha6] - 2026-03-05

### Fixed
- Browser: workers load from a cross-origin CDN.

## [0.1.0-alpha5] - 2026-03-05

### Added
- camelCase JavaScript aliases of all functions, for example
  `projCreateCrsToCrs`.
- Network grid fetch from cdn.proj.org on all platforms.
- A worker pool for JavaScript. Each context stays on one worker.
- PROJ logs on the FFI backend.
- `getWorkerMode()` and `getWorkerCount()`.
- Windows x64.

### Changed
- PROJ 9.8.0, SQLite 3.51.2, zlib 1.3.2 and GraalVM 25.0.2.
- GraalVM uses the shared `wasm.cljc` code, and `graal.clj` is removed.
- `context-create` takes `{:network false}`.
- BREAKING: JS coordinate arrays are `Float64Array`s. Give the coord array as
  `coord:`, and read the results with `getCoords(coords, idx)`.

## [0.1.0-alpha4] - 2025-12-05

### Fixed
- Browser: the WASM loader finds its files relative to the module URL, and
  CDN loading works.

## [0.1.0-alpha3] - 2025-12-04

### Added
- `proj_create_crs_to_crs_from_pj`.
- The Java API (`PROJ.java`).
- A container build for the native, WASM and cross-compile targets.
- Builds from a local PROJ checkout (`bb proj:clone`, `--local-proj`).

### Changed
- PROJ 9.7.1, GraalVM 25.0.1 and Clojure 1.12.3.

### Fixed
- `proj_create_from_database` threw an NPE.
- cherry: ccall converted contexts and nil pointers incorrectly.

## [0.1.0-alpha2] - 2025-07-24

### Added
- Babashka tasks in `bb.edn` for the build and the tests.
- The PROJ functions are generated from the data in `fndefs.cljc`.
- `dispatch-proj-fn` sends each call to its platform. Arguments take
  `:source-crs` or `:source_crs`.
- `wasm.cljc` for GraalVM and cherry, with proj.db and proj.ini in the WASM.
- An npm package, built with cherry and esbuild, with an `init` alias.

### Changed
- PROJ 9.6.2.
- One esbuild config replaces the webpack configurations.

### Removed
- The shell build scripts and the separate `proj-emscripten` package.

### Fixed
- Parameter names that were different on each platform.
- Resource loading in WASM.

## 0.1.0-alpha1 - 2024-12-15

### Added
- A proof of concept, released to npm and Clojars.

[Unreleased]: https://github.com/willcohen/clj-proj/compare/0.1.0-alpha11...HEAD
[0.1.0-alpha11]: https://github.com/willcohen/clj-proj/compare/0.1.0-alpha10...0.1.0-alpha11
[0.1.0-alpha10]: https://github.com/willcohen/clj-proj/compare/0.1.0-alpha9...0.1.0-alpha10
[0.1.0-alpha9]: https://github.com/willcohen/clj-proj/compare/0.1.0-alpha8...0.1.0-alpha9
[0.1.0-alpha8]: https://github.com/willcohen/clj-proj/compare/0.1.0-alpha7...0.1.0-alpha8
[0.1.0-alpha7]: https://github.com/willcohen/clj-proj/compare/0.1.0-alpha6...0.1.0-alpha7
[0.1.0-alpha6]: https://github.com/willcohen/clj-proj/compare/0.1.0-alpha5...0.1.0-alpha6
[0.1.0-alpha5]: https://github.com/willcohen/clj-proj/compare/0.1.0-alpha4...0.1.0-alpha5
[0.1.0-alpha4]: https://github.com/willcohen/clj-proj/compare/0.1.0-alpha3...0.1.0-alpha4
[0.1.0-alpha3]: https://github.com/willcohen/clj-proj/compare/0.1.0-alpha2...0.1.0-alpha3
[0.1.0-alpha2]: https://github.com/willcohen/clj-proj/compare/0.1.0-alpha1...0.1.0-alpha2
