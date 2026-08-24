// Copyright (c) 2024, 2025, 2026 Will Cohen
//
// Part of clj-proj, under the MIT License.
// See LICENSE for license information.
// SPDX-License-Identifier: MIT

/**
 * proj-handler-overrides.mjs - Library-specific overrides for clj-native's
 * gen-handler-source generator.
 *
 * This is the hand-written half of the proj worker-router handler. It
 * supplies the imports, the module-level state, the helpers, the init body,
 * and the methods bag. The generator emits a thin proj-handler.mjs that
 * wires these overrides into the shared makeHandler runtime. That runtime
 * owns workerQueue serialization, the in-flight counter, the destroy
 * barrier, and idempotent-init caching.
 *
 * Per-worker state (module, contexts, nextContextId, logCallbackPtr,
 * logLevel) lives in module-level `let` bindings. worker-router imports the
 * generated handler module once for each worker, so each worker gets its own
 * copy.
 *
 * This file runs from two different directories. Read every shipped asset
 * through resolveAsset with the [['.'], ['dist']] candidate list, and add
 * new assets the same way. A path built from __dirname alone works in one
 * layout and fails in the other.
 *
 * Known sub-worker spawn: in Node.js the XHR polyfill routes Emscripten's
 * synchronous XHR through clj-native's http-bridge (ffi-wasm/http-bridge).
 * The bridge spawns one fetch worker for each process and round-trips over
 * SharedArrayBuffer + Atomics.
 */

// These helpers hold no mutable state, so a top-level import is safe.
// Modules with shared state (handler_runtime's logState) must come through
// the substrate ctx in init(). A direct import can give this module its own
// logState copy.
import { resolveAsset, loadEmscriptenModule } from 'ffi-wasm/handler-paths';
import { stageFiles } from 'ffi-wasm/handler-fs';
import { heapHelpers } from 'ffi-wasm/handler-heap';
import { isNode } from 'ffi-wasm/handler-env';
import {
  createSyncFetch,
  installXhrPolyfill,
  shutdownMethod,
  moduleDestroy,
} from 'ffi-wasm/http-bridge';

// NET-DBG logs are off by default: through Playwright's CDP console capture,
// per-tile logs add multi-second overhead in a hot benchmark loop. Set
// globalThis.__PROJ_NET_DBG__ = true (worker or page scope) to activate them.
const NET_DBG = typeof globalThis !== 'undefined' && globalThis.__PROJ_NET_DBG__ === true;

let module = null;
let contexts = new Map();
let nextContextId = 1;
let logCallbackPtr = null;
let logLevel = 0;

// Handle state for the network callbacks. PROJ's libcurl + emscripten XHR
// shim locks a context-internal pthread mutex again on the same thread
// during grid loads, which fires pthread_mutex_timedlock's `own == self`
// assertion. The callbacks below bypass libcurl.
let nextHandleId = 1;
const handles = new Map();

const PJ_LOG_ERROR = 1;
const PJ_LOG_DEBUG = 2;
const PJ_LOG_TRACE = 3;

async function installNodeXhrPolyfill() {
  if (!isNode || typeof globalThis.XMLHttpRequest !== 'undefined') return;

  const { pathToFileURL } = await import('url');
  const { join } = await import('path');

  // clj-native's http-bridge owns the SharedArrayBuffer round trip and the
  // fetch worker. Only the fetch worker lets Emscripten's synchronous
  // XMLHttpRequest block in Node.js.
  const { dir: assetDir } = await resolveAsset(import.meta.url, 'fetch_worker.mjs', [['.'], ['dist']]);
  const workerUrl = pathToFileURL(join(assetDir, 'fetch_worker.mjs'));

  const syncFetch = await createSyncFetch({ workerUrl });
  await installXhrPolyfill({ syncFetch });
}

async function loadProjModule() {
  const { factory } = await loadEmscriptenModule(import.meta.url, {
    name: 'proj-emscripten.js',
    candidates: [['.'], ['dist']],
  });
  return factory();
}

function makeRangeRequest(url, offset, sizeToRead) {
  try {
    const xhr = new XMLHttpRequest();
    xhr.open('GET', url, false);
    try { xhr.responseType = 'arraybuffer'; } catch (_) {}
    if (offset != null && sizeToRead != null) {
      xhr.setRequestHeader('Range',
        `bytes=${offset}-${offset + sizeToRead - 1}`);
    }
    xhr.send();
    let body = new Uint8Array(0);
    if (xhr.response instanceof ArrayBuffer) {
      body = new Uint8Array(xhr.response);
    } else if (xhr.response instanceof Uint8Array) {
      body = xhr.response;
    } else if (typeof xhr.responseText === 'string' && xhr.responseText.length > 0) {
      body = new TextEncoder().encode(xhr.responseText);
    }
    const headers = {};
    const all = xhr.getAllResponseHeaders ? xhr.getAllResponseHeaders() : '';
    if (all) {
      for (const line of all.split(/\r\n|\n/)) {
        const idx = line.indexOf(':');
        if (idx > 0) {
          headers[line.slice(0, idx).trim().toLowerCase()] =
            line.slice(idx + 1).trim();
        }
      }
    }
    return { status: xhr.status, body, headers };
  } catch (e) {
    return { status: 0, body: new Uint8Array(0), headers: {}, error: e };
  }
}

function writeErrorString(errStrPtr, errMaxSize, msg) {
  if (!errStrPtr || errMaxSize <= 0) return;
  const bytes = new TextEncoder().encode(String(msg ?? ''));
  const n = Math.min(bytes.length, errMaxSize - 1);
  if (n > 0) module.HEAPU8.set(bytes.subarray(0, n), errStrPtr);
  module.HEAPU8[errStrPtr + n] = 0;
}

function installProjNetCallbacks() {
  if (globalThis.__proj_net_open) return;
  if (NET_DBG) console.log('[NET-DBG] INSTALL proj-net callbacks on globalThis');

  globalThis.__proj_net_open = (
    ctx, urlPtr, offset, sizeToRead, bufferPtr, outSizePtr,
    errMaxSize, errStrPtr, _userData,
  ) => {
    try {
      const url = module.UTF8ToString(urlPtr);
      const response = makeRangeRequest(url, offset, sizeToRead);
      if (response.status !== 200 && response.status !== 206) {
        const errMsg = response.error
          ? `Network error: ${response.error.message ?? response.error}`
          : `HTTP ${response.status}`;
        if (NET_DBG) console.log(`[NET-DBG] OPEN-FAIL ctx=${ctx} url=${url} offset=${offset} size=${sizeToRead} status=${response.status} err=${errMsg}`);
        writeErrorString(errStrPtr, errMaxSize, errMsg);
        return 0;
      }
      const bytesRead = Math.min(response.body.length, sizeToRead);
      if (bytesRead > 0) {
        module.HEAPU8.set(response.body.subarray(0, bytesRead), bufferPtr);
      }
      if (outSizePtr) module.setValue(outSizePtr, bytesRead, 'i32');
      const id = nextHandleId++;
      handles.set(id, { url, headers: response.headers });
      if (NET_DBG) console.log(`[NET-DBG] OPEN-OK ctx=${ctx} h=${id} url=${url} offset=${offset} size=${sizeToRead} bytes=${bytesRead}`);
      return id;
    } catch (e) {
      if (NET_DBG) console.log(`[NET-DBG] OPEN-THROW ctx=${ctx} err=${e?.message ?? e}`);
      writeErrorString(errStrPtr, errMaxSize, e?.message ?? String(e));
      return 0;
    }
  };

  globalThis.__proj_net_close = (ctx, handle, _userData) => {
    if (NET_DBG) console.log(`[NET-DBG] CLOSE ctx=${ctx} h=${handle}`);
    handles.delete(handle);
  };

  globalThis.__proj_net_get_header = (ctx, handle, namePtr, _userData) => {
    const entry = handles.get(handle);
    if (!entry) {
      if (NET_DBG) console.log(`[NET-DBG] HDR-NOENTRY ctx=${ctx} h=${handle}`);
      return 0;
    }
    const name = module.UTF8ToString(namePtr).toLowerCase();
    const value = entry.headers?.[name];
    if (!value) {
      if (NET_DBG) console.log(`[NET-DBG] HDR-MISS ctx=${ctx} h=${handle} name=${name}`);
      return 0;
    }
    return module.stringToNewUTF8(value);
  };

  globalThis.__proj_net_read_range = (
    ctx, handle, offset, sizeToRead, bufferPtr,
    errMaxSize, errStrPtr, _userData,
  ) => {
    try {
      const entry = handles.get(handle);
      if (!entry) {
        if (NET_DBG) console.log(`[NET-DBG] READ-NOENTRY ctx=${ctx} h=${handle} offset=${offset} size=${sizeToRead}`);
        writeErrorString(errStrPtr, errMaxSize, 'Invalid handle');
        return 0;
      }
      const response = makeRangeRequest(entry.url, offset, sizeToRead);
      if (response.status !== 200 && response.status !== 206) {
        const errMsg = response.error
          ? `Network error: ${response.error.message ?? response.error}`
          : `HTTP ${response.status}`;
        if (NET_DBG) console.log(`[NET-DBG] READ-FAIL ctx=${ctx} h=${handle} url=${entry.url} offset=${offset} size=${sizeToRead} status=${response.status} err=${errMsg}`);
        writeErrorString(errStrPtr, errMaxSize, errMsg);
        return 0;
      }
      const bytesRead = Math.min(response.body.length, sizeToRead);
      if (bytesRead > 0) {
        module.HEAPU8.set(response.body.subarray(0, bytesRead), bufferPtr);
      }
      entry.headers = response.headers;
      if (NET_DBG) console.log(`[NET-DBG] READ-OK ctx=${ctx} h=${handle} url=${entry.url} offset=${offset} size=${sizeToRead} bytes=${bytesRead}`);
      return bytesRead;
    } catch (e) {
      if (NET_DBG) console.log(`[NET-DBG] READ-THROW ctx=${ctx} h=${handle} err=${e?.message ?? e}`);
      writeErrorString(errStrPtr, errMaxSize, e?.message ?? String(e));
      return 0;
    }
  };
}

// Signatures are PROJ's own callback types under wasm32, where pointers and
// size_t are i32 and `unsigned long long offset` is i64 (j).
let netCallbackPtrs = null;

function networkCallbackPointers() {
  // One set per module, not per context: each addFunction call grows the
  // wasm function table, and context_create runs per context.
  if (netCallbackPtrs) return netCallbackPtrs;
  installProjNetCallbacks();
  // An i64 parameter reaches JS as a BigInt, and the range arithmetic below
  // mixes offsets with Numbers, so narrow it here. An offset is a position in
  // a grid file, so it is far inside 2^53.
  const openFn = globalThis.__proj_net_open;
  const readFn = globalThis.__proj_net_read_range;
  netCallbackPtrs = {
    open: module.addFunction(
      (ctx, urlPtr, offset, sizeToRead, bufferPtr, outSizePtr, errMaxSize, errStrPtr, userData) =>
        openFn(ctx, urlPtr, Number(offset), sizeToRead, bufferPtr, outSizePtr, errMaxSize, errStrPtr, userData),
      'iiijiiiiii'),
    close: module.addFunction(globalThis.__proj_net_close, 'viii'),
    getHeader: module.addFunction(globalThis.__proj_net_get_header, 'iiiii'),
    readRange: module.addFunction(
      (ctx, handle, offset, sizeToRead, bufferPtr, errMaxSize, errStrPtr, userData) =>
        readFn(ctx, handle, Number(offset), sizeToRead, bufferPtr, errMaxSize, errStrPtr, userData),
      'iiijiiiii'),
  };
  return netCallbackPtrs;
}

function readStringArray(mod, listPtr) {
  const strings = [];
  let offset = 0;
  while (true) {
    const strPtr = mod.getValue(listPtr + offset * 4, '*');
    if (strPtr === 0) break;
    strings.push(mod.UTF8ToString(strPtr));
    offset++;
  }
  return strings;
}

function readOutParams(mod, outParamAllocs) {
  const result = {};
  for (const { ptr, size, field } of outParamAllocs) {
    switch (field.type) {
      case 'double':
        result[field.key] = mod.getValue(ptr, 'double');
        break;
      case 'int':
        result[field.key] = mod.getValue(ptr, 'i32');
        break;
      case 'string': {
        const strPtr = mod.getValue(ptr, '*');
        result[field.key] = strPtr ? mod.UTF8ToString(strPtr) : null;
        break;
      }
      case 'double-array': {
        const n = size / 8;
        const values = [];
        for (let j = 0; j < n; j++) {
          values.push(mod.getValue(ptr + j * 8, 'double'));
        }
        result[field.key] = values;
        break;
      }
    }
  }
  return result;
}

function freeOutParams(mod, outParamAllocs) {
  for (const { ptr } of outParamAllocs) mod._free(ptr);
}

function readStructList(mod, listPtr, count, structFields) {
  const readStr = (ptr) => (ptr ? mod.UTF8ToString(ptr) : null);
  const entries = [];
  for (let i = 0; i < count; i++) {
    const s = mod.getValue(listPtr + i * 4, '*');
    const entry = {};
    for (const field of structFields) {
      const { key, type, offset } = field;
      switch (type) {
        case 'string':
          entry[key] = readStr(mod.getValue(s + offset, '*'));
          break;
        case 'int':
          entry[key] = mod.getValue(s + offset, 'i32');
          break;
        case 'double':
          entry[key] = mod.getValue(s + offset, 'double');
          break;
        case 'boolean':
          entry[key] = mod.getValue(s + offset, 'i32') !== 0;
          break;
      }
    }
    entries.push(entry);
  }
  return entries;
}

// Reads each coord buffer back out of the wasm heap and frees it. Runs
// before decodeCallResult, because a PROJ destroy in that step can grow the
// heap and move the buffers.
function readCoordDataAndFree(mod, coordAllocations) {
  const coordData = [];
  for (const alloc of coordAllocations) {
    coordData.push(
      Array.from(mod.HEAPF64.subarray(alloc.heapOffset, alloc.heapOffset + alloc.numFloats)),
    );
    mod._free(alloc.mallocPtr);
  }
  return coordData;
}

// Allocates everything the call needs in the wasm heap and patches `args`
// and `argTypes` in place to point at it. Mirrors decodeCallResult, which
// reads the same allocations back out and frees them. Returns the handles
// that decodeCallResult and readCoordDataAndFree need.
function prepareCallArgs(mod, args, argTypes, opts) {
  const { projReturns, coordArrays, outFields, structParamsCreate } = opts;

  let coordAllocations = null;
  if (coordArrays && coordArrays.length > 0) {
    coordAllocations = [];
    for (const ca of coordArrays) {
      const mallocPtr = mod._malloc(ca.numFloats * 8);
      const heapOffset = mallocPtr / 8;
      mod.HEAPF64.set(ca.data, heapOffset);
      args[ca.argIdx] = mallocPtr;
      coordAllocations.push({ mallocPtr, heapOffset, numFloats: ca.numFloats });
    }
  }

  let outParamAllocs = null;
  if (projReturns === 'out-params' && outFields) {
    outParamAllocs = [];
    for (const field of outFields) {
      const size = field.type === 'double-array'
        ? args[field.countArgIdx] * 8
        : (field.type === 'double' ? 8 : 4);
      const ptr = mod._malloc(size);
      outParamAllocs.push({ ptr, size, field });
      args.push(ptr);
      argTypes.push('number');
    }
  }

  let paramsPtrLocal = null;
  if (projReturns === 'struct-list') {
    const countPtr = mod._malloc(4);
    mod.setValue(countPtr, 0, 'i32');
    args[args.length - 1] = countPtr;
    if (structParamsCreate) {
      paramsPtrLocal = mod.ccall(structParamsCreate, 'number', [], []);
      args[args.length - 2] = paramsPtrLocal;
    }
  }

  return { coordAllocations, outParamAllocs, paramsPtrLocal };
}

// Turns the raw ccall return into the value the caller gets, and releases
// every allocation the call made. `args` still carries the out-param and
// struct-list pointers the prologue appended.
function decodeCallResult(mod, rawResult, opts) {
  const { projReturns, args, outParamAllocs, paramsPtrLocal,
    structParamsDestroy, structDestroyFn, structFields } = opts;

  if (projReturns === 'string-list' && rawResult !== 0) {
    return readStringArray(mod, rawResult);
  }

  if (projReturns === 'struct-list') {
    const countPtr = args[args.length - 1];
    let entries = [];
    if (rawResult !== 0) {
      const count = mod.getValue(countPtr, 'i32');
      entries = readStructList(mod, rawResult, count, structFields);
      mod.ccall(structDestroyFn, null, ['number'], [rawResult]);
    }
    if (paramsPtrLocal && structParamsDestroy) {
      mod.ccall(structParamsDestroy, null, ['number'], [paramsPtrLocal]);
    }
    mod._free(countPtr);
    return entries;
  }

  if (projReturns === 'out-params' && outParamAllocs) {
    const fields = rawResult === 0 ? null : readOutParams(mod, outParamAllocs);
    freeOutParams(mod, outParamAllocs);
    return fields;
  }

  return rawResult;
}

export const methods = {
  context_create: async (opts) => {
    const enableNetwork = (opts?.enableNetwork ?? true) ? 1 : 0;
    const ptr = module.ccall('proj_context_create', 'number', [], []);
    module.ccall('proj_context_set_database_path', 'number',
      ['number', 'string'], [ptr, '/proj/proj.db']);
    module.ccall('proj_context_set_enable_network', 'number',
      ['number', 'number'], [ptr, enableNetwork]);
    if (enableNetwork) {
      // Custom callbacks, so grid fetches skip PROJ's libcurl and XHR shim,
      // whose re-entrant context mutex deadlocks during grid loads.
      const cb = networkCallbackPointers();
      const netRc = module.ccall('proj_context_set_network_callbacks', 'number',
        ['number', 'number', 'number', 'number', 'number', 'number'],
        [ptr, cb.open, cb.close, cb.getHeader, cb.readRange, 0]);
      if (NET_DBG) console.log(`[NET-DBG] SETUP-NET ctx-ptr=${ptr} rc=${netRc}`);
    }
    if (logCallbackPtr) {
      module.ccall('proj_log_func', null,
        ['number', 'number', 'number'], [ptr, 0, logCallbackPtr]);
      module.ccall('proj_log_level', 'number',
        ['number', 'number'], [ptr, PJ_LOG_ERROR]);
    }
    const ctxId = nextContextId++;
    contexts.set(ctxId, ptr);
    return { ctxId, ptr };
  },

  set_log_level: async (level) => {
    logLevel = level || 0;
    return { ok: true, level: logLevel };
  },

  context_destroy: async (ctxId) => {
    const ptr = contexts.get(ctxId);
    if (ptr) {
      module.ccall('proj_context_destroy', null, ['number'], [ptr]);
      contexts.delete(ctxId);
    }
    return { ok: true };
  },

  ccall: async (fnName, returnType, argTypes, args, extra = {}) => {
    const { projReturns, coordArrays, outFields, structParamsCreate,
      structParamsDestroy, structDestroyFn, structFields } = extra;

    const { coordAllocations, outParamAllocs, paramsPtrLocal } =
      prepareCallArgs(module, args, argTypes,
        { projReturns, coordArrays, outFields, structParamsCreate });

    const rawResult = module.ccall(fnName, returnType, argTypes, args);

    const coordData = coordAllocations
      ? readCoordDataAndFree(module, coordAllocations)
      : null;

    const value = decodeCallResult(module, rawResult, {
      projReturns, args, outParamAllocs, paramsPtrLocal,
      structParamsDestroy, structDestroyFn, structFields,
    });

    return coordAllocations ? { result: value, coordData } : value;
  },

  // heapHelpers supplies malloc/free/heap*_get/heap*_set/get_value/
  // set_value/string_to_utf8/utf8_to_string/utf8_byte_length. heap*_get
  // returns a typed-array slice, which structured clone moves without
  // per-element boxing.
  ...heapHelpers(() => module),

  read_string_array: async (ptr, _count) => readStringArray(module, ptr),

  // Releases this handler's reference on the fetch worker that init spawned.
  shutdown: shutdownMethod,
};

// worker-router calls this once for each worker at pool terminate, through
// the `destroy` that the generated proj-handler.mjs re-exports. It closes
// the fetch worker that init spawned when no client called `shutdown`.
export const destroy = moduleDestroy;

export async function init(initArgs, ctx) {
  const args = initArgs ?? {};
  if (!args.dbBytes) {
    throw new Error('proj-handler.create: missing required initArgs.dbBytes');
  }

  await installNodeXhrPolyfill();
  module = await loadProjModule();
  // After this call, the substrate merges {heap-bytes, brk} into BUSY-INC /
  // BUSY-DEC events.
  ctx?.attachEmscriptenModule?.(module);
  installProjNetCallbacks();

  const files = { 'proj.db': args.dbBytes };
  if (args.iniBytes) files['proj.ini'] = args.iniBytes;
  stageFiles(module, files, '/proj');

  logLevel = Number(args.logLevel ?? 0);

  logCallbackPtr = module.addFunction((_userData, level, msgPtr) => {
    const msg = module.UTF8ToString(msgPtr);
    const levelName = level === PJ_LOG_ERROR ? 'ERROR'
      : level === PJ_LOG_DEBUG ? 'DEBUG'
      : level === PJ_LOG_TRACE ? 'TRACE'
      : `L${level}`;
    if (level === PJ_LOG_ERROR || logLevel >= 2) {
      console.log(`[PROJ ${levelName}] ${msg}`);
    }
  }, 'viii');
}
