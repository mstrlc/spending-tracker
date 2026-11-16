import type { Contribution, Fee, HistoryPoint, Asset, AssetKind, Valuation } from "../api/types";
import { formatAmount } from "../lib/money";

/** Asset kinds; [market] ones can follow a listed security, [liability] ones are what's owed, [trackable] ones can follow an account's bank feed balances. */
export const KINDS: { value: AssetKind; label: string; market: boolean; liability: boolean; trackable: boolean }[] = [
  { value: "fund", label: "Fund", market: true, liability: false, trackable: false },
  { value: "stock", label: "Stock", market: true, liability: false, trackable: false },
  { value: "bond", label: "Bond", market: true, liability: false, trackable: false },
  { value: "crypto", label: "Crypto", market: true, liability: false, trackable: false },
  { value: "savings", label: "Savings", market: false, liability: false, trackable: true },
  { value: "scheme", label: "Savings scheme", market: false, liability: false, trackable: true },
  { value: "receivable", label: "Owed to us", market: false, liability: false, trackable: false },
  { value: "vehicle", label: "Vehicle", market: false, liability: false, trackable: false },
  { value: "property", label: "Property", market: false, liability: false, trackable: false },
  { value: "other", label: "Other", market: false, liability: false, trackable: true },
  { value: "loan", label: "Loan", market: false, liability: true, trackable: true },
  { value: "card_debt", label: "Credit card debt", market: false, liability: true, trackable: true },
  { value: "payable", label: "Owed by us", market: false, liability: true, trackable: true },
];

export const isMarketKind = (kind: AssetKind) => KINDS.find((k) => k.value === kind)?.market ?? false;

export const isLiabilityKind = (kind: AssetKind) => KINDS.find((k) => k.value === kind)?.liability ?? false;

export const isTrackableKind = (kind: AssetKind) => KINDS.find((k) => k.value === kind)?.trackable ?? false;

export const kindLabel = (kind: AssetKind) => KINDS.find((k) => k.value === kind)?.label ?? kind;

export type Totals = { contributedMinor: number; valueMinor: number; gainMinor: number; feesMinor: number };

/** Owned holdings added up: what went in, what they're worth and the gain. */
export function portfolioTotals(assets: Asset[]): Totals {
  return assets
    .filter((i) => !isLiabilityKind(i.kind))
    .reduce(
      (sum, i) => ({
        contributedMinor: sum.contributedMinor + i.contributedMinor,
        valueMinor: sum.valueMinor + i.valueMinor,
        gainMinor: sum.gainMinor + (i.gainMinor ?? 0),
        feesMinor: sum.feesMinor + i.feesMinor,
      }),
      { contributedMinor: 0, valueMinor: 0, gainMinor: 0, feesMinor: 0 },
    );
}

/** Gain as a share of what went in, or null when nothing (or less than nothing) is in. */
export function gainRatio({ contributedMinor, gainMinor }: { contributedMinor: number; gainMinor: number | null }): number | null {
  return contributedMinor > 0 && gainMinor !== null ? gainMinor / contributedMinor : null;
}

/** "+8.6%", "−2.0%", "0.0%". */
export function formatPercent(ratio: number): string {
  const value = Math.abs(ratio * 100).toFixed(1);
  if (value === "0.0") return "0.0%";
  return `${ratio > 0 ? "+" : "−"}${value}%`;
}

/**
 * An amount on the Assets page: crowns are rounded to whole CZK ("208,794"), since haléře say nothing at
 * this scale; other currencies keep their minor units.
 */
export function formatAsset(amountMinor: number, currency: string): string {
  if (currency !== "CZK") return formatAmount(amountMinor, currency);
  return new Intl.NumberFormat(undefined, { maximumFractionDigits: 0 }).format(Math.round(amountMinor / 100));
}

/** An amount with its sign spelled out, for gains: "+1,200", "−50". */
export function formatSigned(amountMinor: number, currency: string): string {
  const whole = formatAsset(Math.abs(amountMinor), currency);
  // A gain that rounds to nothing gets no sign.
  const sign = /[1-9]/.test(whole) ? (amountMinor > 0 ? "+" : amountMinor < 0 ? "−" : "") : "";
  return sign + whole;
}

export type HistoryEntry =
  | { type: "contribution"; date: string; contribution: Contribution }
  | { type: "valuation"; date: string; valuation: Valuation }
  | { type: "fee"; date: string; fee: Fee };

/**
 * Contributions and valuations as one timeline, newest first. On a shared day the valuation goes first (above),
 * since it already includes that day's contribution.
 */
export function historyEntries(contributions: Contribution[], valuations: Valuation[], fees: Fee[] = []): HistoryEntry[] {
  const entries: HistoryEntry[] = [
    ...contributions.map((c) => ({ type: "contribution" as const, date: c.date, contribution: c })),
    ...valuations.map((v) => ({ type: "valuation" as const, date: v.date, valuation: v })),
    ...fees.map((f) => ({ type: "fee" as const, date: f.date, fee: f })),
  ];
  const order = { valuation: 0, contribution: 1, fee: 2 };
  return entries.sort((a, b) => b.date.localeCompare(a.date) || order[a.type] - order[b.type]);
}

export type FeeYear = {
  year: string;
  feesMinor: number;
  /** The average month-end worth over the year's months on record, or null with none. */
  averageWorthMinor: number | null;
  /** Fees as a share of that worth, scaled to a full year; null without worth or fees to go on. */
  yearlyRate: number | null;
};

const mean = (values: number[]) => (values.length ? values.reduce((a, b) => a + b, 0) / values.length : null);

/** Month-end worth from the first month fees were charged: earlier months had none to measure against. */
const chargedPoints = (fees: Fee[], points: HistoryPoint[]) => {
  const first = fees.map((f) => f.date.slice(0, 7)).sort()[0];
  return first === undefined ? [] : points.filter((p) => p.date.slice(0, 7) >= first);
};

/**
 * Fees per calendar year, newest first, against the average worth that year (from the first month with fees).
 * A part year is scaled up by the months it charged fees for, so a year that started in September compares with a
 * full one.
 */
export function feesByYear(fees: Fee[], allPoints: HistoryPoint[]): FeeYear[] {
  const points = chargedPoints(fees, allPoints);
  const years = [...new Set(fees.map((f) => f.date.slice(0, 4)))].sort().reverse();
  return years.map((year) => {
    const inYear = fees.filter((f) => f.date.startsWith(year));
    const feesMinor = inYear.reduce((sum, f) => sum + f.amountMinor, 0);
    const averageWorthMinor = mean(points.filter((p) => p.date.startsWith(year)).map((p) => p.valueMinor));
    const months = Math.min(12, new Set(inYear.map((f) => f.date.slice(0, 7))).size);
    const yearlyRate = averageWorthMinor ? (feesMinor / averageWorthMinor) * (12 / months) : null;
    return { year, feesMinor, averageWorthMinor, yearlyRate };
  });
}

export type FeeSummary = {
  feesMinor: number;
  /** Share of the money put in. */
  ofContributed: number | null;
  /** Share of what the holding would have gained without fees (gain + fees); null when that isn't positive. */
  ofGainBeforeFees: number | null;
  /** Fees over all months on record as a share of the average worth, per year. */
  yearlyRate: number | null;
};

export function feeSummary(fees: Fee[], points: HistoryPoint[], holding: Pick<Asset, "contributedMinor" | "gainMinor">): FeeSummary {
  const feesMinor = fees.reduce((sum, f) => sum + f.amountMinor, 0);
  const average = mean(chargedPoints(fees, points).map((p) => p.valueMinor));
  const months = new Set(fees.map((f) => f.date.slice(0, 7))).size;
  const beforeFees = holding.gainMinor === null ? null : holding.gainMinor + feesMinor;
  return {
    feesMinor,
    ofContributed: holding.contributedMinor > 0 ? feesMinor / holding.contributedMinor : null,
    ofGainBeforeFees: beforeFees !== null && beforeFees > 0 ? feesMinor / beforeFees : null,
    yearlyRate: average && months ? (feesMinor / average) * (12 / months) : null,
  };
}
