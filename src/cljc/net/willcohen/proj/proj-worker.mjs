/**
 * proj-worker.mjs - Platform-aware PROJ worker for browser Web Workers and Node.js worker_threads.
 *
 * All PROJ operations run here so blocking (synchronous fetch for grid files) is allowed --
 * browsers prohibit blocking the main thread, but workers can block freely.
 *
 * On Node.js, sets up an XMLHttpRequest polyfill because Emscripten's FETCH support
 * requires XMLHttpRequest but Node.js doesn't have it natively. The polyfill uses xhr2
 * for async requests and a fetch-worker (via SharedArrayBuffer + Atomics) for sync requests.
 *
 * Message protocol from the main thread (proj-loader.mjs):
 *   init            - Load Emscripten module, write proj.db/proj.ini to virtual FS
 *   context_create  - Create PROJ context with network + logging configured
 *   ccall           - Generic PROJ function call forwarding (with optional coordArrays
 *                     for inline coord data transfer: temp malloc, copy in, transform,
 *                     copy out, free)
 *   malloc / free   - Direct WASM memory management
 *   heapf64_set/get - Float64 heap read/write
 *   read_string_array - Read null-terminated char** from WASM memory
 *   string_to_utf8 / utf8_to_string - String memory operations
 *   shutdown        - Terminate fetch worker (Node.js) and clean up
 */

console.debug('PROJ: worker script starting');

let module = null;
let contexts = new Map();  // ctxId -> pointer
let nextContextId = 1;
let logCallbackPtr = null;
let logLevel = 0;  // 0=errors only, 2=debug output

// PJ_LOG_LEVEL constants from PROJ
const PJ_LOG_NONE = 0;
const PJ_LOG_ERROR = 1;
const PJ_LOG_DEBUG = 2;
const PJ_LOG_TRACE = 3;

// Platform abstraction for worker communication
let postMessage;
let onMessage;

// Detect environment and set up communication
const isNode = typeof process !== 'undefined' && process.versions?.node;

// XMLHttpRequest polyfill for Node.js.
// Emscripten's -sFETCH=1 requires XMLHttpRequest for network operations.
// Uses xhr2 for async requests (open(method, url, true)).
// For sync requests (open(method, url, false) -- which Emscripten uses for grid fetching),
// delegates to a fetch-worker via SharedArrayBuffer + Atomics because xhr2 doesn't
// support synchronous mode.
if (isNode && typeof globalThis.XMLHttpRequest === 'undefined') {
  const { Worker } = await import('worker_threads');
  const { fileURLToPath, pathToFileURL } = await import('url');
  const { dirname, join } = await import('path');
  const { createRequire } = await import('module');

  const __filename = fileURLToPath(import.meta.url);
  const __dirname = dirname(__filename);
  const require = createRequire(import.meta.url);

  // Use xhr2 for async requests
  const XHR2 = require('xhr2');

  // Set up fetch worker for sync HTTP requests (xhr2 doesn't support sync)
  const fetchWorkerPath = pathToFileURL(join(__dirname, 'fetch-worker.mjs'));

  const CONTROL_BUFFER_SIZE = 8;
  const META_BUFFER_SIZE = 16;
  const DATA_BUFFER_SIZE = 50 * 1024 * 1024;

  const controlSAB = new SharedArrayBuffer(CONTROL_BUFFER_SIZE);
  const metaSAB = new SharedArrayBuffer(META_BUFFER_SIZE);
  const dataSAB = new SharedArrayBuffer(DATA_BUFFER_SIZE);

  const controlBuffer = new Int32Array(controlSAB);
  const metaBuffer = new Int32Array(metaSAB);
  const dataBuffer = new Uint8Array(dataSAB);

  let fetchWorkerReady = false;
  const fetchWorker = new Worker(fetchWorkerPath);
  fetchWorker.unref();
  fetchWorker.on('message', (msg) => {
    if (msg.status === 'ready') {
      fetchWorkerReady = true;
    }
  });
  fetchWorker.postMessage({
    cmd: 'init',
    controlBuffer: controlSAB,
    metaBuffer: metaSAB,
    dataBuffer: dataSAB
  });

  globalThis.__projFetchWorker = fetchWorker;

  while (!fetchWorkerReady) {
    await new Promise(r => setTimeout(r, 1));
  }

  function syncFetch(url, headers) {
    const urlBytes = new TextEncoder().encode(url);
    const headersStr = JSON.stringify(headers);
    const headersBytes = new TextEncoder().encode(headersStr);

    const view = new DataView(dataSAB);
    view.setInt32(0, urlBytes.length, true);
    view.setInt32(4, headersBytes.length, true);
    dataBuffer.set(urlBytes, 8);
    dataBuffer.set(headersBytes, 8 + urlBytes.length);

    Atomics.store(controlBuffer, 1, 0);
    Atomics.store(controlBuffer, 0, 1);
    Atomics.notify(controlBuffer, 0, 1);

    Atomics.wait(controlBuffer, 1, 0);

    const status = Atomics.load(metaBuffer, 0);
    const bodyLength = Atomics.load(metaBuffer, 1);
    const headersLength = Atomics.load(metaBuffer, 2);

    const body = dataBuffer.slice(0, bodyLength);
    const responseHeaders = new TextDecoder().decode(
      dataBuffer.slice(bodyLength, bodyLength + headersLength)
    );

    return { status, body, headers: responseHeaders };
  }

  // Wrapper that uses xhr2 for async, our sync worker for sync
  let xhrIdCounter = 0;
  globalThis.XMLHttpRequest = class XMLHttpRequest {
    constructor() {
      this._id = ++xhrIdCounter;
      this._xhr2 = new XHR2();
      this._async = true;
      this._method = 'GET';
      this._url = null;
      this._headers = {};
      this._syncResponseHeaders = null;
      this._status = 0;

      // Proxy properties from xhr2
      ['readyState', 'status', 'statusText', 'response', 'responseText', 'responseType',
       'responseURL', 'onreadystatechange', 'onload', 'onerror', 'onprogress'].forEach(prop => {
        Object.defineProperty(this, prop, {
          get: () => this._async ? this._xhr2[prop] : this['_' + prop],
          set: (v) => {
            if (this._async) {
              this._xhr2[prop] = v;
            } else {
              this['_' + prop] = v;
            }
          }
        });
      });
    }

    open(method, url, async = true) {
      this._method = method;
      this._url = url;
      this._async = async;
      if (async) {
        this._xhr2.open(method, url, async);
      } else {
        this._readyState = 1;
      }
    }

    setRequestHeader(name, value) {
      this._headers[name] = value;
      if (this._async) {
        this._xhr2.setRequestHeader(name, value);
      }
    }

    getResponseHeader(name) {
      if (this._async) {
        return this._xhr2.getResponseHeader(name);
      }
      return this._syncResponseHeaders?.[name.toLowerCase()] || null;
    }

    getAllResponseHeaders() {
      let result;
      if (this._async) {
        result = this._xhr2.getAllResponseHeaders();
      } else {
        if (!this._syncResponseHeaders) {
          result = '';
        } else {
          // Emscripten's unpack_response_headers parser expects each header line to end
          // with \r\n, and the entire block must end with \r\n. Without the trailing
          // \r\n, the last header is silently dropped -- which can cause PROJ to miss
          // Content-Range headers needed for grid file parsing.
          result = Object.entries(this._syncResponseHeaders)
            .map(([k, v]) => `${k}: ${v}`)
            .join('\r\n') + '\r\n';
        }
      }
      return result;
    }

    send(body = null) {
      if (this._async) {
        this._xhr2.send(body);
      } else {
        // Sync mode - use fetch worker with Atomics
        try {
          const response = syncFetch(this._url, this._headers);

          this._status = response.status;
          this._statusText = response.status >= 200 && response.status < 300 ? 'OK' : 'Error';
          this._responseURL = this._url;
          this._readyState = 4;

          // Parse response headers
          this._syncResponseHeaders = {};
          if (response.headers) {
            for (const line of response.headers.split('\r\n')) {
              const idx = line.indexOf(':');
              if (idx > 0) {
                const key = line.slice(0, idx).trim().toLowerCase();
                const value = line.slice(idx + 1).trim();
                this._syncResponseHeaders[key] = value;
              }
            }
          }

          // Copy response body to fresh ArrayBuffer
          const arrayBuffer = new ArrayBuffer(response.body.byteLength);
          new Uint8Array(arrayBuffer).set(response.body);
          this._response = arrayBuffer;
          this._responseText = '';

          if (this._onreadystatechange) this._onreadystatechange();
          if (this._onload) this._onload();
        } catch (err) {
          this._status = 0;
          this._readyState = 4;
          if (this._onerror) this._onerror(err);
          if (this._onreadystatechange) this._onreadystatechange();
        }
      }
    }

    abort() {
      if (this._async) {
        this._xhr2.abort();
      } else {
        this._readyState = 0;
      }
    }
  };
}

if (isNode) {
  // Node.js worker_threads
  const { parentPort } = await import('worker_threads');
  postMessage = (msg) => parentPort.postMessage(msg);
  onMessage = (handler) => parentPort.on('message', handler);
} else {
  // Browser Web Worker
  postMessage = (msg) => self.postMessage(msg);
  onMessage = (handler) => { self.onmessage = (e) => handler(e.data); };
}

function readStructList(module, listPtr, count, structFields) {
  const readStr = (ptr) => ptr ? module.UTF8ToString(ptr) : null;
  const entries = [];
  for (let i = 0; i < count; i++) {
    const s = module.getValue(listPtr + i * 4, '*');
    const entry = {};
    for (const field of structFields) {
      const { key, type, offset } = field;
      switch (type) {
        case 'string':
          entry[key] = readStr(module.getValue(s + offset, '*'));
          break;
        case 'int':
          entry[key] = module.getValue(s + offset, 'i32');
          break;
        case 'double':
          entry[key] = module.getValue(s + offset, 'double');
          break;
        case 'boolean':
          entry[key] = module.getValue(s + offset, 'i32') !== 0;
          break;
      }
    }
    entries.push(entry);
  }
  return entries;
}

// Handle messages from main thread
onMessage(async (data) => {
  const { id, cmd } = data;

  try {
    let result;

    switch (cmd) {
      case 'init': {
        // Dynamically imports the Emscripten module (platform-specific path resolution),
        // writes proj.db and proj.ini to Emscripten's virtual filesystem at /proj/,
        // and sets up PROJ log callback via addFunction. addFunction is safe here --
        // the addFunction/GraalVM type mismatch only affects GraalVM's WASM engine,
        // not Emscripten running in a real JS engine.
        // Each worker gets its own Emscripten module instance with its own memory.
        let createProjModule;

        if (isNode) {
          // Node.js: use file path relative to this module
          const { fileURLToPath, pathToFileURL } = await import('url');
          const { dirname, join } = await import('path');
          const __filename = fileURLToPath(import.meta.url);
          const __dirname = dirname(__filename);

          const modulePath = pathToFileURL(join(__dirname, 'proj-emscripten.js')).href;
          const imported = await import(modulePath);
          createProjModule = imported.default;
        } else {
          // Browser: use URL relative to this module
          const { default: mod } = await import('./proj-emscripten.js');
          createProjModule = mod;
        }

        module = await createProjModule({});

        // Write proj.db to virtual filesystem
        module.FS.mkdir('/proj');

        // Handle different data formats (ArrayBuffer, Uint8Array, Buffer)
        const projDbArray = data.projDb instanceof Uint8Array
          ? data.projDb
          : new Uint8Array(data.projDb);

        module.FS.writeFile('/proj/proj.db', projDbArray);
        module.FS.writeFile('/proj/proj.ini', data.projIni);

        logCallbackPtr = module.addFunction((userData, level, msgPtr) => {
          const msg = module.UTF8ToString(msgPtr);
          const levelName = level === PJ_LOG_ERROR ? 'ERROR' :
                           level === PJ_LOG_DEBUG ? 'DEBUG' :
                           level === PJ_LOG_TRACE ? 'TRACE' : `L${level}`;
          if (level === PJ_LOG_ERROR || logLevel >= 2) {
            console.log(`[PROJ ${levelName}] ${msg}`);
          }
        }, 'viii');

        result = { status: 'ready' };
        break;
      }

      case 'context_create': {
        const ptr = module.ccall('proj_context_create', 'number', [], []);
        module.ccall('proj_context_set_database_path', 'number',
          ['number', 'string'], [ptr, '/proj/proj.db']);
        module.ccall('proj_context_set_enable_network', 'number',
          ['number', 'number'], [ptr, 1]);

        if (logCallbackPtr) {
          module.ccall('proj_log_func', null,
            ['number', 'number', 'number'], [ptr, 0, logCallbackPtr]);
          module.ccall('proj_log_level', 'number',
            ['number', 'number'], [ptr, PJ_LOG_ERROR]);
        }

        const ctxId = nextContextId++;
        contexts.set(ctxId, ptr);
        result = { ctxId, ptr };
        break;
      }

      case 'set_log_level': {
        logLevel = data.level || 0;
        result = { ok: true, level: logLevel };
        break;
      }

      case 'context_destroy': {
        const { ctxId } = data;
        const ptr = contexts.get(ctxId);
        if (ptr) {
          module.ccall('proj_context_destroy', null, ['number'], [ptr]);
          contexts.delete(ctxId);
        }
        result = { ok: true };
        break;
      }

      case 'ccall': {
        const { fn: fnName, returnType, argTypes, args, projReturns, coordArrays } = data;

        // If coord arrays attached, alloc temp WASM memory and copy data in
        let coordAllocations = null;
        if (coordArrays && coordArrays.length > 0) {
          coordAllocations = [];
          for (const ca of coordArrays) {
            const bytesNeeded = ca.numFloats * 8;
            const mallocPtr = module._malloc(bytesNeeded);
            const heapOffset = mallocPtr / 8;
            module.HEAPF64.set(ca.data, heapOffset);
            args[ca.argIdx] = mallocPtr;
            coordAllocations.push({ mallocPtr, heapOffset, numFloats: ca.numFloats });
          }
        }

        // For struct-list: allocate count pointer and optional params
        if (projReturns === 'struct-list') {
          const countPtr = module._malloc(4);
          module.setValue(countPtr, 0, 'i32');
          args[args.length - 1] = countPtr;
          if (data.structParamsCreate) {
            const paramsPtr = module.ccall(data.structParamsCreate, 'number', [], []);
            args[args.length - 2] = paramsPtr;
            data._paramsPtr = paramsPtr;
          }
        }

        const rawResult = module.ccall(fnName, returnType, argTypes, args);

        // Handle special return types that need post-processing in worker
        if (coordAllocations) {
          const coordData = [];
          for (const alloc of coordAllocations) {
            coordData.push(
              Array.from(module.HEAPF64.subarray(alloc.heapOffset, alloc.heapOffset + alloc.numFloats))
            );
            module._free(alloc.mallocPtr);
          }
          if (projReturns === 'string-list' && rawResult !== 0) {
            const strings = [];
            let offset = 0;
            while (true) {
              const strPtr = module.getValue(rawResult + offset * 4, '*');
              if (strPtr === 0) break;
              strings.push(module.UTF8ToString(strPtr));
              offset++;
            }
            result = { result: strings, coordData };
          } else if (projReturns === 'struct-list' && rawResult !== 0) {
            const countPtr = args[args.length - 1];
            const count = module.getValue(countPtr, 'i32');
            const structResult = readStructList(module, rawResult, count, data.structFields);
            module.ccall(data.structDestroyFn, null, ['number'], [rawResult]);
            if (data._paramsPtr && data.structParamsDestroy) {
              module.ccall(data.structParamsDestroy, null, ['number'], [data._paramsPtr]);
            }
            module._free(countPtr);
            result = { result: structResult, coordData };
          } else if (projReturns === 'struct-list' && rawResult === 0) {
            const countPtr = args[args.length - 1];
            if (data._paramsPtr && data.structParamsDestroy) {
              module.ccall(data.structParamsDestroy, null, ['number'], [data._paramsPtr]);
            }
            module._free(countPtr);
            result = { result: [], coordData };
          } else {
            result = { result: rawResult, coordData };
          }
        } else {
          if (projReturns === 'string-list' && rawResult !== 0) {
            const strings = [];
            let offset = 0;
            while (true) {
              const strPtr = module.getValue(rawResult + offset * 4, '*');
              if (strPtr === 0) break;
              strings.push(module.UTF8ToString(strPtr));
              offset++;
            }
            result = strings;
          } else if (projReturns === 'struct-list' && rawResult !== 0) {
            const countPtr = args[args.length - 1];
            const count = module.getValue(countPtr, 'i32');
            result = readStructList(module, rawResult, count, data.structFields);
            module.ccall(data.structDestroyFn, null, ['number'], [rawResult]);
            if (data._paramsPtr && data.structParamsDestroy) {
              module.ccall(data.structParamsDestroy, null, ['number'], [data._paramsPtr]);
            }
            module._free(countPtr);
          } else if (projReturns === 'struct-list' && rawResult === 0) {
            const countPtr = args[args.length - 1];
            if (data._paramsPtr && data.structParamsDestroy) {
              module.ccall(data.structParamsDestroy, null, ['number'], [data._paramsPtr]);
            }
            module._free(countPtr);
            result = [];
          } else {
            result = rawResult;
          }
        }
        break;
      }

      case 'malloc': {
        const { size } = data;
        result = module._malloc(size);
        break;
      }

      case 'free': {
        const { ptr } = data;
        module._free(ptr);
        result = { ok: true };
        break;
      }

      case 'heapf64_set': {
        const { offset, values } = data;
        module.HEAPF64.set(values, offset);
        result = { ok: true };
        break;
      }

      case 'heapf64_get': {
        const { offset, length } = data;
        result = Array.from(module.HEAPF64.subarray(offset, offset + length));
        break;
      }

      case 'read_string_array': {
        // Read a null-terminated array of string pointers (char**)
        const { ptr } = data;
        const strings = [];
        let offset = 0;
        while (true) {
          const strPtr = module.getValue(ptr + offset * 4, '*');
          if (strPtr === 0) break;
          strings.push(module.UTF8ToString(strPtr));
          offset++;
        }
        result = strings;
        break;
      }

      case 'heapu8_set': {
        const { offset, values } = data;
        module.HEAPU8.set(values, offset);
        result = { ok: true };
        break;
      }

      case 'heapu8_get': {
        const { offset, length } = data;
        result = Array.from(module.HEAPU8.subarray(offset, offset + length));
        break;
      }

      case 'string_to_utf8': {
        const { str, ptr, maxLength } = data;
        module.stringToUTF8(str, ptr, maxLength);
        result = { ok: true };
        break;
      }

      case 'utf8_to_string': {
        const { ptr } = data;
        result = module.UTF8ToString(ptr);
        break;
      }

      case 'shutdown': {
        // Shutdown the fetch worker if it exists
        if (globalThis.__projFetchWorker) {
          globalThis.__projFetchWorker.postMessage({ cmd: 'shutdown' });
          globalThis.__projFetchWorker.terminate();
          globalThis.__projFetchWorker = null;
        }
        result = { ok: true };
        break;
      }

      default:
        throw new Error(`Unknown command: ${cmd}`);
    }

    postMessage({ id, result });
  } catch (error) {
    postMessage({ id, error: error.message, stack: error.stack });
  }
});

// Signal that worker is ready to receive messages
postMessage({ id: 0, result: { status: 'worker_loaded' } });
