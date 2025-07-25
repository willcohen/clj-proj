import * as esbuild from 'esbuild';
import { mkdirSync, copyFileSync, readFileSync, writeFileSync, unlinkSync, existsSync } from 'fs';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));

// Ensure dist directory exists
mkdirSync('dist', { recursive: true });

// Copy WASM file to dist if it doesn't exist
console.log('Ensuring WASM file in dist...');
try {
  // The WASM file should already be in the current directory from bb build --wasm
  if (!existsSync('dist/proj-emscripten.wasm')) {
    copyFileSync('proj-emscripten.wasm', 'dist/proj-emscripten.wasm');
  }
} catch (err) {
  console.warn('Warning: Could not copy WASM file:', err.message);
}

// Plugin to handle Cherry's import patterns
const cherryImportPlugin = {
  name: 'cherry-imports',
  setup(build) {
    // Resolve Cherry's namespace-style imports to relative paths
    build.onResolve({ filter: /^(net\.willcohen\.proj\.|wasm$|fndefs$)/ }, args => {
      // Map namespace imports to relative paths
      const importMap = {
        'wasm': './wasm.mjs',
        'fndefs': './fndefs.mjs',
        'net.willcohen.proj.wasm': './wasm.mjs',
        'net.willcohen.proj.fndefs': './fndefs.mjs',
        'net.willcohen.proj.proj-loader': './proj-loader.mjs',
      };
      
      const mapped = importMap[args.path];
      if (mapped) {
        // Return absolute path and ensure it's bundled
        return { 
          path: resolve(dirname(args.importer), mapped),
          external: false  // Explicitly mark as NOT external to force bundling
        };
      }
    });

    // Remove imports for macros and other compile-time dependencies
    build.onResolve({ filter: /(wmacros|pmacros|proj-macros|macros)/ }, args => {
      return { path: args.path, namespace: 'empty-module' };
    });

    // Provide empty modules for removed imports
    build.onLoad({ filter: /.*/, namespace: 'empty-module' }, () => {
      return { contents: 'export default {}', loader: 'js' };
    });
  }
};

// Plugin to handle Node.js built-in modules for emscripten
const emscriptenNodePlugin = {
  name: 'emscripten-node',
  setup(build) {
    // Handle Node.js built-in modules that emscripten conditionally loads
    build.onResolve({ filter: /^(module|fs|path|crypto|util)$/ }, args => {
      // Mark these as external - they'll be available in Node.js
      // and emscripten handles the case when they're not available
      return { path: args.path, external: true };
    });
  }
};

// Build configuration
const buildConfig = {
  entryPoints: ['./proj.mjs'],
  bundle: true,
  format: 'esm',
  platform: 'neutral',
  mainFields: ['module', 'main'],
  outfile: 'dist/proj.mjs',
  external: [
    // WASM files are loaded dynamically
    './proj-emscripten.wasm',
    // Cherry core is external
    'cherry-cljs/cljs.core.js',
    'cherry-cljs/lib/clojure.string.js',
    // Resource tracker should be external dependency
    'resource-tracker'
  ],
  plugins: [cherryImportPlugin, emscriptenNodePlugin],
  loader: {
    '.js': 'js',
    '.mjs': 'js',
  },
  keepNames: true,
  metafile: true,
  sourcemap: true,
  // Removed footer - no longer needed since macros are properly quoting fn-defs
};

async function build() {
  try {
    console.log('Building proj-wasm bundle...');
    
    // Create shims file for any missing globals
    const shimContent = `
// Shims for esbuild to provide globals that macro-expanded code expects.
// These modules are resolved by the 'cherry-imports' plugin.
import * as fndefsModule from 'fndefs';
import * as wasmModule from 'wasm';
export const fndefs = fndefsModule;
export const wasm = wasmModule;
export const js = globalThis;
`;
    writeFileSync('./esbuild-shims.mjs', shimContent);
    
    const result = await esbuild.build({
      ...buildConfig,
      inject: ['./esbuild-shims.mjs'],
    });
    
    // Clean up shims
    try {
      unlinkSync('./esbuild-shims.mjs');
    } catch (e) {
      console.warn('Could not clean up shims:', e.message);
    }
    
    // Show bundle analysis
    const text = await esbuild.analyzeMetafile(result.metafile);
    console.log(text);
    
    console.log('\nBuild complete! Distribution in dist/proj.mjs');
  } catch (error) {
    console.error('Build failed:', error);
    process.exit(1);
  }
}

// Run the build
build();
