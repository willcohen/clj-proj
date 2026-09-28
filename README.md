# clj-proj

**[Live Demo](https://willcohen.github.io/clj-proj/)**

[![CI](https://github.com/willcohen/clj-proj/actions/workflows/ci.yml/badge.svg)](https://github.com/willcohen/clj-proj/actions/workflows/ci.yml)
[![NPM Version](https://img.shields.io/npm/v/proj-wasm)](https://www.npmjs.com/package/proj-wasm)
[![Clojars Version](https://img.shields.io/clojars/v/net.willcohen%2Fproj)](https://clojars.org/net.willcohen/proj)

clj-proj gives [PROJ](https://proj.org/) to the JVM and to JavaScript. The
[Clojars package](https://clojars.org/net.willcohen/proj) has a Clojure API
and a Java API. The npm package [`proj-wasm`](https://www.npmjs.com/package/proj-wasm)
has a JavaScript API over a WebAssembly build of PROJ.

clj-proj is alpha software. The API can change in each release.

```clojure
net.willcohen/proj {:mvn/version "0.1.0-alpha10"}
```

For Maven or Gradle, use `net.willcohen:proj:0.1.0-alpha10` from the Clojars
repository (`https://repo.clojars.org`).

```bash
npm install proj-wasm
```

## Usage

### Clojure

```clojure
(require '[net.willcohen.proj.proj :as proj])

;; The first PROJ call runs proj/init! if you did not call it.

(def ctx (proj/context-create))
(def transformer (proj/proj-create-crs-to-crs {:context ctx
                                               :source-crs "EPSG:4326"
                                               :target-crs "EPSG:2249"}))

;; EPSG:4326 uses latitude, longitude order.
(def coords (proj/coord-array 1))
(proj/set-coords! coords [[42.3603222 -71.0579667 0 0]]) ; Boston City Hall
(proj/proj-trans-array {:p transformer :direction 1 :n 1 :coord coords})
(proj/get-coords coords 0)
;; => [x y z t] in EPSG:2249 (MA State Plane)

(proj/proj-get-authorities-from-database)
;; => ["EPSG" "ESRI" "IAU_2015" "IGNF" "NKG" "NRCAN" ...]
```

### Java

```java
import java.util.List;
import net.willcohen.proj.PROJ;

// Selects native FFI, or GraalVM WASM as the fallback
PROJ.init();

Object ctx = PROJ.contextCreate();
Object transform = PROJ.createCrsToCrs(ctx, "EPSG:4326", "EPSG:2249");

// EPSG:4326 uses latitude, longitude order
Object coords = PROJ.coordArray(1);
PROJ.setCoords(coords, new double[][]{{42.3603222, -71.0579667}}); // Boston City Hall
PROJ.transArray(transform, coords, 1);
double[] xyzt = PROJ.getCoords(coords, 0);

List<String> authorities = PROJ.getAuthoritiesFromDatabase();
// => ["EPSG", "ESRI", "IAU_2015", "IGNF", "NKG", "NRCAN", ...]

// A transformation from two CRS objects
Object sourceCrs = PROJ.createFromDatabase(ctx, "EPSG", "4326");
Object targetCrs = PROJ.createFromDatabase(ctx, "EPSG", "2249");
Object transformFromPj = PROJ.createCrsToCrsFromPj(ctx, sourceCrs, targetCrs);
```

`PROJ.java` wraps a part of the Clojure API. Its Javadoc lists the methods.

### JavaScript

PROJ runs in a pool of Web Workers (browser) or `worker_threads` (Node.js).
Each PROJ function returns a Promise. Call `init` before all other functions.

```javascript
import * as proj from "proj-wasm";

await proj.init();

// A call with no context option makes a context.
const transformer = await proj.projCreateCrsToCrs({
  source_crs: "EPSG:4326",
  target_crs: "EPSG:2249"
});

// EPSG:4326 uses latitude, longitude order.
const coords = await proj.coordArray(1);
await proj.setCoords(coords, [[42.3603222, -71.0579667, 0, 0]]); // Boston City Hall
await proj.projTransArray({
  p: transformer,
  direction: proj.PJ_FWD,
  n: 1,
  coord: coords
});

const transformed = await proj.getCoords(coords, 0);
console.log("Transformed:", transformed[0], transformed[1]);

// In Node.js, stop the workers to let the process exit.
await proj.shutdown();
```

The browser API is the same.

## Usage notes

The API covers the [ISO 19111 section of the PROJ C
API](https://proj.org/en/stable/development/reference/functions.html#c-api-for-iso-19111-functionality).
Objects from this section do not mix with functions from the other sections.
The exception is a `CoordinateOperation` with a PROJ pipeline export, which
operates with `proj_trans_array`.

Each PROJ function takes one options map. The keys are the argument names in
`fndefs.cljc`, with underscores or hyphens: `:source_crs` and `:source-crs`
are the same key. The `context` key is optional. If you do not give a
context, the call uses the context of a PJ argument or makes a new context.

`set-coords!` adds zeros to a coordinate with fewer than four values.
`proj-trans-array` changes the coordinate array in place. The `:direction` key
is necessary: 1 is forward, -1 is inverse.

On the JVM, a context is an atom, and the calls on one context run one at a
time. In JavaScript, a context stays on one worker. If the PJ arguments of a
call come from different workers, the library moves them to one worker
through PROJJSON. It also writes a `console.warn` message. To prevent the
move, use one explicit context.

A function with C output parameters returns a map. The Clojure keys are
kebab-case keywords, and the Java keys are camelCase strings. In JavaScript, a
camelCase name returns camelCase keys, and a snake_case name returns
snake_case keys.

If a call with a context returns NULL or an empty string, the library reads
`proj_context_errno`. If the error number is not zero, the library throws an
error. Because the WASM build has no C++ exception catch, a PROJ exception
rejects the Promise in JavaScript and makes the call throw on GraalVM. For
example, an invalid CRS causes a PROJ exception. That error has no PROJ error
number.

### Release of PROJ objects

On the JVM, the garbage collector releases each PROJ object that a call
returns. To release an object before that, call its destroy function, for
example `(proj/proj-destroy {:pj pj})`. The library frees each object one
time, also if you call its destroy function.

In JavaScript, a FinalizationRegistry releases each PJ and context after the
garbage collector removes it. A PJ and a context also have `Symbol.dispose`,
which a `using` declaration calls. The library does not release a PJ list or
an operation factory context. Call the destroy function of that object.

## Grid files

Some transformations, for example NAD27 to NAD83, use grid files from
`cdn.proj.org`. Without a grid, PROJ gives only a "ballpark" result. A new
context has the network on, and PROJ gets a grid when a call uses it. No
configuration is necessary.

clj-proj does not ship grid files, and it does not support local grid files.
The grids come only from `cdn.proj.org`.

On the JVM, Java's HttpClient does the HTTP range requests. In JavaScript, the
worker sends a synchronous XMLHttpRequest. In Node.js, a fetch worker from
clj-native does this request.

To set the network of a context to off:

```clojure
(def ctx-offline (proj/context-create {:network false}))
```

```javascript
const ctxOffline = await proj.contextCreate();
await proj.projContextSetEnableNetwork({ context: ctxOffline, enabled: 0 });
```

Because the WASM build is single-threaded, COOP and COEP headers are not
necessary for a browser page. To change the number of workers, give `init`
the `workers` option, for example `proj.init({ workers: 2 })`.

## Platforms

### JVM (Java and Clojure)

JDK 25 or later is necessary for the JVM library. `init!` loads the native
PROJ library for the platform through dtype-next and the JDK FFM API. If the
load fails, `init!` prints a message and uses the GraalVM WebAssembly backend.

Native libraries:

- macOS arm64
- Linux amd64 and aarch64: glibc 2.28 or later (for example Debian 10,
  Ubuntu 20.04, RHEL 8), and musl (Alpine)
- Windows amd64

macOS x86_64 and Windows arm64 have no native library. They use the GraalVM
backend.

### GraalVM WebAssembly backend

This backend runs the PROJ WASM build in GraalVM. It loads the WASM binary
and `proj.db`, and its initialization takes some seconds. Run it on a GraalVM
CE 25 JDK with `-XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI`. On a
different JDK, GraalVM runs the WASM code in its interpreter, which is much
slower. The warning "The polyglot context is using an implementation that
does not support runtime compilation" shows that the interpreter runs.

To use this backend on a platform with a native library:

```clojure
(proj/force-graal!)
(proj/init!)
(proj/graal?) ;; => true
```

After `(proj/force-ffi!)`, the next `init!` tries FFI first.

## Build

Babashka and Nix run the build. zig builds the Linux and Windows libs on the
host, with no container. For `bb test:linux`, podman or docker is necessary.

1. Install [Nix](https://nixos.org/download.html) and [direnv](https://direnv.net/).
2. Run `direnv allow` one time.

The flake sets `JAVA_HOME` to GraalVM CE 25, which lets GraalVM compile the
PROJ WASM code. In a shell without direnv, run `direnv exec . bb <task>`.

```bash
bb tasks                                     # All tasks, with a description
bb build --native                            # Native lib for this host
bb build --wasm                              # The WASM build
bb build --cross                             # All zig targets
bb build --cross-platform linux/amd64-musl   # One zig target
bb squint                                    # JavaScript bundle in dist/
bb jar                                       # JAR
bb test-run                                  # Clean, build, test, then the JAR
bb dev                                       # nREPL on port 7888
bb demo                                      # Demo at http://localhost:8080/docs/
```

The zig targets are `linux/amd64`, `linux/aarch64`, `linux/amd64-musl`,
`linux/aarch64-musl` and `windows/amd64`.

The native build compiles PROJ, SQLite, LibTIFF and zlib into
`resources/<platform>/`, for example `resources/darwin-aarch64/`. A Linux or
Windows lib contains SQLite, LibTIFF, zlib and the LLVM C++ runtime, and
links the C library dynamically. The build checks each native lib before it
copies the lib to `resources/`.

The WASM build uses emscripten from the Nix shell. It writes to
`resources/wasm/` for GraalVM and to `src/cljc/net/willcohen/proj/` for the
npm bundle.

### Tests

```bash
bb test:all           # FFI, GraalVM, Node.js, Playwright, JAR and npm

bb test:ffi           # Native FFI
bb test:graal         # GraalVM WebAssembly
bb test:node          # JavaScript / Node.js
bb test:playwright    # Browser tests in Chromium and Firefox

bb test:jar           # JAR as a downstream dependency
bb test:npm           # npm package as a downstream dependency
bb test:linux         # Linux platforms, in a public container image
```

### clj-native

For the JVM, the `:dev` alias of `deps.edn` uses a clj-native checkout at
`../clj-native`.

`src/cljc/net/willcohen/proj/package.json` pins `ffi-wasm`, the npm package of
clj-native. To use a clj-native checkout in JavaScript, do these steps:

1. In `../clj-native`, run `npm pack`.
2. In that `package.json`, set `"ffi-wasm"` to
   `"file:../../../../../../clj-native/ffi-wasm-<version>.tgz"`. Do not commit
   this change.
3. Run `npm install --prefix src/cljc/net/willcohen/proj`.

After each clj-native change, do steps 1 and 3 again. To use a worker-router
checkout, link `src/cljc/net/willcohen/proj/node_modules/worker-router` to it.
Run `npm run build` in the checkout, then run `bb squint`.

### Local PROJ

To do a test of a PROJ change with the bindings:

```bash
bb proj:clone                   # Clone OSGeo/PROJ into vendor/PROJ (gitignored)
bb proj:clone --branch=<name>   # Clone one branch
bb proj:clone --update          # Pull into the existing clone
bb build --native --local-proj
bb test:ffi
bb build --wasm --local-proj
bb test:node
```

`--local-proj <path>` uses the PROJ source at that path.

## License

clj-proj is under the MIT License. Refer to [LICENSE](LICENSE).

The jar and the npm package also contain code and data from other projects,
for example PROJ and Emscripten. Refer to
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
