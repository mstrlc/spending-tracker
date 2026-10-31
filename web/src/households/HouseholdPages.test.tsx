import { afterEach, expect, test, vi } from "vitest";
import { cleanup, fireEvent, screen } from "@testing-library/react";
import { Route, Routes } from "react-router";
import { renderWithProviders, stubApi } from "../test-utils";
import { HomeRedirect, HouseholdLayout, OnboardingPage } from "./HouseholdPages";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

test("creating a household opens it, even though the cached household list was empty", async () => {
  const created = { id: "h1", name: "Home", currency: "CZK", role: "owner" };
  let households: unknown[] = [];
  stubApi({
    "GET /api/auth/me": () => Response.json({ id: "u1", email: "a@example.com", displayName: "Alice" }),
    // Refetches after the create are slow, as over a real network, so a redirect based on stale data would win.
    "GET /api/households": async () => {
      if (households.length > 0) await new Promise((resolve) => setTimeout(resolve, 100));
      return Response.json(households);
    },
    "POST /api/households": () => {
      households = [created];
      return Response.json(created, { status: 201 });
    },
  });
  renderWithProviders(
    <Routes>
      <Route path="/" element={<HomeRedirect />} />
      <Route path="/onboarding" element={<OnboardingPage />} />
      <Route path="/h/:householdId" element={<HouseholdLayout />}>
        <Route path="overview" element={<p>Overview page</p>} />
      </Route>
    </Routes>,
    "/",
  );

  fireEvent.change(await screen.findByLabelText("Household name"), { target: { value: "Home" } });
  fireEvent.click(screen.getByRole("button", { name: "Create household" }));

  expect(await screen.findByText("Overview page")).toBeTruthy();
  expect(screen.queryByText("Set up a household")).toBeNull();
});
