import { describe, expect, test } from "vitest";
import type { Category, Expense } from "../api/types";
import { spendingByCategory } from "./ExpensesPage";

const category = (id: string, name: string, extra: Partial<Category> = {}) =>
  ({ id, name, parentId: null, kind: "spending", archived: false, ...extra }) as Category;
const expense = (amountMinor: number, categoryId: string | null) => ({ amountMinor, categoryId }) as Expense;

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
