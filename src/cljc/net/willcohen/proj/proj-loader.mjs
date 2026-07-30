// Copyright (c) 2024, 2025, 2026 Will Cohen
//
// Part of clj-proj, under the MIT License.
// See LICENSE for license information.
// SPDX-License-Identifier: MIT

/**
 * Main-thread orchestrator for PROJ WASM initialization on the GraalVM path,
 * plus a resource-loading helper for the worker-router JS path.
 *
 * - load(): the loader contract that clj-native's bootstrap-graal-module!
 *   drives. It receives the binary resources that the JVM side encoded,
 *   installs them into Emscripten's virtual filesystem, and returns the
 *   loaded module. That fn's docstring holds the contract and the GraalVM
 *   rules that this file obeys.
 * - detectEnvironment(): node / browser / unknown classifier.
 * - loadProjResources(): reads proj.db and proj.ini once on the main thread,
 *   so that wasm.cljc's init-workers! can forward them into each worker
 *   through worker-router's bootstrap protocol.
 *
 * This module has no static imports, deliberately. A GraalVM polyglot Context
 * evaluates it as a bare ESM Source, where a bare specifier does not
 * resolve. Thus it cannot get clj-native's shipped helpers and keeps its own
 * detectEnvironment. Node imports the same file as an ordinary module for
 * loadProjResources, so the two functions stay together.
 *
 * @module proj-loader
 */

/**
 * Identifies the current JavaScript environment.
 * @returns {'node' | 'browser' | 'unknown'}
 */
function detectEnvironment() {
  if (typeof process !== 'undefined' && process.versions != null && process.versions.node != null) {
    return 'node';
  }
  if (typeof window !== 'undefined' && typeof window.document !== 'undefined') {
    return 'browser';
  }
  return 'unknown';
}

/**
 * Load the PROJ Emscripten module and install its data files.
 *
 * Called by clj-native's bootstrap-graal-module!, which owns the caching and
 * bridges the returned promise onto the future that the JVM caller blocks
 * on. Each resource arrives as a real Uint8Array (proj.ini as a string),
 * because the JVM side encodes them through clj-native's js-bytes. This fn
 * does no widening.
 *
 * This fn sets two module arguments for GraalVM. Comments at those two lines
 * give the reason. bootstrap-graal-module!'s docstring holds the general
 * rules.
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
 * (browser). Called once on the main thread by wasm.cljc's init-workers! and
 * forwarded into each worker-router worker through the handler's init args.
 * The two fields are bytes, so the worker handler stages them uniformly
 * through ffi-wasm/handler-fs's stageFiles.
 * @returns {Promise<{projDb: Uint8Array, projIni: Uint8Array}>}
 */
async function loadProjResources() {
  const env = detectEnvironment();

  if (env === 'node') {
    const fs = await import('fs');
    const path = await import('path');
    const { fileURLToPath } = await import('url');

    const __filename = fileURLToPath(import.meta.url);
    const __dirname = path.dirname(__filename);

    return {
      projDb: fs.readFileSync(path.join(__dirname, 'proj.db')),
      projIni: fs.readFileSync(path.join(__dirname, 'proj.ini'))
    };
  } else {
    const baseUrl = new URL('./', import.meta.url).href;
    const [dbResp, iniResp] = await Promise.all([
      fetch(baseUrl + 'proj.db'),
      fetch(baseUrl + 'proj.ini')
    ]);

    return {
      projDb: new Uint8Array(await dbResp.arrayBuffer()),
      projIni: new Uint8Array(await iniResp.arrayBuffer())
    };
  }
}

export { load, detectEnvironment, loadProjResources };
