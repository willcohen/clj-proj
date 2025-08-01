# proj-wasm

A transpiled WebAssembly version of [PROJ](https://github.com/OSGeo/PROJ), made
available for use via JavaScript.

Part of the [clj-proj](https://github.com/willcohen/clj-proj) project, and is
still experimental. Please see that project's
[README](https://github.com/willcohen/clj-proj/blob/main/README.md) for further
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

// Create a context
const ctx = proj.context_create();

// Create a coordinate transformation from WGS84 to Web Mercator
const transformer = proj.proj_create_crs_to_crs({
  context: ctx,
  source_crs: "EPSG:4326",  // WGS84
  target_crs: "EPSG:3857"   // Web Mercator
});

// Create a coordinate array for one point
const coords = proj.coord_array(1);

// Set coordinates: [latitude, longitude, z, time] for EPSG:4326
// Example: Boston City Hall coordinates
proj.set_coords_BANG_(coords, [[42.3601, -71.0589, 0, 0]]);

// Transform the coordinates
proj.proj_trans_array({
  p: transformer,
  direction: 1,  // PJ_FWD (forward transformation)
  n: 1,          // number of coordinates
  coord: coords.malloc || coords.get('malloc')
});

// Access the transformed coordinates
const x = coords.array[0];  // Easting
const y = coords.array[1];  // Northing
console.log(`Transformed coordinates: [${x}, ${y}]`);
// Output: Transformed coordinates: [-7910240.56, 5215074.24]
```

## API Reference

### Core Functions

- `init()` - Initialize the PROJ library (must be called first)
- `context_create()` - Create a new PROJ context
- `proj_create_crs_to_crs(options)` - Create a transformation between two coordinate reference systems
  - `options.context` - The PROJ context
  - `options.source_crs` - Source CRS (e.g., "EPSG:4326")
  - `options.target_crs` - Target CRS (e.g., "EPSG:3857")

### Coordinate Handling

- `coord_array(n)` - Create an array for n coordinates
- `set_coords_BANG_(coords, values)` - Set coordinate values
  - `coords` - Coordinate array created with `coord_array`
  - `values` - Array of [x, y, z, t] coordinate tuples
- `proj_trans_array(options)` - Transform coordinates
  - `options.p` - The transformer
  - `options.direction` - 1 for forward, -1 for inverse
  - `options.n` - Number of coordinates
  - `options.coord` - The coordinate array's malloc pointer

### Accessing Results

After transformation, coordinates can be accessed via:
- `coords.array[0]` - X (easting/longitude)
- `coords.array[1]` - Y (northing/latitude)
- `coords.array[2]` - Z (height)
- `coords.array[3]` - T (time)

## Common CRS Examples

- `EPSG:4326` - WGS84 (GPS coordinates)
- `EPSG:3857` - Web Mercator (used by Google Maps, OpenStreetMap)
- `EPSG:2263` - NAD83 / New York Long Island (ft)
- `EPSG:32633` - UTM Zone 33N

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
