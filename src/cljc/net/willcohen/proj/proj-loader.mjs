// Copyright (c) 2024, 2025, 2026 Will Cohen
//
// Part of clj-proj, under the MIT License.
// See LICENSE for license information.
// SPDX-License-Identifier: MIT

/**
 * load() is the GraalVM loader of PROJ. loadProjResources() reads proj.db and
 * proj.ini for the worker pool.
 *
 * No static imports: a GraalVM polyglot Context evaluates this file as a bare
 * ESM Source, where a bare specifier does not resolve.
 *
 * @module proj-loader
 */

/**
 * Load the PROJ Emscripten module and install its data files.
 *
 * Called by clj-native's bootstrap-graal-module!, which owns the caching and
 * bridges the returned promise onto the future that the JVM caller blocks
 * on. Each resource arrives as a real Uint8Array (proj.ini as a string),
 * because the JVM side encodes them through clj-native's js-bytes.
 *
 * @param {object} options
 * @param {Uint8Array} options.wasmBinary - proj-emscripten.wasm bytes.
 * @param {Uint8Array} options.projDb     - proj.db bytes.
 * @param {string}     options.projIni    - proj.ini contents.
 * @param {object}     [options.projGrids] - {filename: Uint8Array} grid files.
 * @returns {Promise<object>} the initialized Emscripten Module.
 */
async function load(options = {}) {
  console.time('PROJ-init');

  const { default: PROJModule } = await import('./proj-emscripten.js');

  const moduleArgs = {
    wasmBinary: options.wasmBinary.buffer,
    // GraalVM's JS has no URL global. Without locateFile, Emscripten's
    // findWasmBinary calls `new URL(name, import.meta.url)`, which throws
    // there although wasmBinary is supplied. A bare path prevents the URL
    // construction, and nothing fetches the path.
    locateFile: (path) => path,
    monitorRunDependencies: (left) => console.debug(`PROJ-EMCC-DEPS: ${left} dependencies remaining`),
    // Deliberately no setStatus: with it present, Emscripten's run() wraps
    // doRun() in a timer, and GraalVM's JS has no setTimeout.
  };

  // preRun runs after the FS is up and before any C code. PROJ caches its
  // search paths on the first PJ_CONTEXT creation, so the files must exist
  // first. MODULARIZE makes moduleArgs the Module itself, so the closure
  // over moduleArgs is correct.
  moduleArgs.preRun = [function () {
    moduleArgs.FS.mkdir('/proj');
    moduleArgs.FS.writeFile('/proj/proj.db', options.projDb);
    moduleArgs.FS.writeFile('/proj/proj.ini', options.projIni);
    if (options.projGrids) {
      moduleArgs.FS.mkdir('/proj/grids');
      for (const [name, bytes] of Object.entries(options.projGrids)) {
        // A failed grid write decreases transformation accuracy but does not
        // stop PROJ. Thus the catch logs the error.
        try {
          moduleArgs.FS.writeFile(`/proj/grids/${name}`, bytes);
        } catch (e) {
          console.error(`PROJ: Failed to write grid file ${name}:`, e);
        }
      }
    }
  }];

  const module = await PROJModule(moduleArgs);
  console.timeEnd('PROJ-init');
  return module;
}

/**
 * Loads proj.db and proj.ini from the filesystem (Node.js) or fetch
 * (browser), for handler/default-init-args.
 * @returns {Promise<{projDb: Uint8Array, projIni: Uint8Array}>}
 */
async function loadProjResources() {
  const urls = ['proj.db', 'proj.ini'].map((name) => new URL(name, import.meta.url));
  let bytes;
  if (typeof process !== 'undefined' && process.versions?.node != null) {
    const fs = await import('fs');
    bytes = urls.map((url) => fs.readFileSync(url));
  } else {
    const resps = await Promise.all(urls.map((url) => fetch(url)));
    // A 404 page staged as proj.db fails much later, as a database error.
    resps.forEach((resp, i) => {
      if (!resp.ok) {
        throw new Error(`proj-loader: fetch of ${urls[i].href} failed: HTTP ${resp.status}`);
      }
    });
    bytes = await Promise.all(resps.map(async (resp) => new Uint8Array(await resp.arrayBuffer())));
  }
  return { projDb: bytes[0], projIni: bytes[1] };
}

export { load, loadProjResources };
