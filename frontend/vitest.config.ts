import { defineConfig, mergeConfig } from 'vitest/config';
import viteConfig from './vite.config';

export default mergeConfig(viteConfig, defineConfig({
  test: {
    // Playwright owns e2e/. Discover every colocated unit test without mixing runners.
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    environment: 'node',
  },
}));
