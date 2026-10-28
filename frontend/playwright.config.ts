import { defineConfig, devices } from '@playwright/test';

/**
 * End-to-end tests: the real Spring backend on a temporary vault (e2e/backend.mjs) behind the Vite dev server, driven
 * in Chromium. Both servers are started here, so `npm run test:e2e` is all it takes; they use their own ports and
 * leave a running app alone.
 */
const API_PORT = 8797;
const UI_PORT = 5174;

export default defineConfig({
  testDir: 'e2e',
  fullyParallel: true,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? 'github' : 'list',
  timeout: 30_000,
  expect: { timeout: 10_000 },
  use: {
    baseURL: `http://127.0.0.1:${UI_PORT}`,
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: [
    {
      command: 'node e2e/backend.mjs',
      url: `http://127.0.0.1:${API_PORT}/api/tree`,
      timeout: 240_000, // Maven compiles the server first
      reuseExistingServer: false,
      env: { KBMD_E2E_API_PORT: String(API_PORT) },
    },
    {
      command: `npm run dev -- --host 127.0.0.1 --port ${UI_PORT} --strictPort`,
      url: `http://127.0.0.1:${UI_PORT}/`,
      timeout: 60_000,
      reuseExistingServer: false,
      env: { KBMD_API: `http://127.0.0.1:${API_PORT}` },
    },
  ],
});
