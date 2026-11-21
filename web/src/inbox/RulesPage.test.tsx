import { afterEach, expect, test, vi } from "vitest";
import { cleanup, fireEvent, screen, waitFor } from "@testing-library/react";
import { Route, Routes } from "react-router";
import { DialogsProvider } from "../dialogs";
import { HouseholdLayout } from "../households/HouseholdPages";
import { renderWithProviders, stubApi } from "../test-utils";
import { describeApplied, RulesPage } from "./RulesPage";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const rule = {
  id: "r1",
  name: "Bakery",
  counterpartyIban: null,
  merchantKey: null,
  textContains: "BAKERY",
  feedId: null,
  accountId: null,
  direction: "out",
  outcome: "expense",
  merchant: "Corner Bakery",
  categoryId: null,
  personUserId: null,
  incomeSource: "",
  otherAccountId: null,
  skipReason: "",
  auto: true,
  priority: 0,
  version: 1,
  filedCount: 4,
};

function renderRules(role: "owner" | "member", extra: Record<string, (request: Request) => Response | Promise<Response>> = {}) {
  const api = stubApi({
    "GET /api/auth/me": () => Response.json({ id: "u1", email: "a@example.com", displayName: "Alice" }),
    "GET /api/households": () => Response.json([{ id: "h1", name: "Home", currency: "CZK", role }]),
    "GET /api/households/h1/bank-feeds": () => Response.json([]),
    "GET /api/households/h1/accounts": () => Response.json([]),
    "GET /api/households/h1/members": () => Response.json([]),
    "GET /api/households/h1/categories": () => Response.json([]),
    "GET /api/households/h1/inbox-rules": () => Response.json([rule]),
    "GET /api/households/h1/settings": () => Response.json({ autoMatch: false }),
    ...extra,
  });
  renderWithProviders(
    <DialogsProvider>
      <Routes>
        <Route path="/h/:householdId" element={<HouseholdLayout />}>
          <Route path="rules" element={<RulesPage />} />
        </Route>
      </Routes>
    </DialogsProvider>,
    "/h/h1/rules",
  );
  return api;
}

test("lists rules with what they match and become, and lets owners turn the matcher on", async () => {
  let saved: unknown = null;
  renderRules("owner", {
    "PUT /api/households/h1/settings": async (request) => {
      saved = await request.json();
      return Response.json(saved);
    },
  });

  // Narrow and wide layouts each have their own copy.
  expect((await screen.findAllByText("Text contains BAKERY · Money out")).length).toBeGreaterThan(0);
  expect(screen.getAllByText("Corner Bakery").length).toBeGreaterThan(0);
  expect(screen.getAllByText("Expense").length).toBeGreaterThan(0);
  expect(screen.getByText("Automatic")).toBeTruthy();

  const toggle = await screen.findByLabelText(/File transfers and duplicates automatically/);
  await waitFor(() => expect((toggle as HTMLButtonElement).disabled).toBe(false));
  fireEvent.click(toggle);
  await waitFor(() => expect(saved).toEqual({ autoMatch: true }));
});

test("members see the matcher setting but can't change it", async () => {
  renderRules("member");
  const toggle = await screen.findByLabelText(/File transfers and duplicates automatically/);
  expect((toggle as HTMLButtonElement).disabled).toBe(true);
});

test("applying the rules says what was filed", async () => {
  renderRules("owner", {
    "POST /api/households/h1/bank-rows/apply-rules": () =>
      Response.json({ checked: 9, filedByRules: 3, linkedByRules: 1, pairedByMatcher: 2, duplicatesByMatcher: 0 }),
  });
  const apply = await screen.findByRole("button", { name: "Apply to the inbox" });
  // Nothing to apply until the rules have loaded.
  await waitFor(() => expect((apply as HTMLButtonElement).disabled).toBe(false));
  fireEvent.click(apply);
  expect(await screen.findByText("3 rows filed by rules (1 under something you had already entered), 2 rows paired as transfers.")).toBeTruthy();
});

test("a new rule is sent with its conditions and outcome", async () => {
  let body: Record<string, unknown> | null = null;
  renderRules("owner", {
    "POST /api/households/h1/inbox-rules": async (request) => {
      body = (await request.json()) as Record<string, unknown>;
      return Response.json({ ...rule, id: "r2" }, { status: 201 });
    },
  });
  fireEvent.click(await screen.findByRole("button", { name: "New rule" }));
  const add = await screen.findByRole("button", { name: "Add rule" });
  // A rule has to match on something.
  expect((add as HTMLButtonElement).disabled).toBe(true);
  fireEvent.change(screen.getByLabelText("Name"), { target: { value: "Gym" } });
  fireEvent.change(screen.getByLabelText("Contains the text"), { target: { value: "gym" } });
  fireEvent.change(screen.getByLabelText("Outcome"), { target: { value: "skip" } });
  fireEvent.change(screen.getByLabelText("Why"), { target: { value: "Paid elsewhere" } });
  fireEvent.click(screen.getByLabelText(/File matching rows without asking/));
  expect((add as HTMLButtonElement).disabled).toBe(false);
  fireEvent.click(add);
  await waitFor(() => expect(body).not.toBeNull());
  expect(body).toMatchObject({ name: "Gym", textContains: "gym", outcome: "skip", skipReason: "Paid elsewhere", auto: true, direction: null, priority: 0 });
});

test("describeApplied reads naturally", () => {
  expect(describeApplied({ checked: 5, filedByRules: 0, linkedByRules: 0, pairedByMatcher: 0, duplicatesByMatcher: 0 })).toBe(
    "Nothing to file among the 5 rows waiting.",
  );
  expect(describeApplied({ checked: 1, filedByRules: 1, linkedByRules: 0, pairedByMatcher: 0, duplicatesByMatcher: 1 })).toBe(
    "1 row filed by rules, 1 row filed under an expense you had already entered.",
  );
});
