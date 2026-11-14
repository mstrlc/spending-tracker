import { describe, expect, it } from "vitest";
import { byYear, monthPeriod, percentChange, periodRange, readPeriod, writePeriod } from "./period";

describe("report periods", () => {
  it("defaults to the current year and reads the others back", () => {
    expect(readPeriod(new URLSearchParams(""), "2026-11-21")).toEqual({ kind: "year", year: 2026 });
    expect(readPeriod(new URLSearchParams("period=2024"), "2026-11-21")).toEqual({ kind: "year", year: 2024 });
    expect(readPeriod(new URLSearchParams("period=3m"))).toEqual({ kind: "last", months: 3 });
    expect(readPeriod(new URLSearchParams("period=3y"))).toEqual({ kind: "last", months: 36 });
    // The old value still reads.
    expect(readPeriod(new URLSearchParams("period=12m"))).toEqual({ kind: "last", months: 12 });
    expect(readPeriod(new URLSearchParams("period=custom&from=2026-03-01&to=2026-03-31"))).toEqual({
      kind: "custom",
      from: "2026-03-01",
      to: "2026-03-31",
    });
    // Unreadable or backwards dates fall back to the year.
    expect(readPeriod(new URLSearchParams("period=custom&from=2026-04-01&to=2026-03-01"), "2026-11-21")).toEqual({ kind: "year", year: 2026 });
  });

  it("round-trips through the URL", () => {
    const params = new URLSearchParams("category=x&from=old");
    writePeriod(params, { kind: "custom", from: "2026-02-01", to: "2026-02-28" });
    expect(params.toString()).toBe("category=x&period=custom&from=2026-02-01&to=2026-02-28");
    writePeriod(params, { kind: "all" });
    expect(params.toString()).toBe("category=x&period=all");
    writePeriod(params, { kind: "last", months: 12 });
    expect(params.toString()).toBe("category=x&period=1y");
  });

  it("turns periods into dates", () => {
    expect(periodRange({ kind: "year", year: 2026 })).toEqual({ from: "2026-01-01", to: "2026-12-31" });
    expect(periodRange({ kind: "last", months: 12 }, "2026-11-21")).toEqual({ from: "2025-12-01", to: "2026-11-21" });
    expect(periodRange({ kind: "last", months: 1 }, "2026-11-21")).toEqual({ from: "2026-11-01", to: "2026-11-21" });
    expect(periodRange({ kind: "last", months: 3 }, "2026-11-21")).toEqual({ from: "2026-09-01", to: "2026-11-21" });
    expect(periodRange({ kind: "last", months: 36 }, "2026-11-21")).toEqual({ from: "2023-12-01", to: "2026-11-21" });
    expect(periodRange({ kind: "all" })).toEqual({});
    expect(monthPeriod("2024-02")).toEqual({ kind: "custom", from: "2024-02-01", to: "2024-02-29" });
  });

  it("rolls months into years and compares", () => {
    expect(
      byYear([
        { month: "2025-12", amountMinor: 100, count: 1, investedMinor: 0 },
        { month: "2026-01", amountMinor: 200, count: 2, investedMinor: 500 },
        { month: "2026-02", amountMinor: 50, count: 1, investedMinor: -100 },
      ]),
    ).toEqual([
      { month: "2025", amountMinor: 100, count: 1, investedMinor: 0 },
      { month: "2026", amountMinor: 250, count: 3, investedMinor: 400 },
    ]);
    expect(percentChange(120, 100)).toBe(20);
    expect(percentChange(120, 0)).toBeNull();
  });
});
