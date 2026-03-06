/**
 * fetch-worker.mjs - Node.js worker_thread that bridges synchronous HTTP requests
 * from the PROJ worker to Node.js's async HTTP APIs.
 *
 * Why this exists: Emscripten's FETCH uses synchronous XMLHttpRequest, but Node.js
 * doesn't have sync HTTP. The PROJ worker's XMLHttpRequest polyfill (in proj-worker.mjs)
 * delegates sync requests here via SharedArrayBuffer + Atomics.
 *
 * Communication protocol using three SharedArrayBuffers:
 *   controlBuffer (8B, Int32Array)  - [0]=request ready (1=waiting), [1]=response ready (1=done)
 *   metaBuffer    (16B, Int32Array) - [status, bodyLength, headersLength]
 *   dataBuffer    (50MB, Uint8Array) - request data outbound (URL + headers), response data inbound (body + headers)
 *
 * Flow: PROJ worker writes request to dataBuffer, sets control[0]=1 and notifies.
 * This worker wakes, does async HTTP, writes response to dataBuffer, sets control[1]=1
 * and notifies back. Polls with 100ms Atomics.wait timeout, checks for shutdown (value 2
 * in control[0]).
 */

import { parentPort } from 'worker_threads';
import https from 'https';
import http from 'http';

// Shared buffers for communication
let controlBuffer = null;  // Int32Array for signaling
let dataBuffer = null;     // Uint8Array for response data
let metaBuffer = null;     // Int32Array for metadata (status, length, etc.)

// Shutdown flag
let shouldShutdown = false;

// Control buffer layout:
// [0] = request ready flag (1 = request waiting, 0 = no request)
// [1] = response ready flag (1 = response ready, 0 = not ready)

// Meta buffer layout:
// [0] = HTTP status code
// [1] = response body length
// [2] = headers length

parentPort.on('message', async (msg) => {
  if (msg.cmd === 'init') {
    // Initialize shared buffers
    controlBuffer = new Int32Array(msg.controlBuffer);
    dataBuffer = new Uint8Array(msg.dataBuffer);
    metaBuffer = new Int32Array(msg.metaBuffer);
    parentPort.postMessage({ status: 'ready' });

    // Start polling for requests
    pollForRequests();
  } else if (msg.cmd === 'shutdown') {
    shouldShutdown = true;
    // Also set a signal in controlBuffer to wake up any waiting Atomics.wait
    if (controlBuffer) {
      Atomics.store(controlBuffer, 0, 2);  // 2 = shutdown signal
      Atomics.notify(controlBuffer, 0, 1);
    }
    parentPort.postMessage({ status: 'shutdown_complete' });
  }
});

async function pollForRequests() {
  while (!shouldShutdown) {
    // Wait for a request (controlBuffer[0] === 1)
    const result = Atomics.wait(controlBuffer, 0, 0, 100); // 100ms timeout

    // Check for shutdown signal
    const signal = Atomics.load(controlBuffer, 0);
    if (signal === 2 || shouldShutdown) {
      break;  // Shutdown requested
    }

    if (result === 'ok' || signal === 1) {
      // Request is ready - read request data from dataBuffer
      await handleRequest();
    }
    // 'timed-out' means no request yet, keep polling
  }
}

async function handleRequest() {
  try {
    // Read request from dataBuffer
    // Format: [urlLength:4][headersLength:4][url:urlLength][headers:headersLength]
    const view = new DataView(dataBuffer.buffer);
    const urlLength = view.getInt32(0, true);
    const headersLength = view.getInt32(4, true);

    const urlBytes = dataBuffer.slice(8, 8 + urlLength);
    const url = new TextDecoder().decode(urlBytes);

    let headers = {};
    if (headersLength > 0) {
      const headersBytes = dataBuffer.slice(8 + urlLength, 8 + urlLength + headersLength);
      const headersStr = new TextDecoder().decode(headersBytes);
      headers = JSON.parse(headersStr);
    }

    // Make the HTTP request
    const response = await doFetch(url, headers);

    // Write response to buffers
    writeResponse(response);

  } catch (err) {
    // Write error response
    writeResponse({ status: 0, body: new Uint8Array(0), headers: '', error: err.message });
  }
}

// Resolves https vs http module based on URL protocol, uses Node.js http(s).request
// with a 30-second timeout. Errors and timeouts resolve as {status: 0} rather than
// rejecting, so the calling worker gets an error status instead of an unhandled rejection.
function doFetch(url, headers) {
  return new Promise((resolve, reject) => {
    const parsedUrl = new URL(url);
    const isHttps = parsedUrl.protocol === 'https:';
    const lib = isHttps ? https : http;

    const options = {
      method: 'GET',
      hostname: parsedUrl.hostname,
      port: parsedUrl.port || (isHttps ? 443 : 80),
      path: parsedUrl.pathname + parsedUrl.search,
      headers: headers
    };

    const req = lib.request(options, (res) => {
      const chunks = [];
      res.on('data', chunk => chunks.push(chunk));
      res.on('end', () => {
        const body = Buffer.concat(chunks);
        const headersStr = Object.entries(res.headers)
          .map(([k, v]) => `${k}: ${v}`)
          .join('\r\n');
        resolve({
          status: res.statusCode,
          body: new Uint8Array(body.buffer, body.byteOffset, body.byteLength),
          headers: headersStr
        });
      });
    });

    req.on('error', err => {
      resolve({ status: 0, body: new Uint8Array(0), headers: '', error: err.message });
    });

    req.setTimeout(30000, () => {
      req.destroy();
      resolve({ status: 0, body: new Uint8Array(0), headers: '', error: 'timeout' });
    });

    req.end();
  });
}

// Writes response to shared buffers. Layout: body bytes at dataBuffer offset 0,
// then header bytes immediately after body. Meta buffer stores [status, bodyLength,
// headersLength]. Order matters: data must be fully written before setting control[1]=1
// and notifying, so the PROJ worker sees a consistent snapshot when it wakes.
function writeResponse(response) {
  Atomics.store(metaBuffer, 0, response.status);
  Atomics.store(metaBuffer, 1, response.body.length);

  const headersBytes = new TextEncoder().encode(response.headers);
  Atomics.store(metaBuffer, 2, headersBytes.length);

  // Write body to dataBuffer
  dataBuffer.set(response.body, 0);

  // Write headers after body
  dataBuffer.set(headersBytes, response.body.length);

  // Clear request flag and set response ready flag
  Atomics.store(controlBuffer, 0, 0);
  Atomics.store(controlBuffer, 1, 1);

  // Wake up the waiting thread
  Atomics.notify(controlBuffer, 1, 1);
}
