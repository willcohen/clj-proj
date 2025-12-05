# Change Log
All notable changes to this project will be documented in this file. This change
log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [Unreleased]

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

[Unreleased]: https://github.com/willcohen/clj-proj/compare/0.1.0-alpha3...HEAD
[0.1.0-alpha3]: https://github.com/willcohen/clj-proj/compare/0.1.0-alpha2...0.1.0-alpha3
[0.1.0-alpha2]: https://github.com/willcohen/clj-proj/compare/0.1.0-alpha1...0.1.0-alpha2