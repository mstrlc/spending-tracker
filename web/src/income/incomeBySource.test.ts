import { describe, expect, test } from "vitest";
import type { Category, Income } from "../api/types";
import { incomeByCategory, incomeBySource } from "./IncomePage";
import { incomeTotals } from "./net";

const income = (amountMinor: number, source: string) => ({ amountMinor, source }) as Income;

describe("incomeBySource", () => {
  test("sums per source, largest first, naming blank sources", () => {
    expect(incomeBySource([income(100, "Acme"), income(500, ""), income(300, "Acme")])).toEqual([
      { name: "No source", amountMinor: 500 },
      { name: "Acme", amountMinor: 400 },
    ]);
  });

  test("nets clawbacks against their source", () => {
    expect(incomeBySource([income(1000, "Acme"), income(-1000, "Acme"), income(50, "Globex")])).toEqual([
      { name: "Globex", amountMinor: 50 },
    ]);
  });
});

describe("deductions", () => {
  const deduction = (amountMinor: number, source: string) => ({ amountMinor, source, kind: "deduction" }) as Income;

  test("aren't a source of income", () => {
    expect(incomeBySource([income(1000, "Acme"), deduction(300, "ZPMV")])).toEqual([{ name: "Acme", amountMinor: 1000 }]);
  });

  test("come off what was earned, a refunded advance going back on", () => {
    expect(incomeTotals([income(1000, "Acme"), deduction(300, "ZPMV"), deduction(-50, "ZPMV")])).toEqual({
      earned: 1000,
      deducted: 250,
      net: 750,
    });
  });
});

describe("incomeByCategory", () => {
  const cat = (id: string, name: string, parentId: string | null = null) =>
    ({ id, name, parentId, kind: "income" }) as Category;
  const earning = (amountMinor: number, categoryId: string | null) =>
    ({ amountMinor, categoryId, kind: "earning" }) as Income;

  test("rolls subcategories up into their top-level category and ignores deductions", () => {
    const categories = [cat("sal", "Salary"), cat("bon", "Bonus", "sal"), cat("fre", "Freelance")];
    expect(
      incomeByCategory(
        [
          earning(500, "sal"),
          earning(200, "bon"),
          earning(300, "fre"),
          earning(100, null),
          { amountMinor: 400, categoryId: null, kind: "deduction" } as Income,
        ],
        categories,
      ),
    ).toEqual([
      { name: "Salary", amountMinor: 700, tone: "hue-1" },
      { name: "Freelance", amountMinor: 300, tone: "hue-2" },
      { name: "Uncategorized", amountMinor: 100, tone: "neutral" },
    ]);
  });
});
