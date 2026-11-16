import { describe, expect, it } from "vitest";
import type { Account, BankRow, BankRowSuggestion, Category } from "../api/types";
import { activeFilterCount, filterRows, NO_FILTERS, rowPerson, suggestedKind } from "./filters";

const suggestion = (s: Partial<BankRowSuggestion>) =>
  ({
    record: "expense",
    amountMinor: null,
    merchant: "",
    categoryId: null,
    paidByUserId: null,
    incomeSource: "",
    duplicates: [],
    transferCandidates: [],
    transferAccountId: null,
    skipReason: null,
    incomeKind: "earning",
    refundCandidates: [],
    ...s,
  }) as BankRowSuggestion;

const row = (id: string, r: Partial<BankRow>) =>
  ({
    id,
    accountId: "acc-joint",
    date: "2026-11-10",
    amountMinor: -1000,
    currency: "CZK",
    text: id,
    counterpartyName: "",
    counterpartyAccount: "",
    message: "",
    hint: "",
    outcome: "pending",
    outcomeLabel: null,
    skipReason: null,
    suggestion: suggestion({}),
    ...r,
  }) as BankRow;

const accounts = [
  { id: "acc-joint", ownerUserId: null },
  { id: "acc-ann", ownerUserId: "ann" },
] as Account[];
const categories = [
  { id: "food", parentId: null },
  { id: "snacks", parentId: "food" },
  { id: "home", parentId: null },
] as Category[];

const ids = (rows: BankRow[]) => rows.map((r) => r.id);

describe("filterRows", () => {
  const rows = [
    row("Albert", { date: "2026-11-01", amountMinor: -500, suggestion: suggestion({ categoryId: "snacks", paidByUserId: "ann", merchant: "Albert" }) }),
    row("Salary", { date: "2026-11-05", amountMinor: 90000, accountId: "acc-ann", suggestion: suggestion({ record: "income", paidByUserId: "ann" }) }),
    row("Rent", { date: "2026-11-03", amountMinor: -17100, suggestion: suggestion({ categoryId: "home", amountMinor: 17100 }) }),
    row("Top-up", { date: "2026-11-04", amountMinor: -2000, accountId: "acc-ann", suggestion: suggestion({ record: "transfer", paidByUserId: null }) }),
    row("Café", { date: "2026-11-02", amountMinor: -120, suggestion: suggestion({}) }),
  ];
  const run = (f: Partial<typeof NO_FILTERS>) => ids(filterRows(rows, { ...NO_FILTERS, ...f }, accounts, categories));

  it("sorts newest first by default", () => {
    expect(run({})).toEqual(["Salary", "Top-up", "Rent", "Café", "Albert"]);
  });

  it("sorts by amount", () => {
    expect(run({ sort: "spent" })).toEqual(["Rent", "Top-up", "Albert", "Café", "Salary"]);
    expect(run({ sort: "received" })[0]).toBe("Salary");
    expect(run({ sort: "largest" })).toEqual(["Salary", "Rent", "Top-up", "Albert", "Café"]);
  });

  it("keeps a date range, both ends included", () => {
    expect(run({ from: "2026-11-02", to: "2026-11-04", sort: "oldest" })).toEqual(["Café", "Rent", "Top-up"]);
  });

  it("filters by whose: a suggestion's whose for spending, the account's owner otherwise", () => {
    expect(run({ person: "ann" })).toEqual(["Salary", "Top-up", "Albert"]);
    expect(run({ person: "joint" })).toEqual(["Rent", "Café"]);
  });

  it("counts subcategories under their parent and finds uncategorised spending", () => {
    expect(run({ categoryId: "food" })).toEqual(["Albert"]);
    expect(run({ categoryId: "none" })).toEqual(["Café"]);
  });

  it("filters by kind, direction and text, ignoring accents", () => {
    expect(run({ kind: "transfer" })).toEqual(["Top-up"]);
    expect(run({ direction: "in" })).toEqual(["Salary"]);
    expect(run({ query: "cafe" })).toEqual(["Café"]);
  });
});

describe("helpers", () => {
  it("calls a positive expense suggestion a refund", () => {
    expect(suggestedKind(row("r", { amountMinor: 300 }))).toBe("refund");
  });

  it("reads a decided row's whose from its account", () => {
    expect(rowPerson(row("d", { outcome: "expense", accountId: "acc-ann", suggestion: null }), accounts)).toBe("ann");
  });

  it("doesn't count the sort as a filter", () => {
    expect(activeFilterCount({ ...NO_FILTERS, sort: "oldest" })).toBe(0);
    expect(activeFilterCount({ ...NO_FILTERS, from: "2026-11-01", kind: "skip" })).toBe(2);
  });

  it("calls a row suggested into an asset an investment", () => {
    expect(suggestedKind(row("portu", { suggestion: suggestion({ record: "contribution", assetId: "asset-1" }) }))).toBe("contribution");
  });
});
