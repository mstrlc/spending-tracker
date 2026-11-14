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
  feesMinor: 0,
});

describe("portfolioTotals", () => {
  it("adds holdings up and measures the gain against what went in", () => {
    const totals = portfolioTotals([holding(100_000, 112_000), holding(50_000, 47_000)]);
    expect(totals).toEqual({ contributedMinor: 150_000, valueMinor: 159_000, gainMinor: 9_000, feesMinor: 0 });
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
    ).map((e) => (e.type === "valuation" ? e.valuation.id : e.type === "fee" ? e.fee.id : e.contribution.id));
    expect(order).toEqual(["v2", "v1", "c2", "c1"]);
  });
});

describe("niceMax", () => {
  it("rounds up to 1, 2 or 5 times a power of ten", async () => {
    const { niceMax } = await import("./HistoryChart");
    expect([niceMax(0), niceMax(430_000), niceMax(180), niceMax(1_000)]).toEqual([1, 500_000, 200, 1_000]);
  });
});

describe("formatInvestment", () => {
  it("rounds crowns to whole CZK and keeps other currencies' cents", async () => {
    const { formatInvestment, formatSigned } = await import("./holdings");
    expect(formatInvestment(20_879_414, "CZK")).toBe(new Intl.NumberFormat().format(208_794));
    expect(formatInvestment(8_550, "CZK")).toBe("86");
    expect(formatSigned(-91_304, "CZK")).toBe("−913");
    expect(formatSigned(30, "CZK")).toBe("0");
    expect(formatInvestment(12_345, "EUR")).toBe(new Intl.NumberFormat(undefined, { minimumFractionDigits: 2 }).format(123.45));
  });
});

describe("feesByYear", () => {
  it("measures each year's fees against its average worth, scaling part years up", async () => {
    const { feesByYear, feeSummary } = await import("./holdings");
    const fee = (date: string, amountMinor: number) => ({ id: date + amountMinor, investmentId: "p", date, amountMinor, notes: "", version: 1 });
    const point = (date: string, valueMinor: number) => ({ date, contributedMinor: 0, valueMinor });
    const fees = [fee("2025-02-05", 10_000), fee("2025-03-05", 10_000), fee("2025-03-05", -4_000), fee("2026-01-05", 6_000)];
    // January is before the first fee, so it is left out of the averages.
    const points = [point("2025-01-31", 9_000_000), point("2025-02-28", 1_000_000), point("2025-03-31", 1_200_000), point("2026-01-31", 2_000_000)];

    const [y2026, y2025] = feesByYear(fees, points);
    // 160 CZK over 2 months of fees against 11,000 CZK on average: 0.0145… × 6.
    expect(y2025).toMatchObject({ year: "2025", feesMinor: 16_000, averageWorthMinor: 1_100_000 });
    expect(y2025.yearlyRate).toBeCloseTo((16_000 / 1_100_000) * 6);
    expect(y2026.yearlyRate).toBeCloseTo((6_000 / 2_000_000) * 12);

    const summary = feeSummary(fees, points, { contributedMinor: 1_100_000, gainMinor: 78_000 });
    expect(summary.feesMinor).toBe(22_000);
    expect(summary.ofContributed).toBeCloseTo(0.02);
    expect(summary.ofGainBeforeFees).toBeCloseTo(0.22);
  });
});
