// @ts-check
const { defineConfig, devices } = require('@playwright/test');

module.exports = defineConfig({
  testDir: './tests',
  timeout: 60 * 1000,  // 60s for WASM loading
  expect: {
    timeout: 10000
  },
  fullyParallel: false,  // Run tests sequentially to avoid port conflicts
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: 1,  // Single worker to avoid port conflicts between server modes
  reporter: 'html',
  use: {
    trace: 'on-first-retry',
  },

  projects: [
    // Test WITHOUT COOP/COEP headers (single-threaded mode)
    {
      name: 'chromium-isolated',
      use: {
        ...devices['Desktop Chrome'],
        baseURL: 'http://localhost:8080',
      },
    },
    // Test WITH COOP/COEP headers (pthreads mode)
    {
      name: 'chromium-shared',
      use: {
        ...devices['Desktop Chrome'],
        baseURL: 'http://localhost:8081',
      },
    },
    // Firefox isolated
    {
      name: 'firefox-isolated',
      use: {
        ...devices['Desktop Firefox'],
        baseURL: 'http://localhost:8080',
      },
    },
    // Firefox shared
    {
      name: 'firefox-shared',
      use: {
        ...devices['Desktop Firefox'],
        baseURL: 'http://localhost:8081',
      },
    },
  ],

  webServer: [
    // Server WITHOUT COOP/COEP (single-threaded mode)
    {
      command: 'node server.mjs',
      port: 8080,
      env: { PORT: '8080', COOP_COEP: 'false' },
      reuseExistingServer: !process.env.CI,
    },
    // Server WITH COOP/COEP (pthreads mode)
    {
      command: 'node server.mjs',
      port: 8081,
      env: { PORT: '8081', COOP_COEP: 'true' },
      reuseExistingServer: !process.env.CI,
    },
  ],
});
