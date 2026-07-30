// Copyright (c) 2024, 2025, 2026 Will Cohen
//
// Part of clj-proj, under the MIT License.
// See LICENSE for license information.
// SPDX-License-Identifier: MIT

import * as esbuild from 'esbuild';
import { mkdirSync, copyFileSync, readFileSync, writeFileSync, unlinkSync, existsSync } from 'fs';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));

mkdirSync('dist', { recursive: true });

// Always overwrite. An existsSync guard here kept stale wasm in dist/ across
// rebuilds.
console.log('Copying WASM file to dist...');
try {
  copyFileSync('proj-emscripten.wasm', 'dist/proj-emscripten.wasm');
} catch (err) {
  console.warn('Warning: Could not copy WASM file:', err.message);
}

// --debug builds make proj-emscripten.wasm.map. Without the map file adjacent
// to the wasm, emscripten's loader waits forever for the `source-map`
// runDependency.
if (existsSync('proj-emscripten.wasm.map')) {
  try {
    copyFileSync('proj-emscripten.wasm.map', 'dist/proj-emscripten.wasm.map');
  } catch (err) {
    console.warn('Warning: Could not copy WASM source map:', err.message);
  }
}

// The Node XHR polyfill (proj-handler-overrides.mjs) spawns this fetch worker
// from a URL in dist/, so the file must be adjacent to the handler.
try {
  copyFileSync('node_modules/ffi-wasm/src/cljc/net/willcohen/native/fetch_worker.mjs',
               'dist/fetch_worker.mjs');
} catch (err) {
  console.warn('Warning: Could not copy fetch_worker.mjs:', err.message);
}

// clj-native's pool builds the Worker URL with
// `import.meta.resolve('worker-router/worker-bootstrap')`. The in-tree
// browser test page has no node_modules, so an importmap entry points at this
// copy in dist/.
try {
  copyFileSync('node_modules/worker-router/dist/worker-bootstrap.mjs',
               'dist/worker-bootstrap.mjs');
  // proj.mjs externalizes worker-router, and worker-router imports comlink.
  // The test page importmap points at these copies adjacent to proj.mjs.
  copyFileSync('node_modules/worker-router/dist/index.mjs',
               'dist/worker-router.mjs');
  // npm hoists comlink on a fresh install and nests it under worker-router
  // in some existing trees, so try both locations.
  const comlinkSrc = ['node_modules/comlink/dist/esm/comlink.mjs',
                      'node_modules/worker-router/node_modules/comlink/dist/esm/comlink.mjs']
    .find(existsSync);
  if (!comlinkSrc) throw new Error('comlink not found under node_modules');
  copyFileSync(comlinkSrc, 'dist/comlink.mjs');
  // The cdn-style test page has no node_modules, so its importmap points
  // at this local copy of the installed resource-tracker.
  copyFileSync('node_modules/resource-tracker/resource.mjs',
               'dist/resource-tracker.mjs');
  // The bootstrap sourcemap is optional. An unguarded copy threw and stopped
  // the copies above.
  if (existsSync('node_modules/worker-router/dist/worker-bootstrap.mjs.map')) {
    copyFileSync('node_modules/worker-router/dist/worker-bootstrap.mjs.map',
                 'dist/worker-bootstrap.mjs.map');
  }
} catch (err) {
  console.warn('Warning: Could not copy worker-router worker-bootstrap:', err.message);
}

// The proj-handler bundle below overwrites this copy.
try {
  copyFileSync('proj-handler.mjs', 'dist/proj-handler.mjs');
} catch (err) {
  console.warn('Warning: Could not copy proj-handler files:', err.message);
}

try {
  copyFileSync('proj-emscripten.js', 'dist/proj-emscripten.js');
} catch (err) {
  console.warn('Warning: Could not copy Emscripten JS file:', err.message);
}

// All bundles below rewrite `ffi-wasm/handler-runtime` to this sibling file.
// See handlerRuntimeExternalPlugin for the shared-logState reason.
try {
  copyFileSync('node_modules/ffi-wasm/src/cljc/net/willcohen/native/handler_runtime.mjs',
               'dist/handler-runtime.mjs');
} catch (err) {
  console.warn('Warning: Could not copy clj-native handler_runtime.mjs:', err.message);
}

const squintImportPlugin = {
  name: 'squint-imports',
  setup(build) {
    build.onResolve({ filter: /^(net\.willcohen\.proj\.|wasm$|fndefs$)/ }, args => {
      const importMap = {
        'wasm': './wasm.mjs',
        'fndefs': './fndefs.mjs',
        'net.willcohen.proj.wasm': './wasm.mjs',
        'net.willcohen.proj.fndefs': './fndefs.mjs',
        'net.willcohen.proj.proj-loader': './proj-loader.mjs',
      };
      
      const mapped = importMap[args.path];
      if (mapped) {
        return {
          path: resolve(dirname(args.importer), mapped),
          external: false
        };
      }
    });

  }
};

// Externalizes `ffi-wasm/handler-runtime` and rewrites the specifier to
// `./handler-runtime.mjs` in every bundle. The ES module loader caches by
// URL, so all bundles in a JS context share one module instance and one
// logState. Inlined copies split logState: __setLogConfig writes one copy
// while ctx.dbg reads another.
const handlerRuntimeExternalPlugin = {
  name: 'handler-runtime-external',
  setup(build) {
    // The relative shape covers clj-native's own modules, which import
    // handler_runtime as a sibling. Without that match, esbuild inlines a
    // copy into proj.mjs although the bare specifier is external.
    build.onResolve({ filter: /^(ffi-wasm\/handler-runtime|\.\/handler_runtime\.mjs)$/ }, () => {
      return { path: './handler-runtime.mjs', external: true };
    });
  }
};

// worker-router's bundle uses the `node:` prefix form (Node 20+). Emscripten's
// conditional loader uses the bare form. Match the two.
const emscriptenNodePlugin = {
  name: 'emscripten-node',
  setup(build) {
    build.onResolve({ filter: /^(node:)?(module|fs|path|crypto|util|url|worker_threads|os|stream|events)$/ }, args => {
      return { path: args.path, external: true };
    });
  }
};

const buildConfig = {
  entryPoints: ['./proj.mjs'],
  bundle: true,
  format: 'esm',
  platform: 'neutral',
  mainFields: ['module', 'main'],
  outfile: 'dist/proj.mjs',
  external: [
    './proj-emscripten.wasm',
    'squint-cljs/core.js',
    'squint-cljs/src/squint/string.js',
    'resource-tracker',
    // worker-router and comlink resolve at runtime through node_modules or
    // the page importmap. comlink must stay resolvable at runtime for
    // worker-router's import.meta.resolve('comlink').
    'worker-router',
    'comlink',
    'comlink/dist/esm/node-adapter.mjs'
  ],
  plugins: [squintImportPlugin, emscriptenNodePlugin, handlerRuntimeExternalPlugin],
  loader: {
    '.js': 'js',
    '.mjs': 'js',
  },
  keepNames: true,
  metafile: true,
  sourcemap: true,
};

async function build() {
  try {
    console.log('Building proj-wasm bundle...');

    const shimContent = `
// Shims for esbuild to provide globals that macro-expanded code expects.
// These modules are resolved by the 'squint-imports' plugin.
import * as fndefsModule from 'fndefs';
import * as wasmModule from 'wasm';
export const fndefs = fndefsModule;
export const wasm = wasmModule;
export const js = globalThis;
`;
    writeFileSync('./esbuild-shims.mjs', shimContent);

    // The wrapper entry re-exports proj.mjs plus the fndefs constants
    // (PJ_FWD, PROJ_VERSION_*). proj.cljc does not reference the constants,
    // so esbuild tree-shakes them without this.
    const entryContent = `
export * from './proj.mjs';
export * from 'fndefs';
`;
    writeFileSync('./esbuild-entry.mjs', entryContent);

    // Expect `suspicious-nullish-coalescing` warnings. squint's `str` emits
    // `?? ''` in template holes, and esbuild sees a left operand that is
    // never null. The warnings are cosmetic and the emitted code is correct.
    const result = await esbuild.build({
      ...buildConfig,
      entryPoints: ['./esbuild-entry.mjs'],
      inject: ['./esbuild-shims.mjs'],
    });

    try {
      unlinkSync('./esbuild-shims.mjs');
      unlinkSync('./esbuild-entry.mjs');
    } catch (e) {
      console.warn('Could not clean up temp files:', e.message);
    }

    const text = await esbuild.analyzeMetafile(result.metafile);
    console.log(text);

    console.log('\nBuild complete! Distribution in dist/proj.mjs');

    // Bundled for the worker: module workers do not get the page importmap,
    // so a bare specifier in proj-handler.mjs causes a 404 in the browser.
    console.log('\nBundling proj-handler.mjs (worker-loadable)...');
    await esbuild.build({
      entryPoints: ['./proj-handler.mjs'],
      bundle: true,
      format: 'esm',
      platform: 'neutral',
      mainFields: ['module', 'main'],
      outfile: 'dist/proj-handler.mjs',
      external: [
        // Kept as a sibling import so overrides edits do not force a
        // proj-handler rebuild.
        './proj-handler-overrides.mjs',
      ],
      plugins: [handlerRuntimeExternalPlugin],
      keepNames: true,
      sourcemap: true,
      allowOverwrite: true,
    });
    console.log('proj-handler.mjs bundled to dist/proj-handler.mjs');

    // Bundled so the ffi-wasm/handler-{paths,fs,heap} imports resolve at
    // build time. Module workers do not get the page importmap, so bare
    // specifiers cause a 404 and the worker hangs at module load. The
    // paths/fs/heap helpers hold no state, so an inlined copy for each
    // consumer is safe. handler-runtime stays external to keep one logState
    // (see handlerRuntimeExternalPlugin).
    console.log('\nBundling proj-handler-overrides.mjs (worker-loadable)...');
    await esbuild.build({
      entryPoints: ['./proj-handler-overrides.mjs'],
      bundle: true,
      format: 'esm',
      platform: 'neutral',
      mainFields: ['module', 'main'],
      outfile: 'dist/proj-handler-overrides.mjs',
      external: [
        // The dynamic import of ./proj-emscripten.js must resolve at runtime
        // against dist/. xhr2 is Node-only, loaded through createRequire.
        './proj-emscripten.js',
        'xhr2',
      ],
      plugins: [emscriptenNodePlugin, handlerRuntimeExternalPlugin],
      keepNames: true,
      sourcemap: true,
      allowOverwrite: true,
    });
    console.log('proj-handler-overrides.mjs bundled to dist/proj-handler-overrides.mjs');
  } catch (error) {
    console.error('Build failed:', error);
    process.exit(1);
  }
}

build();
