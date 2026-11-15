import { describe, expect, test } from "vitest";
import type { Category, Expense, ExpenseLine } from "../api/types";
import { expenseTotals, spendingByCategory } from "./ExpensesPage";
import { refundLines, refundOriginals, spendingParts, spreadByWeight } from "./refunds";

const category = (id: string, name: string, extra: Partial<Category> = {}) =>
  ({ id, name, parentId: null, kind: "spending", archived: false, ...extra }) as Category;
const expense = (amountMinor: number, categoryId: string | null, lines: ExpenseLine[] = []) =>
  ({ amountMinor, categoryId, lines }) as Expense;
const line = (categoryId: string | null, amountMinor: number): ExpenseLine => ({
  categoryId,
  amountMinor,
  notes: "",
  paidByUserId: null,
});

describe("spendingByCategory", () => {
  const categories = [
    category("food", "Groceries"),
    category("drug", "Drugstore", { parentId: "food" }),
    category("rent", "Housing"),
    category("fund", "Investments", { kind: "investment" }),
  ];

  test("rolls subcategories into their parent, leaves out investing and sorts largest first", () => {
    const slices = spendingByCategory(
      [expense(1000, "food"), expense(500, "drug"), expense(9000, "rent"), expense(5000, "fund"), expense(300, null)],
      categories,
    );
    expect(slices).toEqual([
      { name: "Housing", amountMinor: 9000 },
      { name: "Groceries", amountMinor: 1500 },
      { name: "Uncategorized", amountMinor: 300 },
    ]);
  });

  test("nets refunds against their category and drops categories that end at zero or below", () => {
    const slices = spendingByCategory([expense(1000, "food"), expense(-1000, "food"), expense(200, "rent")], categories);
    expect(slices).toEqual([{ name: "Housing", amountMinor: 200 }]);
  });

  test("splits an expense with category lines across each line's category", () => {
    const splitReceipt = expense(1800, null, [line("food", 700), line("rent", 600), line("fund", 500)]);
    expect(spendingByCategory([splitReceipt], categories)).toEqual([
      { name: "Groceries", amountMinor: 700 },
      { name: "Housing", amountMinor: 600 },
    ]);
    expect(expenseTotals([splitReceipt], categories)).toEqual({
      spent: 1300,
      invested: 500,
    });
  });

  test("merges everything past the fifth category into Other", () => {
    const many = Array.from({ length: 8 }, (_, i) => category(`c${i}`, `C${i}`));
    const slices = spendingByCategory(
      many.map((c, i) => expense(800 - i * 100, c.id)),
      many,
    );
    expect(slices.map((s) => s.name)).toEqual(["C0", "C1", "C2", "C3", "C4", "Other"]);
    expect(slices[5].amountMinor).toBe(300 + 200 + 100);
  });
});

describe("refunds", () => {
  const categories = [category("food", "Groceries"), category("home", "Housing"), category("leisure", "Leisure")];
  const partial = (e: Partial<Expense>) => e as Expense;
  const original = (amountMinor: number, categoryId: string | null, refundedMinor: number, lines: ExpenseLine[] = []) =>
    partial({ amountMinor, categoryId, lines, refundOfId: null, refundedMinor });
  const refund = (amountMinor: number, categoryId: string | null) =>
    partial({ amountMinor, categoryId, lines: [], refundOfId: "original", refundedMinor: 0 });

  test("count against the original's category and leave the refund's own line out", () => {
    // The refund (dated in another month, so not in this list at all) can't be double counted when it is.
    const rows = [original(1000, "food", 400), refund(-400, "food"), original(500, "home", 0)];
    expect(spendingByCategory(rows, categories)).toEqual([
      { name: "Groceries", amountMinor: 600 },
      { name: "Housing", amountMinor: 500 },
    ]);
    expect(expenseTotals(rows, categories)).toEqual({ spent: 1100, invested: 0 });
    // Only the original is in this month's list: it is the one that carries the refund.
    expect(spendingByCategory([original(1000, "food", 400)], categories)).toEqual([{ name: "Groceries", amountMinor: 600 }]);
  });

  test("spread a split original's refunds over its lines in proportion", () => {
    const receipt = original(1000, null, 250, [line("home", 600), line("leisure", 400)]);
    expect(spendingParts(receipt)).toEqual([
      { categoryId: "home", amountMinor: 450 },
      { categoryId: "leisure", amountMinor: 300 },
    ]);
    expect(spendingByCategory([receipt], categories)).toEqual([
      { name: "Housing", amountMinor: 450 },
      { name: "Leisure", amountMinor: 300 },
    ]);
  });

  test("spread amounts add up exactly, like the server's", () => {
    expect(spreadByWeight(100, [1, 1, 1])).toEqual([34, 33, 33]);
    expect(spreadByWeight(-250, [600, 400])).toEqual([-150, -100]);
    expect(spreadByWeight(1000, [1, 2])).toEqual([333, 667]);
    expect(spreadByWeight(5, [])).toEqual([]);
    expect(refundLines([line("home", 600), { ...line("leisure", 400), paidByUserId: "u2" }], -250)).toEqual([
      { categoryId: "home", amountMinor: -150, notes: "", paidByUserId: null },
      { categoryId: "leisure", amountMinor: -100, notes: "", paidByUserId: "u2" },
    ]);
  });

  test("offer the newest positive expenses that aren't refunds, keeping the chosen one", () => {
    const e = (id: string, date: string, amountMinor: number, refundOfId: string | null = null) =>
      partial({ id, date, amountMinor, refundOfId });
    const list = [e("a", "2026-08-01", 100), e("b", "2026-09-01", 200), e("c", "2026-09-05", -50, "b"), e("d", "2026-07-01", 300)];
    expect(refundOriginals(list, null).map((x) => x.id)).toEqual(["b", "a", "d"]);
    expect(refundOriginals(list, "d", 2).map((x) => x.id)).toEqual(["b", "a", "d"]);
    expect(refundOriginals(list, null, 1).map((x) => x.id)).toEqual(["b"]);
  });
});
