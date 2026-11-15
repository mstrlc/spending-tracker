import { afterEach, expect, test, vi } from "vitest";
import { cleanup, fireEvent, screen, waitFor, within } from "@testing-library/react";
import { Route, Routes } from "react-router";
import { DialogsProvider } from "../dialogs";
import { HouseholdLayout } from "../households/HouseholdPages";
import { renderWithProviders, stubApi } from "../test-utils";
import { InboxPage } from "./InboxPage";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

test("clicking Settle on a settlement suggestion opens SettleForm prefilled with members, amount and paired row", async () => {
  let settledBody: unknown = null;
  stubApi({
    "GET /api/auth/me": () => Response.json({ id: "u1", email: "a@example.com", displayName: "Alice" }),
    "GET /api/households": () => Response.json([{ id: "h1", name: "Home", currency: "CZK", role: "owner" }]),
    "GET /api/households/h1/members": () =>
      Response.json([
        { userId: "u1", displayName: "Alice", role: "owner", hasLogin: true, legalName: "" },
        { userId: "u2", displayName: "Bob", role: "member", hasLogin: false, legalName: "Robert" },
      ]),
    "GET /api/households/h1/accounts": () =>
      Response.json([
        { id: "a1", name: "Alice Bank", kind: "bank", ownerUserId: "u1", iban: null, closed: false, version: 1 },
        { id: "a2", name: "Bob Bank", kind: "bank", ownerUserId: "u2", iban: null, closed: false, version: 1 },
      ]),
    "GET /api/households/h1/categories": () => Response.json([]),
    "GET /api/households/h1/bank-feeds": () =>
      Response.json([
        {
          id: "f1",
          accountId: "a1",
          name: "Alice Feed",
          institution: "other",
          source: "file",
          iban: null,
          currency: "CZK",
          importFrom: null,
          version: 1,
          rowCount: 1,
          pendingCount: 1,
          lastDate: "2026-09-12",
          connectionId: null,
          lastSyncedAt: null,
          syncsToday: 0,
          balance: null,
        },
      ]),
    "GET /api/households/h1/bank-rows": () =>
      Response.json([
        {
          id: "r1",
          feedId: "f1",
          accountId: "a1",
          date: "2026-09-12",
          bookingDate: "2026-09-12",
          amountMinor: -50_000,
          currency: "CZK",
          counterpartyName: "",
          counterpartyIban: null,
          counterpartyAccount: "",
          text: "To Bob",
          message: "",
          code: "",
          hint: "",
          merchantKey: "TO BOB",
          outcome: "pending",
          expenseId: null,
          incomeId: null,
          transferId: null,
          settlementId: null,
          skipReason: null,
          decidedByUserId: null,
          decidedAuto: false,
          ruleId: null,
          decidedAt: null,
          version: 1,
          outcomeLabel: null,
          suggestion: {
            record: "settlement",
            amountMinor: 50_000,
            originalAmountMinor: null,
            originalCurrency: null,
            merchant: "To Bob",
            categoryId: null,
            paidByUserId: "u1",
            incomeSource: "To Bob",
            duplicates: [],
            transferCandidates: [
              {
                rowId: "r2",
                version: 2,
                accountId: "a2",
                date: "2026-09-12",
                amountMinor: 50_000,
                currency: "CZK",
                text: "From Alice",
              },
            ],
            transferAccountId: null,
            settleFromUserId: "u1",
            settleToUserId: "u2",
            ruleId: null,
            ruleName: null,
            skipReason: null,
            incomeKind: "earning",
            earnedMonth: null,
            refundCandidates: [],
          },
        },
      ]),
    "POST /api/households/h1/bank-rows/settle": async (request) => {
      settledBody = await request.json();
      return Response.json([]);
    },
  });

  renderWithProviders(
    <DialogsProvider>
      <Routes>
        <Route path="/h/:householdId" element={<HouseholdLayout />}>
          <Route path="inbox" element={<InboxPage />} />
        </Route>
      </Routes>
    </DialogsProvider>,
    "/h/h1/inbox",
  );

  expect((await screen.findAllByText("Settlement")).length).toBeGreaterThan(0);
  expect((await screen.findAllByText("Alice → Bob")).length).toBeGreaterThan(0);

  fireEvent.click(await screen.findByRole("button", { name: "Settle" }));

  const form = await screen.findByRole("form", { name: "Settle between members" });
  expect(form).toBeTruthy();
  expect((screen.getByLabelText("Paid by") as HTMLSelectElement).value).toBe("u1");
  expect((screen.getByLabelText("To") as HTMLSelectElement).value).toBe("u2");
  expect(within(form).getByText("From Alice")).toBeTruthy();
  expect(within(form).getByText(/^CZK\s500\.00$/)).toBeTruthy();

  fireEvent.click(within(form).getByRole("button", { name: "Settle" }));
  await waitFor(() =>
    expect(settledBody).toEqual({
      rows: [
        { id: "r1", version: 1 },
        { id: "r2", version: 2 },
      ],
      fromUserId: "u1",
      toUserId: "u2",
      notes: "",
    }),
  );
});
