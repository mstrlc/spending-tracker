import { shiftMonth, todayIso } from "../lib/dates";

/**
 * What the Reports page's period picker holds: a calendar year, the last few months up to today, all time, or chosen
 * dates. "The last [months] months" starts on the 1st, so the current month (so far) and the [months] − 1 before it:
 * no bar on the chart is a part month at the start.
 */
export type Period =
  | { kind: "year"; year: number }
  | { kind: "last"; months: number }
  | { kind: "all" }
  | { kind: "custom"; from: string; to: string };

/** The rolling periods offered, by their URL value. */
export const LAST_PERIODS: { value: string; months: number; label: string }[] = [
  { value: "1m", months: 1, label: "This month" },
  { value: "3m", months: 3, label: "Last 3 months" },
  { value: "6m", months: 6, label: "Last 6 months" },
  { value: "1y", months: 12, label: "Last 12 months" },
  { value: "3y", months: 36, label: "Last 3 years" },
];

/** The URL value of a period other than a custom one, which the picker's options use too. */
export function periodValue(period: Period): string {
  switch (period.kind) {
    case "year":
      return String(period.year);
    case "last":
      return LAST_PERIODS.find((p) => p.months === period.months)?.value ?? `${period.months}m`;
    default:
      return period.kind;
  }
}

/** The period in the URL (`?period=2026|1m|3m|6m|1y|3y|all|custom&from=&to=`); the current year when missing or unreadable. */
export function readPeriod(params: URLSearchParams, today = todayIso()): Period {
  const value = params.get("period");
  if (value === "all") return { kind: value };
  const last = LAST_PERIODS.find((p) => p.value === value);
  if (last) return { kind: "last", months: last.months };
  // Links from before the periods were added.
  if (value === "12m") return { kind: "last", months: 12 };
  if (value === "custom") {
    const from = params.get("from") ?? "";
    const to = params.get("to") ?? "";
    if (isDate(from) && isDate(to) && from <= to) return { kind: "custom", from, to };
  }
  if (value && /^\d{4}$/.test(value)) return { kind: "year", year: Number(value) };
  return { kind: "year", year: Number(today.slice(0, 4)) };
}

export function writePeriod(params: URLSearchParams, period: Period) {
  params.delete("from");
  params.delete("to");
  params.set("period", periodValue(period));
  if (period.kind === "custom") {
    params.set("from", period.from);
    params.set("to", period.to);
  }
}

/** The dates to ask the server for; open ends are left out (the ledger's start, today). */
export function periodRange(period: Period, today = todayIso()): { from?: string; to?: string } {
  switch (period.kind) {
    case "year":
      return { from: `${period.year}-01-01`, to: `${period.year}-12-31` };
    case "last":
      return { from: `${shiftMonth(today.slice(0, 7), -(period.months - 1))}-01`, to: today };
    case "all":
      return {};
    case "custom":
      return { from: period.from, to: period.to };
  }
}

/** The whole calendar month [month] (YYYY-MM) as a custom period. */
export function monthPeriod(month: string): Period {
  const [y, m] = month.split("-").map(Number);
  const last = new Date(y, m, 0).getDate();
  return { kind: "custom", from: `${month}-01`, to: `${month}-${String(last).padStart(2, "0")}` };
}

const isDate = (value: string) => /^\d{4}-\d{2}-\d{2}$/.test(value);

/** Per-month totals rolled up into calendar years, for periods too long to draw month by month. */
export function byYear(months: { month: string; amountMinor: number; count: number; investedMinor: number }[]) {
  const years = new Map<string, { month: string; amountMinor: number; count: number; investedMinor: number }>();
  for (const m of months) {
    const year = m.month.slice(0, 4);
    const sum = years.get(year) ?? { month: year, amountMinor: 0, count: 0, investedMinor: 0 };
    sum.amountMinor += m.amountMinor;
    sum.count += m.count;
    sum.investedMinor += m.investedMinor;
    years.set(year, sum);
  }
  return [...years.values()];
}

/** The change from [before] to [now] as a whole percentage, or null when there was nothing before. */
export function percentChange(now: number, before: number): number | null {
  if (before <= 0) return null;
  return Math.round(((now - before) / before) * 100);
}
