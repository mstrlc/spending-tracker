import { afterEach, expect, test, vi } from "vitest";
import { cleanup, fireEvent, screen, waitFor } from "@testing-library/react";
import { Route, Routes } from "react-router";
import { DialogsProvider } from "../dialogs";
import { HouseholdLayout } from "../households/HouseholdPages";
import { renderWithProviders, stubApi } from "../test-utils";
import { ExpenseForm } from "./ExpenseForm";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function stub(onSave: (body: unknown) => void) {
  stubApi({
    "GET /api/auth/me": () => Response.json({ id: "u1", email: "a@example.com", displayName: "Alice" }),
    "GET /api/households": () => Response.json([{ id: "h1", name: "Home", currency: "CZK", role: "owner" }]),
    "GET /api/households/h1/members": () =>
      Response.json([
        { userId: "u1", displayName: "Alice", role: "owner", hasLogin: true, legalName: "" },
        { userId: "u2", displayName: "Bob", role: "member", hasLogin: false, legalName: "" },
      ]),
    "GET /api/households/h1/accounts": () =>
      Response.json([{ id: "a1", name: "Alice Card", kind: "credit_card", ownerUserId: "u1", iban: null, status: "active", version: 1 }]),
    "GET /api/households/h1/categories": () => Response.json([]),
    "GET /api/households/h1/trips": () => Response.json([]),
    "GET /api/households/h1/merchants": () => Response.json([]),
    "GET /api/households/h1/bank-feeds": () => Response.json([]),
    "GET /api/households/h1/bank-rows": () => Response.json([]),
    "POST /api/households/h1/expenses": async (request) => {
      const body = await request.json();
      onSave(body);
      return Response.json({ id: "e1", version: 1, merchant: "", debts: [], lines: [], refundedMinor: 0, ...body }, { status: 201 });
    },
  });
  renderWithProviders(
    <DialogsProvider>
      <Routes>
        <Route path="/h/:householdId" element={<HouseholdLayout />}>
          <Route path="expenses" element={<ExpenseForm onDone={() => {}} />} />
        </Route>
      </Routes>
    </DialogsProvider>,
    "/h/h1/expenses",
  );
}

test("split lines can each belong to someone else, and the expense then has no single whose", async () => {
  let saved: any = null;
  stub((body) => (saved = body));

  fireEvent.change(await screen.findByLabelText(/Amount/), { target: { value: "1000" } });
  fireEvent.change(screen.getByLabelText("Account"), { target: { value: "a1" } });
  fireEvent.click(screen.getByLabelText(/Split into lines/));

  // Lines start as the expense's: Alice's, from her card.
  expect((screen.getByLabelText("Line 1 whose") as HTMLSelectElement).value).toBe("u1");
  fireEvent.change(screen.getByLabelText("Line 1 amount"), { target: { value: "600" } });
  fireEvent.change(screen.getByLabelText("Line 2 amount"), { target: { value: "400" } });
  fireEvent.change(screen.getByLabelText("Line 2 whose"), { target: { value: "u2" } });

  expect((screen.getByLabelText("Whose") as HTMLSelectElement).value).toBe("__mixed");
  expect(screen.getByText("Lines that aren't Alice's make others owe Alice")).toBeTruthy();

  fireEvent.click(screen.getByRole("button", { name: "Add expense" }));
  await waitFor(() => expect(saved).not.toBeNull());
  expect(saved.paidByUserId).toBeNull();
  expect(saved.lines.map((l: { paidByUserId: string | null; amountMinor: number }) => [l.paidByUserId, l.amountMinor])).toEqual([
    ["u1", 60_000],
    ["u2", 40_000],
  ]);
});

test("picking whose for the whole expense sets every line", async () => {
  let saved: any = null;
  stub((body) => (saved = body));

  fireEvent.change(await screen.findByLabelText(/Amount/), { target: { value: "50" } });
  fireEvent.click(screen.getByLabelText(/Split into lines/));
  fireEvent.change(screen.getByLabelText("Line 1 amount"), { target: { value: "20" } });
  fireEvent.change(screen.getByLabelText("Line 2 amount"), { target: { value: "30" } });
  fireEvent.change(screen.getByLabelText("Line 1 whose"), { target: { value: "u2" } });
  fireEvent.change(screen.getByLabelText("Whose"), { target: { value: "" } });

  expect((screen.getByLabelText("Line 1 whose") as HTMLSelectElement).value).toBe("");
  expect((screen.getByLabelText("Line 2 whose") as HTMLSelectElement).value).toBe("");

  fireEvent.click(screen.getByRole("button", { name: "Add expense" }));
  await waitFor(() => expect(saved).not.toBeNull());
  expect(saved.lines.map((l: { paidByUserId: string | null }) => l.paidByUserId)).toEqual([null, null]);
});
