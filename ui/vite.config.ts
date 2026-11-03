/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Dev: Core on 9310, DPS on 9400 (README). Production serves the build behind the same gateway paths.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 9312,
    proxy: {
      '/api': { target: 'http://localhost:9310', changeOrigin: true },
      '/dps': { target: 'http://localhost:9400', changeOrigin: true },
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    // e2e/ holds the Playwright browser tests (npm run e2e), which need the running stack.
    include: ['src/**/*.test.{ts,tsx}'],
    setupFiles: ['./src/test-setup.ts'],
    css: false,
    testTimeout: 15000,
    coverage: {
      provider: 'v8',
      reporter: ['text', 'json-summary'],
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/main.tsx', 'src/test-setup.ts', 'src/**/*.test.{ts,tsx}', 'src/api/types.ts'],
      thresholds: { lines: 80, statements: 80, branches: 70, functions: 70 },
    },
  },
});
