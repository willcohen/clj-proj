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
    console.log("toUint8Array called with type:", typeof bufferLike, 
                "length:", bufferLike?.length,
                "constructor:", bufferLike?.constructor?.name);
    
    // If it's already a Uint8Array or ArrayBuffer, return as-is
    if (bufferLike instanceof Uint8Array) {
        console.log("Conversion method used: Already Uint8Array");
        return bufferLike;
    }
    if (bufferLike instanceof ArrayBuffer) {
        console.log("Conversion method used: ArrayBuffer to Uint8Array");
        const arr = new Uint8Array(bufferLike);
        console.log("Result size:", arr.byteLength);
        console.log("First few bytes:", Array.from(arr.slice(0, 10)));
        return arr;
    }
    
    // For GraalVM: Check if it's a Java byte array passed as a host object
    if (bufferLike && typeof bufferLike === 'object' && bufferLike.length !== undefined) {
        try {
            console.log("Attempting array-like conversion for length:", bufferLike.length);
            const arr = new Uint8Array(bufferLike.length);
            for (let i = 0; i < bufferLike.length; i++) {
                // Java byte arrays have signed bytes (-128 to 127)
                // JavaScript Uint8Array expects unsigned bytes (0 to 255)
                const byte = bufferLike[i];
                arr[i] = byte < 0 ? byte + 256 : byte;
            }
            console.log("Conversion method used: Array-like object (Java byte array)");
            console.log("Result size:", arr.byteLength);
            console.log("First few bytes:", Array.from(arr.slice(0, 10)));
            return arr;
        } catch (e) {
            console.warn("Failed to copy array-like object:", e);
        }
    }
    
    // Check if it has an array() method (standard ByteBuffer)
    if (bufferLike.array && typeof bufferLike.array === 'function') {
        try {
            console.log("Attempting ByteBuffer.array() method");
            const result = new Uint8Array(bufferLike.array());
            console.log("Conversion method used: ByteBuffer.array()");
            console.log("Result size:", result.byteLength);
            console.log("First few bytes:", Array.from(result.slice(0, 10)));
            return result;
        } catch (e) {
            console.warn("Failed to call .array() method:", e);
        }
    }
    
    // For GraalVM ByteBuffers with position/limit/get
    if (bufferLike.limit && bufferLike.position !== undefined) {
        try {
            console.log("Attempting ByteBuffer position/limit/get method");
            const length = bufferLike.limit();
            const arr = new Uint8Array(length);
            const savedPosition = bufferLike.position();
            bufferLike.position(0);
            for (let i = 0; i < length; i++) {
                arr[i] = bufferLike.get();
            }
            bufferLike.position(savedPosition);
            console.log("Conversion method used: ByteBuffer position/limit/get");
            console.log("Result size:", arr.byteLength);
            console.log("First few bytes:", Array.from(arr.slice(0, 10)));
            return arr;
        } catch (e) {
            console.warn("Failed to read ByteBuffer byte by byte:", e);
        }
    }
    
    console.log("Failed to convert, object properties:", Object.keys(bufferLike || {}));
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
    console.log("PROJ-DEBUG: `initialize` called.");
    
    const { onSuccess, onError } = options;
    
    if (!onSuccess || !onError) {
        throw new Error("Both onSuccess and onError callbacks are required");
    }
    
    if (projModuleInstance) {
        console.log("PROJ-DEBUG: Module already initialized, calling success callback with cached instance.");
        onSuccess(projModuleInstance);
        return;
    }

    // Run the async initialization
    (async () => {
        try {
            const env = detectEnvironment();
            console.log("PROJ-DEBUG: Detected environment:", env);
            
            // Load database files based on environment
            let projDbData, projIniData;
            
            if (options.projDb && options.projIni) {
                // GraalVM case - resources provided in options
                console.log("PROJ-DEBUG: Using resources from options (GraalVM)");
                projDbData = toUint8Array(options.projDb);
                projIniData = options.projIni;
            } else if (env === 'node') {
                // Node.js - load from filesystem
                console.log("PROJ-DEBUG: Loading resources from filesystem (Node.js)");
                const fs = await import('fs');
                const path = await import('path');
                const { fileURLToPath } = await import('url');
                
                const __filename = fileURLToPath(import.meta.url);
                const __dirname = path.dirname(__filename);
                
                projDbData = fs.readFileSync(path.join(__dirname, 'proj.db'));
                projIniData = fs.readFileSync(path.join(__dirname, 'proj.ini'), 'utf8');
                console.log(`PROJ-DEBUG: Loaded proj.db (${projDbData.length} bytes) and proj.ini`);
            } else if (env === 'browser') {
                // Browser - load via fetch relative to this module's URL
                console.log("PROJ-DEBUG: Loading resources via fetch (Browser)");
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
                console.log(`PROJ-DEBUG: Fetched proj.db (${projDbData.length} bytes) and proj.ini`);
            } else {
                throw new Error("Unknown environment - cannot load database files");
            }
            
            // Import the Emscripten module
            console.log("PROJ-DEBUG: Importing proj-emscripten.js");
            const { default: PROJModule } = await import('./proj-emscripten.js');
            console.log("PROJ-DEBUG: Successfully imported proj-emscripten.js");

            // Prepare module arguments
            const moduleArgs = {
                onRuntimeInitialized: function() {
                    console.log("PROJ-DEBUG: Runtime initialized, writing files to virtual FS");
                    
                    try {
                        // Create /proj directory and write database files
                        this.FS.mkdir('/proj');
                        this.FS.writeFile('/proj/proj.db', projDbData);
                        this.FS.writeFile('/proj/proj.ini', projIniData);
                        
                        console.log(`PROJ-DEBUG: Successfully wrote proj.db (${projDbData.length} bytes) and proj.ini to /proj/`);
                        
                        // Handle grid files if provided (GraalVM case)
                        if (options.projGrids) {
                            console.log("PROJ-DEBUG: Writing grid files to virtual FS");
                            this.FS.mkdir('/proj/grids');
                            
                            for (const [name, bytes] of Object.entries(options.projGrids)) {
                                try {
                                    const gridBytes = toUint8Array(bytes);
                                    this.FS.writeFile(`/proj/grids/${name}`, gridBytes);
                                    console.log(`PROJ-DEBUG: Wrote grid file ${name} (${gridBytes.byteLength} bytes)`);
                                } catch (e) {
                                    console.error(`PROJ-DEBUG: Failed to write grid file ${name}:`, e);
                                }
                            }
                        }
                        
                        // Verify files were written
                        const files = this.FS.readdir('/proj');
                        console.log("PROJ-DEBUG: /proj directory contents:", files);
                        
                        // Cache the initialized module
                        projModuleInstance = this;
                        console.timeEnd("PROJ-init");
                        
                        // Call success callback
                        onSuccess(this);
                    } catch (e) {
                        console.error("PROJ-DEBUG: Error writing files to virtual FS:", e);
                        onError(e);
                    }
                },
                
                setStatus: (text) => console.log(`PROJ-EMCC-STATUS: ${text}`),
                monitorRunDependencies: (left) => console.log(`PROJ-EMCC-DEPS: ${left} dependencies remaining`)
            };

            // Handle GraalVM case with wasmBinary
            if (options.wasmBinary) {
                console.log("PROJ-DEBUG: Using provided wasmBinary (GraalVM)");
                const wasmBinaryArray = toUint8Array(options.wasmBinary);
                moduleArgs.wasmBinary = wasmBinaryArray.buffer;
            }

            // Handle browser case with locateFile
            if (options.locateFile) {
                console.log("PROJ-DEBUG: Using provided locateFile function");
                moduleArgs.locateFile = options.locateFile;
            } else if (env === 'browser') {
                // Default locateFile for browser - resolve relative to this module's URL
                const baseUrl = new URL('./', import.meta.url).href;
                moduleArgs.locateFile = (path) => {
                    return baseUrl + path;
                };
            }

            // Initialize the module
            console.log("PROJ-DEBUG: Calling PROJModule()");
            await PROJModule(moduleArgs);
            
        } catch (error) {
            console.error("PROJ-DEBUG: Initialization failed:", error);
            console.timeEnd("PROJ-init");
            onError(error);
        }
    })();
}

export { initialize, detectEnvironment };
