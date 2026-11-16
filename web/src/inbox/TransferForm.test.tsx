import { afterEach, expect, test, vi } from "vitest";
import { cleanup, fireEvent, screen, waitFor } from "@testing-library/react";
import { Route, Routes } from "react-router";
import type { Transfer } from "../api/types";
import { DialogsProvider } from "../dialogs";
import { HouseholdLayout } from "../households/HouseholdPages";
import { renderWithProviders, stubApi } from "../test-utils";
import { TransferForm } from "./TransferForm";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const accounts = [
  { id: "a1", name: "Current", ownerUserId: "u1", kind: "bank", iban: null, status: "active", version: 1 },
  { id: "a2", name: "Cash", ownerUserId: "u1", kind: "cash", iban: null, status: "active", version: 1 },
  { id: "a3", name: "Joint", ownerUserId: null, kind: "bank", iban: null, status: "active", version: 1 },
];

function renderForm(
  props: { transfer?: Transfer; onDone?: () => void },
  extraRoutes: Record<string, (req: Request) => Response | Promise<Response>> = {},
) {
  stubApi({
    "GET /api/auth/me": () => Response.json({ id: "u1", email: "a@example.com", displayName: "Alice" }),
    "GET /api/households": () => Response.json([{ id: "h1", name: "Home", currency: "CZK", role: "owner" }]),
    "GET /api/households/h1/accounts": () => Response.json(accounts),
    "GET /api/households/h1/bank-feeds": () => Response.json([]),
    ...extraRoutes,
  });
  renderWithProviders(
    <DialogsProvider>
      <Routes>
        <Route path="/h/:householdId" element={<HouseholdLayout />}>
          <Route
            path="transfers"
            element={<TransferForm transfer={props.transfer} defaultDate="2026-09-10" onDone={props.onDone ?? (() => {})} />}
          />
        </Route>
      </Routes>
    </DialogsProvider>,
    "/h/h1/transfers",
  );
}

test("creates a hand-entered transfer", async () => {
  let sentBody: unknown = null;
  const onDone = vi.fn();
  renderForm(
    { onDone },
    {
      "POST /api/households/h1/transfers": async (req) => {
        sentBody = await req.json();
        return Response.json(
          {
            id: "t1",
            date: "2026-09-10",
            amountMinor: 150000,
            fromAccountId: "a1",
            toAccountId: "a3",
            notes: "top-up",
            version: 1,
            rowIds: [],
            bankAccountIds: [],
          },
          { status: 201 },
        );
      },
    },
  );

  expect(await screen.findByRole("form", { name: "Add transfer" })).toBeTruthy();
  await waitFor(() => expect(screen.getAllByRole("option", { name: "Current" }).length).toBeGreaterThan(0));
  fireEvent.change(screen.getByLabelText("Amount (CZK)"), { target: { value: "1500" } });
  fireEvent.change(screen.getByLabelText("From"), { target: { value: "a1" } });
  fireEvent.change(screen.getByLabelText("To"), { target: { value: "a3" } });
  fireEvent.change(screen.getByLabelText("Notes"), { target: { value: "top-up" } });
  fireEvent.click(screen.getByRole("button", { name: "Add" }));

  await waitFor(() => expect(onDone).toHaveBeenCalled());
  expect(sentBody).toEqual({
    date: "2026-09-10",
    amountMinor: 150000,
    fromAccountId: "a1",
    toAccountId: "a3",
    notes: "top-up",
  });
});

test("edits a hand-entered transfer", async () => {
  let sentBody: unknown = null;
  const onDone = vi.fn();
  const transfer: Transfer = {
    id: "t1",
    date: "2026-09-10",
    amountMinor: 150000,
    fromAccountId: "a1",
    toAccountId: "a2",
    notes: "ATM",
    version: 2,
    rowIds: [],
    bankAccountIds: [],
  };
  renderForm(
    { transfer, onDone },
    {
      "PUT /api/households/h1/transfers/t1": async (req) => {
        sentBody = await req.json();
        return Response.json({ ...transfer, amountMinor: 200000, notes: "cash", version: 3 });
      },
    },
  );

  expect(await screen.findByRole("form", { name: "Edit transfer" })).toBeTruthy();
  fireEvent.change(screen.getByLabelText("Amount (CZK)"), { target: { value: "2000" } });
  fireEvent.change(screen.getByLabelText("Notes"), { target: { value: "cash" } });
  fireEvent.click(screen.getByRole("button", { name: "Save" }));

  await waitFor(() => expect(onDone).toHaveBeenCalled());
  expect(sentBody).toEqual({
    date: "2026-09-10",
    amountMinor: 200000,
    fromAccountId: "a1",
    toAccountId: "a2",
    notes: "cash",
    version: 2,
  });
});

test("locks bank-row fields on a one-row transfer and allows editing the unread account and notes", async () => {
  const transfer: Transfer = {
    id: "t2",
    date: "2026-09-10",
    amountMinor: 50000,
    fromAccountId: "a1",
    toAccountId: "a2",
    notes: "",
    version: 1,
    rowIds: ["r1"],
    bankAccountIds: ["a1"],
  };
  renderForm({ transfer });

  expect(await screen.findByRole("form", { name: "Edit transfer" })).toBeTruthy();
  expect((screen.getByLabelText("Amount (CZK)") as HTMLInputElement).disabled).toBe(true);
  expect((screen.getByLabelText("Date") as HTMLInputElement).disabled).toBe(true);
  expect((screen.getByLabelText("From") as HTMLSelectElement).disabled).toBe(true);
  expect((screen.getByLabelText("To") as HTMLSelectElement).disabled).toBe(false);
  expect((screen.getByLabelText("Notes") as HTMLInputElement).disabled).toBe(false);
});
