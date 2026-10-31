/// <reference types="vitest/config" />
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // Same-origin API calls so session cookies work without CORS.
    proxy: { "/api": "http://localhost:8080" },
  },
  test: {
    environment: "jsdom",
  },
});
