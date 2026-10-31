import { afterEach, expect, test, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { HomePage } from "./HomePage";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <HomePage />
    </QueryClientProvider>,
  );
}

test("shows API status from the health endpoint", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => Response.json({ status: "ok", database: "up" })),
  );
  renderPage();
  expect(await screen.findByText("API ok, database up")).toBeTruthy();
});

test("shows an alert when the database is down", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => Response.json({ status: "degraded", database: "down" }, { status: 503 })),
  );
  renderPage();
  expect((await screen.findByRole("alert")).textContent).toContain("Database down");
});
