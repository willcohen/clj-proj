// Simple HTTP server with optional COOP/COEP headers for SharedArrayBuffer support
import { createServer } from 'http';
import { readFile, stat } from 'fs/promises';
import { join, extname } from 'path';
import { fileURLToPath } from 'url';

const __dirname = fileURLToPath(new URL('.', import.meta.url));
const rootDir = join(__dirname, '../..');  // Project root

const PORT = parseInt(process.env.PORT || '8080');
const ENABLE_COOP_COEP = process.env.COOP_COEP === 'true';

const MIME_TYPES = {
  '.html': 'text/html',
  '.js': 'text/javascript',
  '.mjs': 'text/javascript',
  '.css': 'text/css',
  '.json': 'application/json',
  '.wasm': 'application/wasm',
  '.db': 'application/octet-stream',
  '.ini': 'text/plain',
  '.map': 'application/json',
};

const server = createServer(async (req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);
  let filePath = join(rootDir, url.pathname);

  // Default to index.html for directory requests
  try {
    const stats = await stat(filePath);
    if (stats.isDirectory()) {
      filePath = join(filePath, 'index.html');
    }
  } catch {
    // File doesn't exist, will 404 below
  }

  try {
    const content = await readFile(filePath);
    const ext = extname(filePath);
    const mimeType = MIME_TYPES[ext] || 'application/octet-stream';

    // Set COOP/COEP headers if enabled
    if (ENABLE_COOP_COEP) {
      res.setHeader('Cross-Origin-Opener-Policy', 'same-origin');
      res.setHeader('Cross-Origin-Embedder-Policy', 'require-corp');
    }

    // Always set CORS headers for resources
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Content-Type', mimeType);
    res.writeHead(200);
    res.end(content);
  } catch (err) {
    if (err.code === 'ENOENT') {
      res.writeHead(404);
      res.end(`Not found: ${url.pathname}`);
    } else {
      console.error(err);
      res.writeHead(500);
      res.end('Server error');
    }
  }
});

server.listen(PORT, () => {
  console.log(`Server running at http://localhost:${PORT}/`);
  console.log(`COOP/COEP headers: ${ENABLE_COOP_COEP ? 'ENABLED' : 'DISABLED'}`);
  if (ENABLE_COOP_COEP) {
    console.log('  Cross-Origin-Opener-Policy: same-origin');
    console.log('  Cross-Origin-Embedder-Policy: require-corp');
  }
});
