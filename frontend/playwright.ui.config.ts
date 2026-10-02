import { defineConfig } from '@playwright/test';

// Browser UI contract tests use explicit synthetic HTTP fixtures. They do not replace PG E2E.
export default defineConfig({
  testDir: './ui-tests', fullyParallel: false, retries: 0,
  use: { baseURL: 'http://127.0.0.1:5174', browserName: 'chromium', screenshot: 'only-on-failure',
    launchOptions: { executablePath: process.env.PIS_UI_BROWSER_PATH } },
  webServer: { command: 'npm run dev -- --port 5174', url: 'http://127.0.0.1:5174', reuseExistingServer: false },
});
