import { describe, expect, it } from "vitest";
import type { Contribution, Asset, Valuation } from "../api/types";
import { formatPercent, gainRatio, groupByKind, groupByOwner, historyEntries, portfolioTotals } from "./holdings";

const holding = (contributedMinor: number, valueMinor: number): Asset => ({
  id: String(contributedMinor),
  name: "Fund",
  kind: "fund",
  ownerUserId: null,
  notes: "",
  version: 1,
  security: null,
  shares: null,
  marketValued: false,
  accountId: null,
  valueSource: "cost",
  contributedMinor,
  repaidMinor: null,
  borrowedMinor: null,
  valueMinor,
  valuedOn: null,
  gainMinor: valueMinor - contributedMinor,
  annualReturn: null,
  firstContributedOn: null,
  feesMinor: 0,
});

describe("portfolioTotals", () => {
  it("adds holdings up and measures the gain against what went in", () => {
    const totals = portfolioTotals([holding(100_000, 112_000), holding(50_000, 47_000)]);
    expect(totals).toEqual({ contributedMinor: 150_000, valueMinor: 159_000, gainMinor: 9_000, feesMinor: 0 });
    expect(formatPercent(gainRatio(totals)!)).toBe("+6.0%");
  });

  it("ignores null gain on uncontributed holdings", () => {
    const savings: Asset = { ...holding(0, 350_000), kind: "savings", valueSource: "bank", gainMinor: null };
    const totals = portfolioTotals([holding(100_000, 112_000), savings]);
    expect(totals).toEqual({ contributedMinor: 100_000, valueMinor: 462_000, gainMinor: 12_000, feesMinor: 0 });
    expect(gainRatio(savings)).toBeNull();
  });

  it("has no percentage when nothing is in", () => {
    expect(gainRatio({ contributedMinor: 0, gainMinor: 0 })).toBeNull();
    expect(formatPercent(-0.02)).toBe("−2.0%");
    expect(formatPercent(0.00001)).toBe("0.0%");
  });
});

describe("groupByKind", () => {
  it("groups by kind in KINDS order with each group's totals, leaving empty kinds out", () => {
    const loan: Asset = { ...holding(0, -80_000), id: "l1", name: "Loan", kind: "loan", valueSource: "valuation", gainMinor: 0 };
    const groups = groupByKind([
      { ...holding(48_000, 50_000), id: "s1", name: "Spoření", kind: "savings" },
      loan,
      holding(100_000, 112_000),
      { ...holding(10_000, 11_000), id: "f2", name: "Stock", kind: "stock" },
    ]);
    expect(groups.map((g) => [g.label, g.valueMinor, g.contributedMinor, g.gainMinor, g.rows.map((r) => r.id)])).toEqual([
      ["Fund", 112_000, 100_000, 12_000, ["100000"]],
      ["Stock", 11_000, 10_000, 1_000, ["f2"]],
      ["Savings", 50_000, 48_000, 2_000, ["s1"]],
      ["Loan", -80_000, 0, 0, ["l1"]],
    ]);
  });

  it("has no gain when none of the group's rows has one, so no percentage is shown", () => {
    const savings: Asset = { ...holding(0, 350_000), id: "s", name: "Spoření", kind: "savings", valueSource: "bank", gainMinor: null };
    const [group] = groupByKind([savings]);
    expect(group.valueMinor).toBe(350_000);
    expect(group.gainMinor).toBeNull();
    expect(gainRatio(group)).toBeNull();
  });
});

describe("groupByOwner", () => {
  it("groups in member order with shared last, owners outside the household counting as shared", () => {
    const a = (id: string, ownerUserId: string | null, valueMinor: number, gainMinor: number | null = null): Asset => ({
      ...holding(0, valueMinor),
      id,
      ownerUserId,
      gainMinor,
    });
    const groups = groupByOwner([a("x", "u2", 10), a("y", "u1", 20, 5), a("z", null, 30), a("w", "gone", 40)], ["u1", "u2"]);
    expect(groups.map((g) => [g.key, g.ownerUserId, g.valueMinor, g.gainMinor, g.rows.map((r) => r.id)])).toEqual([
      ["u1", "u1", 20, 5, ["y"]],
      ["u2", "u2", 10, null, ["x"]],
      ["shared", null, 70, null, ["z", "w"]],
    ]);
  });
});

describe("historyEntries", () => {
  it("is newest first, with a day's valuation above that day's contribution", () => {
    const contribution = (id: string, date: string): Contribution =>
      ({ id, assetId: "f", date, amountMinor: 100, originalAmountMinor: null, originalCurrency: null, quantity: null, accountId: null, transferId: null, notes: "", version: 1 });
    const valuation = (id: string, date: string): Valuation => ({ id, assetId: "f", date, valueMinor: 100, notes: "", version: 1 });

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

describe("formatAsset", () => {
  it("rounds crowns to whole CZK and keeps other currencies' cents", async () => {
    const { formatAsset, formatSigned } = await import("./holdings");
    expect(formatAsset(20_879_414, "CZK")).toBe(new Intl.NumberFormat().format(208_794));
    expect(formatAsset(8_550, "CZK")).toBe("86");
    expect(formatSigned(-91_304, "CZK")).toBe("−913");
    expect(formatSigned(30, "CZK")).toBe("0");
    expect(formatAsset(12_345, "EUR")).toBe(new Intl.NumberFormat(undefined, { minimumFractionDigits: 2 }).format(123.45));
  });
});

describe("feesByYear", () => {
  it("measures each year's fees against its average worth, scaling part years up", async () => {
    const { feesByYear, feeSummary } = await import("./holdings");
    const fee = (date: string, amountMinor: number) => ({ id: date + amountMinor, assetId: "p", date, amountMinor, notes: "", version: 1 });
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
