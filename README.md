# clj-proj

## Current Versions of Primary Packages

![NPM Version](https://img.shields.io/npm/v/proj-wasm)

![Clojars Version](https://img.shields.io/clojars/v/net.willcohen%2Fproj)

<!-- ## Current Versions of Internal Package -->

<!-- ![NPM Version](https://img.shields.io/npm/v/proj-emscripten) -->


This project provides a native (or transpiled) version of PROJ for both the JVM
and JS ecosystems.

The goal of this project is to provide a long-missing component of geospatial
analysis for these platforms: a performant version of PROJ that can closely
follow upstream development.

This currently provides bindings to the JVM via Clojure using a [package
published to Clojars](https://clojars.org/net.willcohen/proj), and to pure
Javascript via [an ES6 wrapper module called
`proj-wasm`](https://www.npmjs.com/package/proj-wasm) which provides a clean
interface to an [internal transpiled WASM module called
`proj-emscripten`](https://www.npmjs.com/package/proj-emscripten), both
published to NPM.


## EARLY DEVELOPMENT 

This project is in its initial phases, with only proof-of-concept functionality
built out, and no testing or continuous integration prepared. Feedback from
testers is welcome and encouraged.

Consider all APIs and structuring of this library to be an early
work-in-progress, subject to potentially substantial change while basic
functionality continues to be developed.

## JVM (Java / Clojure)

For Java and Clojure, one or two different interfaces with PROJ are available.
PROJ compilation works for the following platforms:

- macOS/darwin Apple Silicon (arm64)

Support is planned for the following platforms once basic functionality is more
complete, pending cross-platform compilation workflows:

- macOS/darwin Intel (x86_64)
- Windows x64 and arm64
- Linux x64 and arm64

### JDK 11+ with native library

On platforms with a native precompiled PROJ available, this library utilizes
JNA. This is the preferred option. Once Panama stabilizes, this library may use
that instead when present.

#### Usage

On a computer where the native library was built:
``` clojure

net.willcohen.proj.proj> (proj-init)
;; => #<G__24723@195e9ce3: 
  {:proj_destroy #object[tech.v3.datatype.ffi ...
net.willcohen.proj.proj> (trans-coord (create-crs-to-crs "EPSG:3586" "EPSG:4326") [0 0 0 0])
#atom[{:ptr #object[tech.v3.datatype.ffi.Pointer 0x366e1314 {:address 0x000000011F806100 }], :op 1, :result 1} 0x517c7f87]
;; => [34.24438675300125 -73.6513909034731 0.0 0.0]
```

Idiomatic Java API is not yet present.

### JDK 11+ with GraalWasm and graal.js

On platforms where no native library is available, this library falls back to
running the WebAssembly transpiled version of PROJ through GraalWasm, with some
Javascript glue needed for emscripten's filesystem API, using graal.js.

Users needing this transpiled PROJ are highly encouraged to use at least JDK 11
and to enable JVMCI to improve performance.

#### Usage

To test on a computer where a native library would supercede:

``` clojure
net.willcohen.proj.proj> (force-graal!)
;; => true
```

Otherwise, usage should be identical to the native library.

``` clojure
net.willcohen.proj.proj> (proj-init)
[To redirect Truffle log output to a file use one of the following options:
...
locateFile: pgi.wasm scriptDirectory: 
writeStackCookie: 0x00071690
initRuntime
;; => #'net.willcohen.proj.impl.graal/p
net.willcohen.proj.proj> (trans-coord (create-crs-to-crs "EPSG:3586" "EPSG:4326") [0 0 0 0])
...
INFO: p.ccall('proj_trans_array', 'number', ['number', 'number', 'number', 'number'], ['3389176', '1', '1', '3424368'], 0);
;; => [34.24438675300125, -73.6513909034731, 0.0, 0.0]
```

## JS (Javascript / WebAssembly / Clojurescript)

As noted above, this package also includes a WASM version of PROJ, compiled via
emscripten. While this version of PROJ has been compiled without thread support
(due to WASM's current lack of thread capabilities), PROJ's need for filesystem
access to a sqlite database and grid files requires the use of emscripten's FS
api, which requires Javascript. If filesystem access becomes standardized within
WebAssembly, a pure WebAssembly implementation may eventually become possible.
Alternately, if PROJ allows for embedding of the database internally, this would
negate the need for filesystem access, which would simplify handling and
interop.

This package uses cherry-cljs to wrap the emscripten-generated version of PROJ,
while also allowing the wrapping logic to mirror the JVM structure. This wrapper
via cherry creates the npm package for proj.

#### Usage

Put the following in `index.mjs`.
``` ejs
import * as proj from "proj-wasm";

proj.proj_init();

var t = proj.create_crs_to_crs("EPSG:3586", "EPSG:4326");
var c = [0,0,0,0]
console.log(proj.trans_coord(t, c));
```

``` sh
$ node index.mjs
writeStackCookie: 0x00000000
initRuntime
Float64Array(4) [
  34.24438675300125,
  -73.6513909034731,
  4.8569143e-317,
  5.7293886364e-313
]
```


## Building locally

### 1. Generate compiled and transpiled versions of upstream PROJ

This needs to happen whenever either the upstream versions of PROJ or its
dependencies change, or if there are modifications to the C compilation or
emscripten processes.

`./scripts/1-build-proj-c.sh`

### 2. Generate basic NPM packages wrapping emscripten version of PROJ

This needs to happen whenever the previous step changes.

`./scripts/2-build-proj-emscripten.sh`

### 3. Generate cross-platform NPM package matching full clj-proj API

`./scripts/3-build-proj-npm.sh`

## License

```
Copyright (c) 2024 Will Cohen

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
