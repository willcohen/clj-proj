// Copyright (c) 2024, 2025, 2026 Will Cohen
//
// Part of clj-proj, under the MIT License.
// See LICENSE for license information.
// SPDX-License-Identifier: MIT

// Standalone probe of the shipped wasm build in current browsers. It loads
// proj-emscripten.js on the main thread, so only the build itself is under
// test. Run: node probe-resizable.mjs
import { chromium, firefox } from '@playwright/test';
import { spawn } from 'node:child_process';

const server = spawn(process.execPath, ['server.mjs'], {
  cwd: import.meta.dirname,
  env: { ...process.env, PORT: '8099', COOP_COEP: 'false' },
  stdio: ['ignore', 'pipe', 'pipe'],
});
await new Promise((r) => setTimeout(r, 1200));

for (const [name, launcher] of [['chromium', chromium], ['firefox', firefox]]) {
  const browser = await launcher.launch();
  const page = await browser.newPage();
  const errs = [];
  page.on('pageerror', (e) => errs.push('pageerror: ' + e.message));
  await page.goto('http://localhost:8099/test/browser/probe-resizable.html');
  await page.waitForFunction(
    () => document.getElementById('out').textContent.includes('DONE'),
    null, { timeout: 60000 },
  ).catch(() => {});
  const out = await page.textContent('#out');
  console.log('===== ' + name + ' (' + browser.version() + ')');
  console.log(out);
  if (errs.length) console.log(errs.join('\n'));
  await browser.close();
}

// The same checks in Node, for comparison.
console.log('===== node (' + process.version + ')');
try {
  const rab = new ArrayBuffer(8, { maxByteLength: 16 });
  new TextDecoder().decode(new Uint8Array(rab, 0, 4));
  console.log('A textdecoder-on-resizable: ACCEPTED');
} catch (e) {
  console.log('A textdecoder-on-resizable: REJECTED -- ' + e.message);
}
const { default: PROJModule } = await import('../../src/cljc/net/willcohen/proj/dist/proj-emscripten.js');
const m = await PROJModule();
console.log('B HEAPU8.buffer.resizable: ' + m.HEAPU8.buffer.resizable);
try {
  const s = m.UTF8ToString(m.ccall('proj_context_errno_string', 'number',
                                   ['number', 'number'], [0, 1027]));
  console.log('D UTF8ToString: OK -- ' + JSON.stringify(s));
} catch (e) {
  console.log('D UTF8ToString: THREW -- ' + e.message);
}

server.kill();
process.exit(0);
