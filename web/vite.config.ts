/// <reference types="vitest/config" />
import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig(({ mode }) => ({
  plugins: [react()],
  server: {
    port: 5173,
    // Same-origin API calls so session cookies work without CORS. API_URL points at a server on another port.
    proxy: { "/api": loadEnv(mode, ".", "").API_URL ?? "http://localhost:8080" },
  },
  test: {
    environment: "jsdom",
  },
}));
