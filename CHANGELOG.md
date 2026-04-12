# Change Log
All notable changes to this project will be documented in this file. This change
log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [Unreleased]

### Added
- GitHub Actions CI: build native (linux-amd64, linux-aarch64, darwin-aarch64, windows-amd64 cross-compile), WASM, and run tests
- `coord->coord-array` tests across FFI, GraalVM, Node.js, and Browser

### Fixed
- `coord->coord-array`: missing `:browser` case in CLJS dispatch
- `coord->coord-array`: missing auto-initialization before dispatch
- `set-coord!`: wrapped JVM-only (was incorrectly cross-platform)
- `toggle-graal!`: now resets `implementation` to nil like `force-graal!`/`force-ffi!`

## [0.1.0-alpha7] - 2026-03-06

### Added
- CLJS: Automatic cross-worker PJ reconciliation. When PJ args to a function live on different workers (e.g., after round-robin context creation), they are transparently recreated on the target worker via PROJJSON export and `proj_create_crs_to_crs`/`proj_get_source_crs` roundtrip, producing ISO-19111 compatible objects. A `console.warn` is emitted suggesting explicit contexts for better performance.
- `force-worker-idx` parameter on `proj-emscripten-helper` and `def-wasm-fn-runtime` for routing calls to a specific worker

### Fixed
- CLJS: Auto-create PROJ context when none provided, fixing "Cannot find proj.db" errors for context-requiring functions called without an explicit context (e.g., `projCreateCrsToCrs({source_crs: "EPSG:4326", target_crs: "EPSG:3857"})`)
- Moved auto-context creation from `extract-args` to `dispatch-proj-fn` for both JVM and CLJS
- CLJS: Auto-created contexts now pin to the same worker as existing PJ args, fixing empty results from functions like `projAsWkt` when called without an explicit context on a PJ object created on a different worker
- CLJS: `getCrsInfoListFromDatabase` now auto-creates a context when called without one

## [0.1.0-alpha6] - 2026-03-05

### Fixed
- Browser: cross-origin CDN worker loading via blob URL workaround (workers must be same-origin; now creates a same-origin shim that imports the cross-origin worker script)

## [0.1.0-alpha5] - 2026-03-05

### Added
- camelCase JavaScript API aliases auto-generated for all PROJ functions (e.g., `projTransArray`, `projCreateCrsToCrs`), plus manual aliases for helper functions (`setCoords`, `coordArray`, `contextCreate`, etc.)
- Network grid fetching (NADCON, NTv2, etc.) from cdn.proj.org across all platforms
  - FFI: JNA callbacks delegate HTTP range requests to Java HttpClient
  - GraalVM: Java HttpClient callbacks via compiled C stubs (`proj_network_stubs.c` with EM_JS)
  - Node.js: synchronous fetch in worker threads via `Atomics.wait()`
  - Browser: worker architecture with automatic pthreads/single-threaded mode detection
- Worker pool architecture for JavaScript (`proj-worker.mjs`, `fetch-worker.mjs`) with context-to-worker affinity
- PROJ logging callback for FFI via JNA (`logging.clj`)
- GraalVM network callbacks (`network.clj`) with `ProxyExecutable` callbacks dispatched via C stubs
- Playwright test server with configurable COOP/COEP for pthreads/single-threaded mode testing
- Grid fetch comparison tests on all platforms (OFF vs ON, verifying ~14m NADCON shift for Boston)
- `getWorkerMode()` / `getWorkerCount()` for inspecting the worker pool at runtime
- Windows x64 tested and working

### Changed
- PROJ 9.8.0 (was 9.7.1), SQLite 3.51.2 (was 3.51.1), zlib 1.3.2 (was 1.3.1), GraalVM 25.0.2 (was 25.0.1)
- Removed `graal.clj` -- GraalVM dispatch now uses shared `wasm.cljc` code path
- `context-create` accepts `{:network false}` option across all platforms
- BREAKING: Coordinate arrays are now JS-side Float64Arrays instead of allocated WASM memory; data is transferred to the correct worker on demand by `proj_trans_array` and allocated there. `coord:` now takes the coord array object directly instead of `coords.malloc`. Results are read via `getCoords(coords, idx)` instead of `coords.array[i]`.
- Playwright tests: dual-server config (with/without COOP/COEP), CDN-style loading tests

## [0.1.0-alpha4] - 2025-12-05

### Fixed
- Browser: WASM loader now resolves `proj.db`, `proj.ini`, and `proj-emscripten.wasm` relative to module URL instead of HTML page (fixes CDN loading)

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
- `extract-args` now uses `:argsemantics` defaults (fixes `proj_create_from_database` NPE)
- `proj_create_from_database` options parameter changed to `:pointer?` for proper null handling
- ClojureScript: context/nil pointer conversion in ccall
- test:playwright copies required resources

## [0.1.0-alpha2] - 2025-07-24

### Added
- **Babashka Build System**: Complete replacement of shell scripts with `bb.edn` tasks
  - `bb build` command with `--native`, `--wasm`, and `--cross` options
  - `bb test:ffi`, `bb test:graal`, `bb test:cljs`, `bb test:playwright` for comprehensive testing
  - `bb jar`, `bb pom`, `bb cherry`, `bb nrepl` and other development tasks
  - `bb test:all` and `bb build:all` meta-tasks for eventual CI/CD workflows
  - `bb test-run` for complete build and test pipeline (excluding deployment)

- **Macro-Based Code Generation**: Complete architectural refactor
  - New `fndefs.cljc` containing all PROJ function definitions as data
  - `macros.clj` and `macros.cljs` for compile-time and runtime code generation
  - Single source of truth for all PROJ function signatures

- **Runtime Dispatch System**: New unified architecture in `proj.cljc`
  - `dispatch-proj-fn` central router for all function calls
  - `extract-args` for flexible parameter handling (supports both `:source-crs` and `:source_crs` styles)
  - Platform-specific dispatch with automatic implementation selection
  - Consistent error handling and return value processing

- **WebAssembly Module**: New `wasm.cljc` namespace
  - Unified WASM support for both GraalVM and ClojureScript
  - Embedded resources (proj.db, proj.ini) directly in WASM for simpler deployment
  - New `proj-loader.mjs` for ES6 module loading
  - Automatic initialization with callbacks for async operations

- **Testing Infrastructure**
  - Playwright tests for browser-based WASM validation
  - Node.js test suite with ES modules support
  - Unified CLJ tests that run across Graal and FFI
  - Browser example in `examples/browser/index.html`

- **JavaScript/NPM Support**
  - Modern ES6 module distribution via esbuild (replacing webpack)
  - Cherry compiler integration for ClojureScript compilation
  - Simplified `init` function alias for cleaner JavaScript API
  - Complete NPM package with proper exports and module structure

- **Developer Experience**
  - Improved documentation

### Changed
- **Build System**: Complete migration from shell scripts to Babashka
  - Removed shell scripts (1-build-proj-c.sh, etc.)
  - Consolidated all build logic into bb.edn tasks
  - Began improving cross-platform building with Docker/Podman support
  - Streamlined dependency management

- **Project Structure**
  - Moved from `src/js/proj-emscripten/` to consolidated WASM support in core
  - Eliminated separate webpack configurations in favor of single esbuild config
  - Removed Java enum file (`Enums.java`) - no longer needed with macro system
  - Simplified directory structure with all core code in `src/cljc/net/willcohen/proj/`

- **Implementation Files**
  - `graal.clj`: Refactored to use macro-generated functions
  - `native.clj`: Simplified with macro system
  - `proj.cljc`: Major refactor for runtime dispatch
  - All implementations now share common function definitions

- **Documentation**
  - README.md extensively updated with Babashka commands
  - Added "How It Works" section explaining runtime dispatch
  - Updated all usage examples to use new `init` function

- **Dependencies**
  - Updated to latest PROJ 9.6.2
  - Updated all Clojure/ClojureScript dependencies
  - Added cherry compiler for ClojureScript builds
  - Removed webpack dependencies in favor of esbuild

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

[Unreleased]: https://github.com/willcohen/clj-proj/compare/0.1.0-alpha7...HEAD
[0.1.0-alpha7]: https://github.com/willcohen/clj-proj/compare/0.1.0-alpha6...0.1.0-alpha7
[0.1.0-alpha6]: https://github.com/willcohen/clj-proj/compare/0.1.0-alpha5...0.1.0-alpha6
[0.1.0-alpha5]: https://github.com/willcohen/clj-proj/compare/0.1.0-alpha4...0.1.0-alpha5
[0.1.0-alpha4]: https://github.com/willcohen/clj-proj/compare/0.1.0-alpha3...0.1.0-alpha4
[0.1.0-alpha3]: https://github.com/willcohen/clj-proj/compare/0.1.0-alpha2...0.1.0-alpha3
[0.1.0-alpha2]: https://github.com/willcohen/clj-proj/compare/0.1.0-alpha1...0.1.0-alpha2