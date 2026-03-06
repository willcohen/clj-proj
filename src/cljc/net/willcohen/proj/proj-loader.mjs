/**
 * Main-thread orchestrator for PROJ WASM initialization.
 *
 * Two roles:
 * (a) GraalVM initialization via initialize() -- loads WASM binary and proj.db from
 *     JVM resources passed as ProxyObject callbacks.
 * (b) Worker pool management via initWithWorkers() -- handles environment detection,
 *     SharedArrayBuffer availability, shared WebAssembly.Memory creation, resource
 *     loading, and worker spawning for browser and Node.js.
 *
 * For workers, message passing uses a promise-per-call pattern: each outgoing message
 * gets a unique callIdCounter, and the promise resolver is stored in a pendingCalls Map.
 *
 * @module proj-loader
 */

// A single, cached instance of the initialized module.
let projModuleInstance = null;

/**
 * Detects the current JavaScript environment.
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
 * Converts a GraalVM ByteBuffer or similar object to a Uint8Array.
 * @param {*} bufferLike - A ByteBuffer-like object from GraalVM
 * @returns {Uint8Array} - The converted byte array
 */
function toUint8Array(bufferLike) {
    console.debug("toUint8Array called with type:", typeof bufferLike,
                "length:", bufferLike?.length,
                "constructor:", bufferLike?.constructor?.name);

    if (bufferLike instanceof Uint8Array) {
        console.debug("toUint8Array: already Uint8Array");
        return bufferLike;
    }
    if (bufferLike instanceof ArrayBuffer) {
        const arr = new Uint8Array(bufferLike);
        console.debug("toUint8Array: ArrayBuffer, size:", arr.byteLength,
                    "first bytes:", Array.from(arr.slice(0, 10)));
        return arr;
    }

    // GraalVM: Java byte array passed as a host object
    if (bufferLike && typeof bufferLike === 'object' && bufferLike.length !== undefined) {
        try {
            console.debug("toUint8Array: array-like conversion for length:", bufferLike.length);
            const arr = new Uint8Array(bufferLike.length);
            for (let i = 0; i < bufferLike.length; i++) {
                // Java byte arrays have signed bytes (-128 to 127)
                const byte = bufferLike[i];
                arr[i] = byte < 0 ? byte + 256 : byte;
            }
            console.debug("toUint8Array: array-like object, size:", arr.byteLength,
                        "first bytes:", Array.from(arr.slice(0, 10)));
            return arr;
        } catch (e) {
            console.warn("Failed to copy array-like object:", e);
        }
    }

    // Standard ByteBuffer with array() method
    if (bufferLike.array && typeof bufferLike.array === 'function') {
        try {
            console.debug("toUint8Array: ByteBuffer.array() method");
            const result = new Uint8Array(bufferLike.array());
            console.debug("toUint8Array: ByteBuffer.array(), size:", result.byteLength,
                        "first bytes:", Array.from(result.slice(0, 10)));
            return result;
        } catch (e) {
            console.warn("Failed to call .array() method:", e);
        }
    }

    // GraalVM ByteBuffers with position/limit/get
    if (bufferLike.limit && bufferLike.position !== undefined) {
        try {
            console.debug("toUint8Array: ByteBuffer position/limit/get method");
            const length = bufferLike.limit();
            const arr = new Uint8Array(length);
            const savedPosition = bufferLike.position();
            bufferLike.position(0);
            for (let i = 0; i < length; i++) {
                arr[i] = bufferLike.get();
            }
            bufferLike.position(savedPosition);
            console.debug("toUint8Array: ByteBuffer position/limit/get, size:", arr.byteLength,
                        "first bytes:", Array.from(arr.slice(0, 10)));
            return arr;
        } catch (e) {
            console.warn("Failed to read ByteBuffer byte by byte:", e);
        }
    }

    console.debug("toUint8Array: failed to convert, object properties:", Object.keys(bufferLike || {}));
    throw new Error("Unable to convert buffer-like object to Uint8Array");
}

/**
 * Initializes the PROJ Emscripten module with dynamically loaded database.
 * Uses standard Emscripten FS (no pthreads, no WASMFS, no NODERAWFS).
 * 
 * For GraalVM: Resources are provided via options (projDb, projIni)
 * For Node.js: Resources are loaded from filesystem using fs.readFileSync
 * For Browser: Resources are loaded via fetch
 *
 * All resources are written to Emscripten's virtual filesystem at /proj/
 *
 * @param {object} options - Initialization options.
 * @param {ArrayBuffer} [options.wasmBinary] - The proj.wasm file contents (for GraalVM).
 * @param {Uint8Array} [options.projDb] - The proj.db database file (for GraalVM).
 * @param {string} [options.projIni] - The proj.ini config file (for GraalVM).
 * @param {object} [options.projGrids] - Grid files as {filename: Uint8Array} (for GraalVM).
 * @param {function(string): string} [options.locateFile] - A function to locate the .wasm file (for browsers).
 * @param {function(object)} [options.onSuccess] - Callback when initialization succeeds
 * @param {function(Error)} [options.onError] - Callback when initialization fails
 */
function initialize(options = {}) {
    console.time("PROJ-init");
    console.debug("PROJ: `initialize` called.");
    
    const { onSuccess, onError } = options;
    
    if (!onSuccess || !onError) {
        throw new Error("Both onSuccess and onError callbacks are required");
    }
    
    if (projModuleInstance) {
        console.debug("PROJ: Module already initialized, calling success callback with cached instance.");
        onSuccess(projModuleInstance);
        return;
    }

    // Run the async initialization
    (async () => {
        try {
            const env = detectEnvironment();
            console.debug("PROJ: Detected environment:", env);
            
            // Load database files based on environment
            let projDbData, projIniData;
            
            if (options.projDb && options.projIni) {
                // GraalVM case - resources provided in options
                console.debug("PROJ: Using resources from options (GraalVM)");
                projDbData = toUint8Array(options.projDb);
                projIniData = options.projIni;
            } else if (env === 'node') {
                // Node.js - load from filesystem
                console.debug("PROJ: Loading resources from filesystem (Node.js)");
                const fs = await import('fs');
                const path = await import('path');
                const { fileURLToPath } = await import('url');
                
                const __filename = fileURLToPath(import.meta.url);
                const __dirname = path.dirname(__filename);
                
                projDbData = fs.readFileSync(path.join(__dirname, 'proj.db'));
                projIniData = fs.readFileSync(path.join(__dirname, 'proj.ini'), 'utf8');
                console.debug(`PROJ: Loaded proj.db (${projDbData.length} bytes) and proj.ini`);
            } else if (env === 'browser') {
                // Browser - load via fetch relative to this module's URL
                console.debug("PROJ: Loading resources via fetch (Browser)");
                const baseUrl = new URL('./', import.meta.url).href;
                const [dbResp, iniResp] = await Promise.all([
                    fetch(baseUrl + 'proj.db'),
                    fetch(baseUrl + 'proj.ini')
                ]);
                
                if (!dbResp.ok || !iniResp.ok) {
                    throw new Error(`Failed to fetch resources: db=${dbResp.status}, ini=${iniResp.status}`);
                }
                
                projDbData = new Uint8Array(await dbResp.arrayBuffer());
                projIniData = await iniResp.text();
                console.debug(`PROJ: Fetched proj.db (${projDbData.length} bytes) and proj.ini`);
            } else {
                throw new Error("Unknown environment - cannot load database files");
            }
            
            // Import the Emscripten module
            console.debug("PROJ: Importing proj-emscripten.js");
            const { default: PROJModule } = await import('./proj-emscripten.js');
            console.debug("PROJ: Successfully imported proj-emscripten.js");

            // Prepare module arguments
            const moduleArgs = {
                onRuntimeInitialized: function() {
                    console.debug("PROJ: Runtime initialized, writing files to virtual FS");
                    
                    try {
                        // Create /proj directory and write database files
                        this.FS.mkdir('/proj');
                        this.FS.writeFile('/proj/proj.db', projDbData);
                        this.FS.writeFile('/proj/proj.ini', projIniData);
                        
                        console.debug(`PROJ: Successfully wrote proj.db (${projDbData.length} bytes) and proj.ini to /proj/`);
                        
                        // Handle grid files if provided (GraalVM case)
                        if (options.projGrids) {
                            console.debug("PROJ: Writing grid files to virtual FS");
                            this.FS.mkdir('/proj/grids');
                            
                            for (const [name, bytes] of Object.entries(options.projGrids)) {
                                try {
                                    const gridBytes = toUint8Array(bytes);
                                    this.FS.writeFile(`/proj/grids/${name}`, gridBytes);
                                    console.debug(`PROJ: Wrote grid file ${name} (${gridBytes.byteLength} bytes)`);
                                } catch (e) {
                                    console.error(`PROJ: Failed to write grid file ${name}:`, e);
                                }
                            }
                        }
                        
                        // Verify files were written
                        const files = this.FS.readdir('/proj');
                        console.debug("PROJ: /proj directory contents:", files);
                        
                        // Cache the initialized module
                        projModuleInstance = this;
                        console.timeEnd("PROJ-init");
                        
                        // Call success callback
                        onSuccess(this);
                    } catch (e) {
                        console.error("PROJ: Error writing files to virtual FS:", e);
                        onError(e);
                    }
                },
                
                setStatus: (text) => console.debug(`PROJ-EMCC-STATUS: ${text}`),
                monitorRunDependencies: (left) => console.debug(`PROJ-EMCC-DEPS: ${left} dependencies remaining`)
            };

            // Handle GraalVM case with wasmBinary
            if (options.wasmBinary) {
                console.debug("PROJ: Using provided wasmBinary (GraalVM)");
                const wasmBinaryArray = toUint8Array(options.wasmBinary);
                moduleArgs.wasmBinary = wasmBinaryArray.buffer;
            }

            // Handle browser case with locateFile
            if (options.locateFile) {
                console.debug("PROJ: Using provided locateFile function");
                moduleArgs.locateFile = options.locateFile;
            } else if (env === 'browser') {
                // Default locateFile for browser - resolve relative to this module's URL
                const baseUrl = new URL('./', import.meta.url).href;
                moduleArgs.locateFile = (path) => {
                    return baseUrl + path;
                };
            }

            // Initialize the module
            console.debug("PROJ: Calling PROJModule()");
            await PROJModule(moduleArgs);
            
        } catch (error) {
            console.error("PROJ: Initialization failed:", error);
            console.timeEnd("PROJ-init");
            onError(error);
        }
    })();
}

// Worker pool state
let workerPool = null;
const pendingCalls = new Map();
let callIdCounter = 0;

function sendToWorker(worker, message, transfer = []) {
  return new Promise((resolve, reject) => {
    const id = ++callIdCounter;
    pendingCalls.set(id, { resolve, reject });
    worker.postMessage({ ...message, id }, transfer);
  });
}

function handleWorkerMessage(data) {
  const { id, result, error, stack } = data;
  const pending = pendingCalls.get(id);
  if (pending) {
    pendingCalls.delete(id);
    if (error) {
      const err = new Error(error);
      err.stack = stack;
      pending.reject(err);
    } else {
      pending.resolve(result);
    }
  }
}

/**
 * Loads proj.db and proj.ini from filesystem (Node.js) or fetch (browser).
 * Used by initWithWorkers to load resources once before distributing to workers.
 * @returns {Promise<{projDb: Uint8Array, projIni: string}>}
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
      projIni: fs.readFileSync(path.join(__dirname, 'proj.ini'), 'utf8')
    };
  } else {
    const baseUrl = new URL('./', import.meta.url).href;
    const [dbResp, iniResp] = await Promise.all([
      fetch(baseUrl + 'proj.db'),
      fetch(baseUrl + 'proj.ini')
    ]);

    return {
      projDb: new Uint8Array(await dbResp.arrayBuffer()),
      projIni: await iniResp.text()
    };
  }
}

/**
 * Creates a pool of Web Workers (browser) or worker_threads (Node.js), each running
 * a PROJ WASM instance.
 *
 * Detects SharedArrayBuffer support: Node.js always has it; browsers require
 * crossOriginIsolated (COOP/COEP headers). When available, each worker uses the
 * pthreads WASM build for internal threading. Each worker gets its own independent
 * Emscripten module and memory. Loads proj.db and proj.ini once, then sends to
 * all workers on init.
 *
 * Returns a pool object with sendToWorker(workerIdx, msg) for message routing.
 * Default is 1 worker. Use 'auto' to match hardware concurrency.
 *
 * @param {object} [options]
 * @param {number|'auto'} [options.workers=1] - Number of workers to spawn
 * @returns {Promise<object>} Worker pool object
 */
async function initWithWorkers(options = {}) {
  if (workerPool) {
    return workerPool;
  }

  const env = detectEnvironment();
  // Each worker gets its own Emscripten module with its own memory.
  // The pthreads WASM build uses SharedArrayBuffer internally (between
  // the module and its own pthread workers), but we never share a single
  // WebAssembly.Memory across separate module instances -- that corrupts
  // malloc/MEMFS/SQLite.
  const autoCount = typeof navigator !== 'undefined' && navigator.hardwareConcurrency
    ? navigator.hardwareConcurrency
    : 4;
  const workerCount = options.workers === 'auto'
    ? autoCount
    : (options.workers || autoCount);

  // Detect SharedArrayBuffer support (required by pthreads WASM build)
  // Node.js always has it; browser requires COOP/COEP headers
  const hasSharedArrayBuffer = typeof SharedArrayBuffer !== 'undefined';
  const isCrossOriginIsolated = typeof crossOriginIsolated !== 'undefined' ? crossOriginIsolated : false;
  const canUsePthreads = env === 'node'
    ? hasSharedArrayBuffer
    : (hasSharedArrayBuffer && isCrossOriginIsolated);

  const mode = canUsePthreads ? 'pthreads' : 'single-threaded';
  if (!canUsePthreads) {
    console.info('proj-wasm: SharedArrayBuffer not available. Pthreads WASM build may not load. Enable COOP/COEP headers for full support.');
  }

  // Load resources once
  console.log('proj-wasm: Loading PROJ resources...');
  const { projDb, projIni } = await loadProjResources();
  console.log(`proj-wasm: Loaded proj.db (${projDb.length} bytes)`);

  // Spawn workers
  const workers = [];

  for (let i = 0; i < workerCount; i++) {
    let worker;

    if (env === 'node') {
      // Node.js: use worker_threads
      const { Worker } = await import('worker_threads');
      const { fileURLToPath, pathToFileURL } = await import('url');
      const { dirname, join } = await import('path');

      const __filename = fileURLToPath(import.meta.url);
      const __dirname = dirname(__filename);
      const workerPath = pathToFileURL(join(__dirname, 'proj-worker.mjs'));

      worker = new Worker(workerPath);
      worker.unref();  // Don't keep process alive for this worker
      worker.on('message', handleWorkerMessage);
      worker.on('error', (err) => console.error(`Worker ${i} error:`, err));
    } else {
      // Browser: use Web Workers
      const workerUrl = new URL('./proj-worker.mjs', import.meta.url);
      let effectiveUrl = workerUrl;
      // Cross-origin workers aren't allowed, so create a same-origin blob
      // that re-exports the cross-origin module (preserving relative imports)
      if (workerUrl.origin !== location.origin) {
        const blob = new Blob(
          [`import '${workerUrl.href}';`],
          { type: 'application/javascript' }
        );
        effectiveUrl = URL.createObjectURL(blob);
      }
      worker = new Worker(effectiveUrl, { type: 'module' });
      worker.addEventListener('message', (e) => handleWorkerMessage(e.data));
      worker.addEventListener('error', (err) => console.error(`Worker ${i} error:`, err));
    }

    // Wait for worker to signal it's loaded
    await new Promise((resolve) => {
      const checkLoaded = (data) => {
        if (data.result?.status === 'worker_loaded') {
          resolve();
        }
      };

      if (env === 'node') {
        worker.once('message', checkLoaded);
      } else {
        const handler = (e) => {
          if (e.data.result?.status === 'worker_loaded') {
            worker.removeEventListener('message', handler);
            resolve();
          }
        };
        worker.addEventListener('message', handler);
      }
    });

    // Initialize PROJ in the worker (each gets its own WASM instance)
    const initResult = await sendToWorker(worker, {
      cmd: 'init',
      projDb,
      projIni
    });

    console.log(`proj-wasm: Worker ${i} initialized:`, initResult);
    workers.push(worker);
  }

  workerPool = {
    workers,
    mode,
    nextWorkerIdx: 0,
    sendToWorker: (workerIdx, msg) => sendToWorker(workers[workerIdx], msg)
  };

  return workerPool;
}

function getWorkerPool() {
  return workerPool;
}

/**
 * Sends shutdown command to all workers (which cleans up fetch-workers in Node.js),
 * then terminates them.
 */
async function shutdown() {
  if (!workerPool) return;

  const env = detectEnvironment();

  // Send shutdown command to all workers
  for (let i = 0; i < workerPool.workers.length; i++) {
    try {
      await sendToWorker(workerPool.workers[i], { cmd: 'shutdown' });
    } catch (e) {
      // Ignore errors during shutdown
    }
  }

  // Terminate all workers
  for (const worker of workerPool.workers) {
    try {
      if (env === 'node') {
        worker.terminate();
      } else {
        worker.terminate();
      }
    } catch (e) {
      // Ignore errors during shutdown
    }
  }

  workerPool = null;
}

export { initialize, detectEnvironment, initWithWorkers, getWorkerPool, shutdown };
