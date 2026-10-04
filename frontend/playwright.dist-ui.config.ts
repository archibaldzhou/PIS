import {defineConfig} from '@playwright/test';
// Actual dist server; explicit mocked auth responses. Not the real service E2E.
export default defineConfig({
 testDir:'./dist-tests',testMatch:'contract.spec.ts',retries:0,fullyParallel:false,
 use:{baseURL:'http://127.0.0.1:5176',browserName:'chromium',launchOptions:{executablePath:process.env.PIS_UI_BROWSER_PATH}},
 webServer:{command:'exec python3 ../scripts/deployment/local_proxy.py --dist dist --port 5176 --upstream-port 8080',url:'http://127.0.0.1:5176',reuseExistingServer:false,gracefulShutdown:{signal:'SIGTERM',timeout:25000},timeout:30000},
});
