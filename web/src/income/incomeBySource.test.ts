import { describe, expect, test } from "vitest";
import type { Income } from "../api/types";
import { incomeBySource } from "./IncomePage";
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
