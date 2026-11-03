import { describe, expect, it } from "vitest";
import type { Contribution, Investment, Valuation } from "../api/types";
import { formatPercent, gainRatio, historyEntries, portfolioTotals } from "./holdings";

const holding = (contributedMinor: number, valueMinor: number): Investment => ({
  id: String(contributedMinor),
  name: "Fund",
  kind: "fund",
  ownerUserId: null,
  notes: "",
  version: 1,
  security: null,
  shares: null,
  marketValued: false,
  contributedMinor,
  valueMinor,
  valuedOn: null,
  gainMinor: valueMinor - contributedMinor,
  firstContributedOn: null,
});

describe("portfolioTotals", () => {
  it("adds holdings up and measures the gain against what went in", () => {
    const totals = portfolioTotals([holding(100_000, 112_000), holding(50_000, 47_000)]);
    expect(totals).toEqual({ contributedMinor: 150_000, valueMinor: 159_000, gainMinor: 9_000 });
    expect(formatPercent(gainRatio(totals)!)).toBe("+6.0%");
  });

  it("has no percentage when nothing is in", () => {
    expect(gainRatio({ contributedMinor: 0, gainMinor: 0 })).toBeNull();
    expect(formatPercent(-0.02)).toBe("−2.0%");
    expect(formatPercent(0.00001)).toBe("0.0%");
  });
});

describe("historyEntries", () => {
  it("is newest first, with a day's valuation above that day's contribution", () => {
    const contribution = (id: string, date: string): Contribution =>
      ({ id, investmentId: "f", date, amountMinor: 100, quantity: null, accountId: null, notes: "", version: 1 });
    const valuation = (id: string, date: string): Valuation => ({ id, investmentId: "f", date, valueMinor: 100, notes: "", version: 1 });

    const order = historyEntries(
      [contribution("c1", "2026-01-15"), contribution("c2", "2026-06-30")],
      [valuation("v1", "2026-06-30"), valuation("v2", "2026-10-31")],
    ).map((e) => (e.type === "valuation" ? e.valuation.id : e.contribution.id));
    expect(order).toEqual(["v2", "v1", "c2", "c1"]);
  });
});

describe("niceMax", () => {
  it("rounds up to 1, 2 or 5 times a power of ten", async () => {
    const { niceMax } = await import("./HistoryChart");
    expect([niceMax(0), niceMax(430_000), niceMax(180), niceMax(1_000)]).toEqual([1, 500_000, 200, 1_000]);
  });
});
