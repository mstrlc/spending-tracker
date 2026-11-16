/// <reference types="vitest/config" />
import { defineConfig, loadEnv } from "vite";
import path from "node:path";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

export default defineConfig(({ mode }) => {
  // The repo root's .env.local (from scripts/dev.sh setup) names this checkout's ports; the shell's variables win.
  const env = { ...loadEnv(mode, path.resolve(import.meta.dirname, ".."), ""), ...loadEnv(mode, ".", "") };
  return {
    plugins: [react(), tailwindcss()],
    resolve: { alias: { "@": path.resolve(import.meta.dirname, "src") } },
    server: {
      port: env.WEB_PORT ? Number(env.WEB_PORT) : 5173,
      // A checkout with its own slot must not drift onto another checkout's port.
      strictPort: Boolean(env.WEB_PORT),
      // Same-origin API calls so session cookies work without CORS. API_URL points at this checkout's API.
      proxy: { "/api": env.API_URL ?? "http://localhost:8080" },
    },
    test: {
      environment: "jsdom",
      setupFiles: ["src/test-setup.ts"],
    },
  };
});
