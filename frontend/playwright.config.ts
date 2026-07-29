import { defineConfig, devices } from '@playwright/test';

/**
 * Browser coverage for the Nail Look vertical slice.
 *
 * Assumes the app is already running: Vite on 5173 and the backend on 8080 against a real Postgres. The
 * tests deliberately do NOT stub the API — the point of this suite is to prove the whole chain works with
 * real catalog data, which a mocked network would hide.
 *
 * Screenshots land in test-results/screens/ and are the evidence attached to the phase report.
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: 'http://localhost:5173',
    trace: 'off',
    screenshot: 'off',
  },
  projects: [
    { name: 'desktop', use: { ...devices['Desktop Chrome'], viewport: { width: 1280, height: 900 } } },
    { name: 'mobile', use: { ...devices['Pixel 7'] } },
  ],
});
