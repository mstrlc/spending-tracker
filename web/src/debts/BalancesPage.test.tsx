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
  jointFunding: "pot" | "contributions" = "pot",
) {
  stubApi({
    "GET /api/auth/me": () => Response.json({ id: "u1", email: "a@example.com", displayName: "Alice" }),
    "GET /api/households": () => Response.json([{ id: "h1", name: "Home", currency: "CZK", role, debtsFrom, jointFunding }]),
    "GET /api/households/h1/members": () => Response.json(members),
    "GET /api/households/h1/balances": () =>
      Response.json({
        balances: [{ fromUserId: "u2", toUserId: "u1", amountMinor: 100_00 }],
        entries,
        jointContributions: [],
        jointLeftover: [],
      }),
    "GET /api/households/h1/expenses": () => Response.json([]),
    "GET /api/households/h1/settlements": () => Response.json([]),
    "GET /api/households/h1/split-rules": () => Response.json([]),
    "GET /api/households/h1/accounts": () => Response.json([]),
    "GET /api/households/h1/categories": () => Response.json([]),
    "GET /api/households/h1/settings": () => Response.json({ autoMatch: false, debtsFrom, jointFunding }),
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

  await waitFor(() => expect(saved).toEqual({ autoMatch: false, debtsFrom: "2026-11-15", jointFunding: "pot" }));
});

test("members see the debtsFrom date without a change button", async () => {
  renderBalances("member", "2026-11-10");
  expect(await screen.findByText(formatDate("2026-11-10"))).toBeTruthy();
  expect(screen.queryByRole("button", { name: "Change" })).toBeNull();
  expect(screen.queryByLabelText("How joint accounts work")).toBeNull();
});

test("owners can switch jointFunding and top-up entries + leftover are shown", async () => {
  let saved: unknown = null;
  renderBalances(
    "owner",
    null,
    {
      "GET /api/households/h1/balances": () =>
        Response.json({
          balances: [{ fromUserId: "u2", toUserId: "u1", amountMinor: 500_00 }],
          entries: [
            {
              kind: "top_up",
              id: "t1",
              date: "2026-11-12",
              label: "Revolut Joint",
              amountMinor: 1000_00,
              debts: [{ fromUserId: "u2", toUserId: "u1", amountMinor: 500_00 }],
            },
          ],
          jointContributions: [{ userId: "u1", accountId: "a-joint", amountMinor: 1500_00 }],
          jointLeftover: [{ userId: "u1", accountId: "a-joint", amountMinor: 500_00 }],
        }),
      "PUT /api/households/h1/settings": async (request) => {
        saved = await request.json();
        return Response.json(saved);
      },
    },
    "contributions",
  );

  expect(await screen.findByText("Joint top-up")).toBeTruthy();
  expect(screen.getByText(/Still in joint accounts/)).toBeTruthy();
  const select = screen.getByLabelText("How joint accounts work") as HTMLSelectElement;
  await waitFor(() => expect(select.disabled).toBe(false));
  fireEvent.change(select, { target: { value: "pot" } });

  await waitFor(() => expect(saved).toEqual({ autoMatch: false, debtsFrom: null, jointFunding: "pot" }));
});
