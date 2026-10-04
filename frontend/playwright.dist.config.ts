import {defineConfig} from '@playwright/test';
import existing from './playwright.config';
const servers=existing.webServer;
if(!Array.isArray(servers))throw Error('Expected explicit test backend');
export default defineConfig({
 testDir:'./dist-tests',testMatch:'real.spec.ts',retries:0,fullyParallel:false,
 use:{baseURL:'http://127.0.0.1:5175',browserName:'chromium'},
 webServer:[servers[0],{command:'exec python3 ../scripts/deployment/local_proxy.py --dist dist --port 5175 --upstream-port 8080',url:'http://127.0.0.1:5175',reuseExistingServer:false,gracefulShutdown:{signal:'SIGTERM',timeout:25000},timeout:30000}],
});
