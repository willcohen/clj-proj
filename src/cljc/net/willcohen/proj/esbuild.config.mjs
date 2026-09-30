// Copyright (c) 2024, 2025, 2026 Will Cohen
//
// Part of clj-proj, under the MIT License.
// See LICENSE for license information.
// SPDX-License-Identifier: MIT

import * as esbuild from 'esbuild';
import { mkdirSync, copyFileSync, writeFileSync, unlinkSync, existsSync } from 'fs';

mkdirSync('dist', { recursive: true });

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

try {
  copyFileSync('proj-emscripten.js', 'dist/proj-emscripten.js');
} catch (err) {
  console.warn('Warning: Could not copy Emscripten JS file:', err.message);
}

// The worker imports these two as they are. They have no static import of a
// package, because a module worker ignores the page importmap. The generated
// handler imports ffi-wasm by the URL in its init args.
for (const f of ['proj-handler.mjs', 'proj-handler-overrides.mjs']) {
  copyFileSync(f, `dist/${f}`);
}

// platform 'neutral' does not know the Node builtins, which the Emscripten
// glue and clj-native's helpers import, with or without the `node:` prefix.
const emscriptenNodePlugin = {
  name: 'emscripten-node',
  setup(build) {
    build.onResolve({ filter: /^(node:)?(module|fs|path|crypto|util|url|worker_threads|os|stream|events)$/ }, args => {
      return { path: args.path, external: true };
    });
  }
};

const buildConfig = {
  bundle: true,
  format: 'esm',
  platform: 'neutral',
  mainFields: ['module', 'main'],
  outfile: 'dist/proj.mjs',
  // A package that proj.mjs inlines gets a second module instance next to
  // the copy that a consumer imports, and its state splits. 'ffi-wasm' also
  // covers each ffi-wasm/* subpath.
  external: [
    'squint-cljs/core.js',
    'squint-cljs/src/squint/string.js',
    'resource-tracker',
    'ffi-wasm',
  ],
  plugins: [emscriptenNodePlugin],
  keepNames: true,
  metafile: true,
  sourcemap: true,
};

async function build() {
  try {
    console.log('Building proj-wasm bundle...');

    // The wrapper entry re-exports proj.mjs plus the fndefs constants
    // (PJ_FWD, PROJ_VERSION_*). proj.cljc does not reference the constants,
    // so esbuild tree-shakes them without this.
    const entryContent = `
export * from './proj.mjs';
export * from './fndefs.mjs';
`;
    writeFileSync('./esbuild-entry.mjs', entryContent);

    // Expect `suspicious-nullish-coalescing` warnings. squint's `str` emits
    // `?? ''` in template holes, and esbuild sees a left operand that is
    // never null. The warnings are cosmetic and the emitted code is correct.
    const result = await esbuild.build({
      ...buildConfig,
      entryPoints: ['./esbuild-entry.mjs'],
    });

    try {
      unlinkSync('./esbuild-entry.mjs');
    } catch (e) {
      console.warn('Could not clean up temp files:', e.message);
    }

    const text = await esbuild.analyzeMetafile(result.metafile);
    console.log(text);

    console.log('\nBuild complete! Distribution in dist/proj.mjs');
  } catch (error) {
    console.error('Build failed:', error);
    process.exit(1);
  }
}

build();
