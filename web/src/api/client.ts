import createClient from "openapi-fetch";
import type { paths } from "./schema";

// Paths in schema.d.ts already include the /api prefix; the Vite dev server proxies them to Ktor.
export const api = createClient<paths>({
  baseUrl: window.location.origin,
  // Look up fetch per call rather than capturing it at import time, so tests can stub it.
  fetch: (request) => globalThis.fetch(request),
});
