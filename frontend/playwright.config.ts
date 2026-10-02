import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  retries: 0,
  use: { baseURL: 'http://127.0.0.1:5173', browserName: 'chromium' },
  webServer: [
    { command: 'java -jar ../backend/target/pis-backend-0.0.1-SNAPSHOT.jar',
      url: 'http://127.0.0.1:8080/api/hello', reuseExistingServer: false, timeout: 90_000 },
    { command: 'npm run dev',
      url: 'http://127.0.0.1:5173', reuseExistingServer: false, timeout: 60_000 },
  ],
});
