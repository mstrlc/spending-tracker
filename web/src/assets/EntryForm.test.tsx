import { afterEach, expect, test, vi } from "vitest";
import { cleanup, fireEvent, screen } from "@testing-library/react";
import { Route, Routes } from "react-router";
import { DialogsProvider } from "../dialogs";
import { HouseholdLayout } from "../households/HouseholdPages";
import { renderWithProviders, stubApi } from "../test-utils";
import { AssetsPage } from "./AssetsPage";
import { EntryForm, type EntryTarget } from "./EntryForm";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function renderEntryForm(target: EntryTarget, isLiability = false) {
  stubApi({
    "GET /api/auth/me": () => Response.json({ id: "u1", email: "a@example.com", displayName: "Alice" }),
    "GET /api/households": () => Response.json([{ id: "h1", name: "Home", currency: "CZK", role: "owner" }]),
    "GET /api/households/h1/accounts": () => Response.json([]),
  });
  renderWithProviders(
    <DialogsProvider>
      <Routes>
        <Route path="/h/:householdId" element={<HouseholdLayout />}>
          <Route
            path="entry"
            element={<EntryForm assetId="a1" security={null} isLiability={isLiability} target={target} onDone={() => {}} />}
          />
        </Route>
      </Routes>
    </DialogsProvider>,
    "/h/h1/entry",
  );
}

test("uses balance and repayment wording on a liability entry form", async () => {
  renderEntryForm({ type: "valuation" }, true);

  expect(await screen.findByRole("form", { name: "Update balance" })).toBeTruthy();
  const owed = screen.getByLabelText("Owed (CZK)");
  fireEvent.change(owed, { target: { value: "-100" } });
  fireEvent.click(screen.getByRole("button", { name: "Save balance" }));
  expect(screen.getByText("Enter what's owed, like 250000.")).toBeTruthy();

  cleanup();
  renderEntryForm({ type: "contribution" }, true);

  const amount = await screen.findByLabelText("Amount (CZK)");
  fireEvent.change(amount, { target: { value: "0" } });
  fireEvent.click(screen.getByRole("button", { name: "Add contribution" }));
  expect(screen.getByText("Positive for a repayment, minus for money borrowed.")).toBeTruthy();
});

test("uses worth and put-in wording on a non-liability entry form", async () => {
  renderEntryForm({ type: "valuation" }, false);

  expect(await screen.findByRole("form", { name: "Update value" })).toBeTruthy();
  const worth = screen.getByLabelText("Worth (CZK)");
  fireEvent.change(worth, { target: { value: "-100" } });
  fireEvent.click(screen.getByRole("button", { name: "Save value" }));
  expect(screen.getByText("Enter what it's worth, like 125000.")).toBeTruthy();
});

test("uses liability wording in the asset sheet button and history rows", async () => {
  const loan = {
    id: "a1",
    name: "Mortgage",
    kind: "loan" as const,
    ownerUserId: null,
    notes: "",
    version: 1,
    security: null,
    shares: null,
    marketValued: false,
    accountId: null,
    valueSource: "manual" as const,
    contributedMinor: 500_000,
    valueMinor: -25_000_000,
    valuedOn: "2026-02-01",
    gainMinor: 0,
    annualReturn: null,
    firstContributedOn: "2026-01-01",
    feesMinor: 0,
  };
  stubApi({
    "GET /api/auth/me": () => Response.json({ id: "u1", email: "a@example.com", displayName: "Alice" }),
    "GET /api/households": () => Response.json([{ id: "h1", name: "Home", currency: "CZK", role: "owner" }]),
    "GET /api/households/h1/assets": () => Response.json([loan]),
    "GET /api/households/h1/assets/summary": () =>
      Response.json({ assetsMinor: 0, liabilitiesMinor: -25_000_000, netMinor: -25_000_000, contributedMinor: 500_000, gainMinor: 0, feesMinor: 0, annualReturn: null }),
    "GET /api/households/h1/assets/history": () => Response.json({ points: [] }),
    "GET /api/households/h1/members": () => Response.json([]),
    "GET /api/households/h1/accounts": () => Response.json([]),
    "GET /api/households/h1/assets/a1/contributions": () =>
      Response.json([
        { id: "c1", assetId: "a1", date: "2026-01-01", amountMinor: -2_000_000, originalAmountMinor: null, originalCurrency: null, quantity: null, accountId: null, transferId: null, notes: "", version: 1 },
        { id: "c2", assetId: "a1", date: "2026-01-15", amountMinor: 500_000, originalAmountMinor: null, originalCurrency: null, quantity: null, accountId: null, transferId: "t1", notes: "Monthly payment", version: 1 },
      ]),
    "GET /api/households/h1/assets/a1/valuations": () =>
      Response.json([{ id: "v1", assetId: "a1", date: "2026-02-01", valueMinor: 25_000_000, notes: "", version: 1 }]),
    "GET /api/households/h1/assets/a1/fees": () => Response.json([]),
  });
  renderWithProviders(
    <DialogsProvider>
      <Routes>
        <Route path="/h/:householdId" element={<HouseholdLayout />}>
          <Route path="assets" element={<AssetsPage />} />
        </Route>
      </Routes>
    </DialogsProvider>,
    "/h/h1/assets",
  );

  fireEvent.click(await screen.findByText("Mortgage"));

  expect(await screen.findByRole("button", { name: "Update balance" })).toBeTruthy();
  expect(await screen.findByText(/From transfer/)).toBeTruthy();
  expect(screen.getAllByText("Borrowed").length).toBeGreaterThan(0);
  expect(screen.getAllByText("Repaid").length).toBeGreaterThan(0);
  expect(screen.getAllByText("Owed").length).toBeGreaterThan(0);
  expect(screen.getByRole("link", { name: "Transfer" }).getAttribute("href")).toBe("/households/h1/transfers");

  // Opening a transfer-backed contribution shows it read-only with no Save or Delete button.
  fireEvent.click(screen.getByText(/From transfer/));
  expect(await screen.findByRole("form", { name: "View contribution" })).toBeTruthy();
  expect((screen.getByLabelText("Amount (CZK)") as HTMLInputElement).disabled).toBe(true);
  expect((screen.getByLabelText("Date") as HTMLInputElement).disabled).toBe(true);
  expect(screen.queryByRole("button", { name: "Save" })).toBeNull();
  expect(screen.queryByRole("button", { name: "Delete" })).toBeNull();
  expect(screen.getByRole("link", { name: "Edit or delete the transfer" }).getAttribute("href")).toBe("/households/h1/transfers");
});
