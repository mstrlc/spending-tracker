import type { Contribution, Investment, InvestmentKind, Valuation } from "../api/types";
import { formatAmount } from "../lib/money";

export const KINDS: { value: InvestmentKind; label: string }[] = [
  { value: "fund", label: "Fund" },
  { value: "stock", label: "Stock" },
  { value: "bond", label: "Bond" },
  { value: "crypto", label: "Crypto" },
  { value: "other", label: "Other" },
];

export const kindLabel = (kind: InvestmentKind) => KINDS.find((k) => k.value === kind)?.label ?? kind;

export type Totals = { contributedMinor: number; valueMinor: number; gainMinor: number };

/** The holdings added up: what went in, what it's worth and the gain. */
export function portfolioTotals(investments: Investment[]): Totals {
  return investments.reduce(
    (sum, i) => ({
      contributedMinor: sum.contributedMinor + i.contributedMinor,
      valueMinor: sum.valueMinor + i.valueMinor,
      gainMinor: sum.gainMinor + i.gainMinor,
    }),
    { contributedMinor: 0, valueMinor: 0, gainMinor: 0 },
  );
}

/** Gain as a share of what went in, or null when nothing (or less than nothing) is in. */
export function gainRatio({ contributedMinor, gainMinor }: Pick<Totals, "contributedMinor" | "gainMinor">): number | null {
  return contributedMinor > 0 ? gainMinor / contributedMinor : null;
}

/** "+8.6%", "−2.0%", "0.0%". */
export function formatPercent(ratio: number): string {
  const value = Math.abs(ratio * 100).toFixed(1);
  if (value === "0.0") return "0.0%";
  return `${ratio > 0 ? "+" : "−"}${value}%`;
}

/** An amount with its sign spelled out, for gains: "+1,200.00", "−50.00". */
export function formatSigned(amountMinor: number, currency: string): string {
  const sign = amountMinor > 0 ? "+" : amountMinor < 0 ? "−" : "";
  return sign + formatAmount(Math.abs(amountMinor), currency);
}

export type HistoryEntry =
  | { type: "contribution"; date: string; contribution: Contribution }
  | { type: "valuation"; date: string; valuation: Valuation };

/**
 * Contributions and valuations as one timeline, newest first. On a shared day the valuation goes first (above),
 * since it already includes that day's contribution.
 */
export function historyEntries(contributions: Contribution[], valuations: Valuation[]): HistoryEntry[] {
  const entries: HistoryEntry[] = [
    ...contributions.map((c) => ({ type: "contribution" as const, date: c.date, contribution: c })),
    ...valuations.map((v) => ({ type: "valuation" as const, date: v.date, valuation: v })),
  ];
  return entries.sort((a, b) => b.date.localeCompare(a.date) || (a.type === b.type ? 0 : a.type === "valuation" ? -1 : 1));
}
