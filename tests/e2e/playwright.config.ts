import { defineConfig, devices } from "@playwright/test";

const PORT = 38080;
// When PLAYWRIGHT_BASE_URL is set (e.g. by upload-and-test.sh pointing at a real phone),
// skip the local :core webServer entirely and target that URL instead.
const externalBaseURL = process.env.PLAYWRIGHT_BASE_URL;
const baseURL = externalBaseURL ?? `http://127.0.0.1:${PORT}`;

export default defineConfig({
  testDir: "./specs",
  timeout: 30_000,
  expect: { timeout: 5_000 },
  fullyParallel: false,
  // One retry when targeting a phone over LAN: click/long-press/SSE timing
  // assertions occasionally race over real WiFi in a way loopback hides. The
  // local :core run stays at zero retries — flakes there are real bugs.
  retries: externalBaseURL ? 1 : 0,
  workers: 1,
  reporter: [["list"]],
  use: {
    baseURL,
    trace: "on-first-retry",
    actionTimeout: 5_000,
  },
  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
  ],
  ...(externalBaseURL
    ? {}
    : {
        webServer: {
          command: `PORT=${PORT} ./start-server.sh`,
          url: `http://127.0.0.1:${PORT}/api/items`,
          reuseExistingServer: !process.env.CI,
          timeout: 180_000,
          stdout: "pipe",
          stderr: "pipe",
        },
      }),
});
