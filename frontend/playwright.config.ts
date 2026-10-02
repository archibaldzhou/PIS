import { defineConfig } from '@playwright/test';

const databaseUrl = process.env.PIS_TEST_DB_URL
  ?? 'jdbc:postgresql://127.0.0.1:5433/pis_test?connectTimeout=5&socketTimeout=10';
const databasePassword = process.env.PIS_TEST_DB_PASSWORD;
if (!databaseUrl.startsWith('jdbc:postgresql://')
  || !new URL(databaseUrl.slice('jdbc:'.length)).pathname.endsWith('_test')) {
  throw new Error('Playwright requires a disposable PostgreSQL database whose name ends in _test');
}
if (!databasePassword?.trim()) {
  throw new Error('Set PIS_TEST_DB_PASSWORD before running Playwright');
}

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  retries: 0,
  use: { baseURL: 'http://127.0.0.1:5173', browserName: 'chromium' },
  webServer: [
    {
      command: (process.platform === 'win32' ? '..\\backend\\mvnw.cmd' : '../backend/mvnw')
        + ' -f ../backend/pom.xml -B -ntp spring-boot:test-run'
        + ' -Dspring-boot.run.main-class=com.pis.security.testfixture.SecurityE2eApplication',
      env: {
        PIS_TEST_DB_URL: databaseUrl,
        PIS_TEST_DB_USERNAME: process.env.PIS_TEST_DB_USERNAME ?? 'pis_test',
        PIS_TEST_DB_PASSWORD: databasePassword,
      },
      url: 'http://127.0.0.1:8080/actuator/health/readiness',
      reuseExistingServer: false,
      timeout: 120_000,
    },
    { command: 'npm run dev',
      url: 'http://127.0.0.1:5173', reuseExistingServer: false, timeout: 60_000 },
  ],
});
