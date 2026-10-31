import { describe, expect, test } from "vitest";
import type { Income } from "../api/types";
import { incomeBySource } from "./IncomePage";

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
