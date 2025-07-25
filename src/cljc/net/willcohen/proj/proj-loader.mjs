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
    // GraalVM might not have 'window' or 'process' in a standard way,
    // but it will be called from Clojure which provides the resources directly.
    // We can treat it as a special case and rely on options being passed in.
    return 'unknown';
}

/**
 * Converts a GraalVM ByteBuffer or similar object to a Uint8Array.
 * GraalVM ByteBuffers don't expose .array() directly to JavaScript,
 * so we need to handle the conversion differently.
 * 
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
    // This check is early because GraalVM passes raw byte arrays
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
    
    // For GraalVM ByteBuffers, we need to read the bytes differently
    // GraalVM exposes ByteBuffer properties and methods differently
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
 * Initializes the PROJ Emscripten module with embedded resources.
 * Resources (proj.db, proj.ini) are now embedded directly in the WASM binary.
 * This version is designed to work with GraalVM where the main thread might be blocked.
 *
 * @param {object} options - Initialization options.
 * @param {ArrayBuffer} [options.wasmBinary] - The proj.wasm file contents (for GraalVM).
 * @param {function(string): string} [options.locateFile] - A function to locate the .wasm file (for browsers).
 * @param {function(object)} [options.onSuccess] - Callback when initialization succeeds
 * @param {function(Error)} [options.onError] - Callback when initialization fails
 */
function initialize(options = {}) {
    console.time("PROJ-init");
    console.log("PROJ-DEBUG: `initialize` called with embedded resources.");
    
    const { onSuccess, onError } = options;
    
    if (!onSuccess || !onError) {
        throw new Error("Both onSuccess and onError callbacks are required");
    }
    
    if (projModuleInstance) {
        console.log("PROJ-DEBUG: Module already initialized, calling success callback with cached instance.");
        onSuccess(projModuleInstance);
        return;
    }

    // Run the async initialization in a separate execution context
    (async () => {
        try {
            // Dynamically import the large Emscripten module inside the async function.
            // This avoids the static import at the top level, which can cause issues with some JS engines like GraalJS.
            console.log("PROJ-DEBUG: About to `await import('./proj-emscripten.js')`...");
            console.time("PROJ-import-proj-js")
            const { default: PROJModule } = await import('./proj-emscripten.js');
            console.log("PROJ-DEBUG: Successfully imported `./proj-emscripten.js`. PROJModule is now available.");

            // --- Step 1: Prepare Module Arguments ---
            // With embedded resources, we only need to handle the WASM binary (for GraalVM)
            // and optionally the locateFile function (for browsers)
            const moduleArgs = {
                // Add hooks to see what Emscripten is doing internally.
                setStatus: (text) => console.log(`PROJ-EMCC-STATUS: ${text}`),
                monitorRunDependencies: (left) => console.log(`PROJ-EMCC-DEPS: ${left} dependencies remaining.`)
            };

            // Handle GraalVM case where WASM binary is provided directly
            if (options.wasmBinary) {
                console.log("PROJ-DEBUG: Using provided WASM binary from GraalVM.");
                try {
                    moduleArgs.wasmBinary = toUint8Array(options.wasmBinary);
                    console.log("WASM binary converted successfully, size:", moduleArgs.wasmBinary.byteLength);
                } catch (e) {
                    console.error("Failed to convert WASM binary:", e);
                    throw e;
                }
            }

            // Handle browser case where locateFile may be needed for .wasm file
            if (options.locateFile) {
                console.log("PROJ-DEBUG: Using provided locateFile function for browser.");
                moduleArgs.locateFile = options.locateFile;
            }

            // --- Step 2: Instantiate the Emscripten Module ---
            console.log("PROJ-DEBUG: Preparing to instantiate Emscripten module...");
            console.log("PROJ-DEBUG: About to `await PROJModule(moduleArgs)`...");
            
            let PROJ;
            try {
                PROJ = await PROJModule(moduleArgs);
                console.log("Module loaded successfully with embedded resources");
            } catch (e) {
                console.error("PROJModule instantiation failed:", e);
                throw e;
            }
            
            console.log("PROJ-DEBUG: Emscripten module instantiated successfully. PROJ object is available.");
            console.log("PROJ-DEBUG: Resources (proj.db, proj.ini) are embedded in WASM - no filesystem setup needed.");

            projModuleInstance = PROJ;
            console.log("PROJ-DEBUG: Caching instance and calling success callback.");
            console.timeEnd("PROJ-init");
            
            // Call the success callback
            console.log("PROJ-DEBUG: About to call onSuccess callback");
            console.log("PROJ-DEBUG: onSuccess type:", typeof onSuccess);
            
            // GraalVM ProxyExecutable objects are callable as functions
            onSuccess(PROJ);
            
        } catch (error) {
            console.error("PROJ-DEBUG: Initialization failed:", error);
            
            // Call error callback
            console.log("PROJ-DEBUG: About to call onError callback");
            console.log("PROJ-DEBUG: onError type:", typeof onError);
            onError(error);
        }
    })();
}

// The export is an object containing the initialize function.

export { initialize };