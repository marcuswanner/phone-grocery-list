import { defineConfig, devices } from "@playwright/test";

const PORT = 38080;

export default defineConfig({
  testDir: "./specs",
  timeout: 30_000,
  expect: { timeout: 5_000 },
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [["list"]],
  use: {
    baseURL: `http://127.0.0.1:${PORT}`,
    trace: "on-first-retry",
    actionTimeout: 5_000,
  },
  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
  ],
  webServer: {
    command: `PORT=${PORT} ./start-server.sh`,
    url: `http://127.0.0.1:${PORT}/api/items`,
    reuseExistingServer: !process.env.CI,
    timeout: 180_000,
    stdout: "pipe",
    stderr: "pipe",
  },
});
