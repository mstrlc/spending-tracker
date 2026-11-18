import { afterEach, expect, test, vi } from "vitest";
import { cleanup, fireEvent, screen, waitFor } from "@testing-library/react";
import { Route, Routes } from "react-router";
import { DialogsProvider } from "../dialogs";
import { HouseholdLayout } from "../households/HouseholdPages";
import { formatDate } from "../lib/dates";
import { renderWithProviders, stubApi } from "../test-utils";
import { BalancesPage } from "./BalancesPage";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const members = [
  { userId: "u1", displayName: "Alice", role: "owner", hasLogin: true, legalName: "" },
  { userId: "u2", displayName: "Bob", role: "member", hasLogin: true, legalName: "" },
];

const entries = [
  {
    kind: "expense",
    id: "e-before",
    date: "2026-11-05",
    label: "Old coffee",
    amountMinor: 100_00,
    debts: [{ fromUserId: "u2", toUserId: "u1", amountMinor: 50_00 }],
  },
  {
    kind: "expense",
    id: "e-after",
    date: "2026-11-12",
    label: "New groceries",
    amountMinor: 200_00,
    debts: [{ fromUserId: "u2", toUserId: "u1", amountMinor: 100_00 }],
  },
];

function renderBalances(
  role: "owner" | "member",
  debtsFrom: string | null,
  extra: Record<string, (request: Request) => Response | Promise<Response>> = {},
) {
  stubApi({
    "GET /api/auth/me": () => Response.json({ id: "u1", email: "a@example.com", displayName: "Alice" }),
    "GET /api/households": () => Response.json([{ id: "h1", name: "Home", currency: "CZK", role, debtsFrom }]),
    "GET /api/households/h1/members": () => Response.json(members),
    "GET /api/households/h1/balances": () =>
      Response.json({
        balances: [{ fromUserId: "u2", toUserId: "u1", amountMinor: 100_00 }],
        entries,
        jointContributions: [],
      }),
    "GET /api/households/h1/expenses": () => Response.json([]),
    "GET /api/households/h1/settlements": () => Response.json([]),
    "GET /api/households/h1/split-rules": () => Response.json([]),
    "GET /api/households/h1/accounts": () => Response.json([]),
    "GET /api/households/h1/categories": () => Response.json([]),
    "GET /api/households/h1/settings": () => Response.json({ autoMatch: false, debtsFrom }),
    ...extra,
  });

  renderWithProviders(
    <DialogsProvider>
      <Routes>
        <Route path="/h/:householdId" element={<HouseholdLayout />}>
          <Route path="balances" element={<BalancesPage />} />
        </Route>
      </Routes>
    </DialogsProvider>,
    "/h/h1/balances",
  );
}

test("shows the debtsFrom date, hides earlier entries, and lets owners change it", async () => {
  let saved: unknown = null;
  renderBalances("owner", "2026-11-10", {
    "PUT /api/households/h1/settings": async (request) => {
      saved = await request.json();
      return Response.json(saved);
    },
  });

  expect(await screen.findByText(formatDate("2026-11-10"))).toBeTruthy();
  expect(await screen.findByText("New groceries")).toBeTruthy();
  expect(screen.queryByText("Old coffee")).toBeNull();

  const changeBtn = screen.getByRole("button", { name: "Change" });
  await waitFor(() => expect((changeBtn as HTMLButtonElement).disabled).toBe(false));
  fireEvent.click(changeBtn);

  const input = screen.getByLabelText("Balances count from");
  fireEvent.change(input, { target: { value: "2026-11-15" } });
  fireEvent.click(screen.getByRole("button", { name: "Save" }));

  await waitFor(() => expect(saved).toEqual({ autoMatch: false, debtsFrom: "2026-11-15" }));
});

test("members see the debtsFrom date without a change button", async () => {
  renderBalances("member", "2026-11-10");
  expect(await screen.findByText(formatDate("2026-11-10"))).toBeTruthy();
  expect(screen.queryByRole("button", { name: "Change" })).toBeNull();
});
