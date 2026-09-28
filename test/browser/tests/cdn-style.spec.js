// @ts-check
const { test, expect } = require('@playwright/test');

// CDN-style: the module loads from a subdirectory relative to the HTML page.
test.describe('CDN-Style Loading Tests', () => {
  test('can initialize PROJ with CDN-style loading', async ({ page }) => {
    const consoleLogs = [];
    const consoleErrors = [];

    page.on('console', msg => {
      const text = msg.text();
      consoleLogs.push({ type: msg.type(), text });
      console.log('Browser console:', msg.type(), text);
    });

    page.on('pageerror', error => {
      consoleErrors.push(error.message);
      console.log('Browser error:', error.message);
    });

    await page.goto('/test/browser/cdn-style/index.html');
    await page.waitForFunction(() => window.proj !== undefined, { timeout: 30000 });

    const result = await page.evaluate(async () => {
      const proj = window.proj;

      try {
        console.log('Starting PROJ initialization (CDN-style test)...');
        const initFunction = proj.init_BANG_ || proj['init!'] || proj.init;

        if (!initFunction || typeof initFunction !== 'function') {
          return { success: false, error: 'Init function not found' };
        }

        await initFunction();
        console.log('PROJ initialized successfully');

        const crossOriginIsolated = self.crossOriginIsolated || false;
        console.log('crossOriginIsolated:', crossOriginIsolated);

        const context = await proj.context_create();
        if (!context) {
          return { success: false, error: 'Failed to create context after init' };
        }

        return {
          success: true,
          crossOriginIsolated
        };
      } catch (error) {
        return {
          success: false,
          error: error.message,
          stack: error.stack
        };
      }
    });

    const has404Errors = consoleLogs.some(log =>
      log.text.includes('404') ||
      log.text.includes('Failed to fetch resources')
    );

    if (!result.success) {
      console.log('Initialization failed:', result.error);
      if (result.stack) {
        console.log('Stack:', result.stack);
      }
    }

    expect(has404Errors).toBe(false);
    expect(result.success).toBe(true);
  });
});
