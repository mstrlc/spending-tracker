import { afterEach, expect, test, vi } from "vitest";
import { cleanup, fireEvent, screen, within } from "@testing-library/react";
import { Route, Routes } from "react-router";
import { DialogsProvider } from "../dialogs";
import { HouseholdLayout } from "../households/HouseholdPages";
import type { Asset } from "../api/types";
import { renderWithProviders, stubApi } from "../test-utils";
import { AssetsPage } from "./AssetsPage";
import { formatPercent, formatSigned } from "./holdings";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const members = [
  { userId: "u1", displayName: "Alice", role: "owner", hasLogin: true, legalName: "" },
  { userId: "u2", displayName: "Bob", role: "member", hasLogin: true, legalName: "" },
];

const asset = (over: Partial<Asset> & { id: string }): Asset => ({
  name: over.id,
  kind: "fund",
  ownerUserId: "u1",
  notes: "",
  version: 1,
  security: null,
  shares: null,
  marketValued: false,
  accountId: null,
  valueSource: "cost",
  contributedMinor: 0,
  repaidMinor: null,
  borrowedMinor: null,
  valueMinor: 0,
  valuedOn: "2026-11-10",
  gainMinor: null,
  annualReturn: null,
  firstContributedOn: null,
  feesMinor: 0,
  ...over,
});

const assets = [
  asset({ id: "a1", name: "Portu · Alice", kind: "fund", contributedMinor: 100_000_00, valueMinor: 150_000_00, gainMinor: 50_000_00, annualReturn: 0.12 }),
  asset({ id: "a2", name: "Portu · Bob", kind: "fund", ownerUserId: "u2", contributedMinor: 50_000_00, valueMinor: 60_000_00, gainMinor: 10_000_00 }),
  asset({ id: "a3", name: "Spoření", kind: "savings", valueSource: "bank", accountId: "acc1", contributedMinor: 48_000_00, valueMinor: 50_000_00 }),
  asset({ id: "a4", name: "Mortgage", kind: "loan", valueSource: "valuation", valueMinor: -80_000_00, contributedMinor: 20_000_00, repaidMinor: 20_000_00, borrowedMinor: 100_000_00, gainMinor: 0 }),
];

/** The page's whole-CZK formatting, so the test doesn't hardcode a locale's grouping. */
const czk = (minor: number) => new Intl.NumberFormat(undefined, { maximumFractionDigits: 0 }).format(Math.round(minor / 100));

function renderAssets() {
  stubApi({
    "GET /api/auth/me": () => Response.json({ id: "u1", email: "a@example.com", displayName: "Alice" }),
    "GET /api/households": () => Response.json([{ id: "h1", name: "Home", currency: "CZK", role: "owner", debtsFrom: null, jointFunding: "pot" }]),
    "GET /api/households/h1/bank-feeds": () => Response.json([]),
    "GET /api/households/h1/members": () => Response.json(members),
    "GET /api/households/h1/assets": () => Response.json(assets),
    "GET /api/households/h1/assets/summary": () =>
      Response.json({
        assetsMinor: 260_000_00,
        liabilitiesMinor: -80_000_00,
        netMinor: 180_000_00,
        contributedMinor: 198_000_00,
        gainMinor: 60_000_00,
        feesMinor: 0,
        annualReturn: 0.1,
      }),
    "GET /api/households/h1/assets/history": () =>
      Response.json({
        points: [
          { date: "2026-10-31", contributedMinor: 190_000_00, valueMinor: 240_000_00 },
          { date: "2026-11-12", contributedMinor: 198_000_00, valueMinor: 260_000_00 },
        ],
      }),
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
}

/** The table's rows as text, header row first. */
const rowTexts = () => screen.getAllByRole("row").map((r) => r.textContent ?? "");

test("groups assets by kind with totals, biggest first within a kind, under a net-worth summary and by-kind bar", async () => {
  renderAssets();

  // The summary leads with net worth and breaks owned/owed out because there is a liability.
  expect(await screen.findByText("Net worth")).toBeTruthy();
  expect(screen.getByText("Owned")).toBeTruthy();
  expect(screen.getByText("Owed")).toBeTruthy();
  expect(screen.getByLabelText("Owned assets by kind")).toBeTruthy();
  expect(screen.getByText("By kind")).toBeTruthy();

  const rows = rowTexts();
  const at = (text: string) => rows.findIndex((r) => r.includes(text));
  // Kind group headers carry the group's worth, gain and % in their own columns; holdings sit under their kind, biggest first.
  expect(at("Fund")).toBeGreaterThanOrEqual(0);
  const fundHeader = rows[at("Fund")];
  expect(fundHeader).toContain(czk(210_000_00));
  expect(fundHeader).toContain(formatSigned(60_000_00, "CZK"));
  expect(fundHeader).toContain(formatPercent(60_000_00 / 150_000_00));
  // The group header is one cell per column, so its worth lines up with the Worth column.
  expect(within(screen.getAllByRole("row")[at("Fund")]).getAllByRole("cell")).toHaveLength(6);
  expect(at("Portu · Alice")).toBeGreaterThan(at("Fund"));
  expect(at("Portu · Alice")).toBeLessThan(at("Portu · Bob"));
  expect(at("Savings")).toBeGreaterThan(at("Portu · Bob"));
  expect(rows[at("Savings")]).toContain(czk(50_000_00));
  expect(at("Spoření")).toBeGreaterThan(at("Savings"));
  // Liabilities get their own section, grouped the same way, owed shown positive.
  expect(screen.getByText("Liabilities")).toBeTruthy();
  expect(at("Loan")).toBeGreaterThan(at("Spoření"));
  expect(rows[at("Loan")]).toContain(czk(80_000_00));
  expect(at("Mortgage")).toBeGreaterThan(at("Loan"));

  // Sorting by worth flips within each kind.
  fireEvent.click(screen.getByRole("button", { name: /Worth/ }));
  const flipped = rowTexts();
  expect(flipped.findIndex((r) => r.includes("Portu · Bob"))).toBeLessThan(flipped.findIndex((r) => r.includes("Portu · Alice")));
});

test("can optionally group the assets by person instead of kind", async () => {
  renderAssets();
  expect(await screen.findByText("Net worth")).toBeTruthy();

  // Radix tabs activate on mouse down, not click.
  fireEvent.mouseDown(screen.getByRole("tab", { name: "By person" }));

  // The bar follows the grouping.
  expect(screen.getByLabelText("Net worth by person")).toBeTruthy();
  // Group headers are the members in order, each with its worth total; the Held by column is gone.
  expect(screen.queryByRole("button", { name: "Held by" })).toBeNull();
  const rows = rowTexts();
  const at = (text: string) => rows.findIndex((r) => r.includes(text));
  expect(rows[at("Alice")]).toContain(czk(200_000_00));
  expect(at("Alice")).toBeLessThan(at("Portu · Alice"));
  // Biggest first within the person: Portu · Alice (1,500) before Spoření (500).
  expect(at("Portu · Alice")).toBeLessThan(at("Spoření"));
  expect(at("Bob")).toBeGreaterThan(at("Spoření"));
  expect(rows[at("Bob")]).toContain(czk(60_000_00));
  expect(at("Portu · Bob")).toBeGreaterThan(at("Bob"));
  // Liabilities group by person too.
  expect(at("Mortgage")).toBeGreaterThan(at("Alice"));

  // Back to kinds.
  fireEvent.mouseDown(screen.getByRole("tab", { name: "By kind" }));
  expect(rowTexts().findIndex((r) => r.includes("Fund"))).toBeGreaterThanOrEqual(0);
  // Both tables show the Held by column again (exact name, so the "Filter Held by" buttons don't match).
  expect(screen.getAllByRole("button", { name: "Held by" })).toHaveLength(2);
});
