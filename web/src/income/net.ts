import type { Income } from "../api/types";

type Line = Pick<Income, "amountMinor"> & { kind?: Income["kind"] };

/** What a line adds to net income: earnings count as they are, deductions (stored positive) come off. */
export const netAmount = (line: Line) => (line.kind === "deduction" ? -line.amountMinor : line.amountMinor);

/** A month's income: earned (gross), deducted (insurance advances, tax) and what's left. */
export function incomeTotals(lines: Line[]) {
  let earned = 0;
  let deducted = 0;
  for (const line of lines) {
    if (line.kind === "deduction") deducted += line.amountMinor;
    else earned += line.amountMinor;
  }
  return { earned, deducted, net: earned - deducted };
}

export type IncomeRow = {
  income: Income;
  /** Linked deductions whose earning is present in the same list, rendered indented under it. */
  linkedDeductions: Income[];
};

/**
 * Groups deductions linked to an earning in [rows] under that earning; unlinked deductions (and deductions whose
 * earning isn't in [rows]) stay in their original position.
 */
export function groupIncome(rows: Income[]): IncomeRow[] {
  const earningIds = new Set(rows.filter((r) => r.kind !== "deduction").map((r) => r.id));
  const byEarning = new Map<string, Income[]>();
  for (const r of rows) {
    if (r.kind === "deduction" && r.deductedFromId && earningIds.has(r.deductedFromId)) {
      const list = byEarning.get(r.deductedFromId) ?? [];
      list.push(r);
      byEarning.set(r.deductedFromId, list);
    }
  }
  const result: IncomeRow[] = [];
  for (const r of rows) {
    if (r.kind === "deduction" && r.deductedFromId && earningIds.has(r.deductedFromId)) continue;
    result.push({
      income: r,
      linkedDeductions: r.kind !== "deduction" ? (byEarning.get(r.id) ?? []) : [],
    });
  }
  return result;
}

/** Earnings for the "Taken off" picker: matching recipient first, then newest date. */
export function sortEarningsForPicker(rows: Income[], recipientUserId: string | null): Income[] {
  return rows
    .filter((r) => r.kind !== "deduction")
    .sort((a, b) => {
      const aMatch = (a.recipientUserId ?? null) === (recipientUserId ?? null) ? 0 : 1;
      const bMatch = (b.recipientUserId ?? null) === (recipientUserId ?? null) ? 0 : 1;
      if (aMatch !== bMatch) return aMatch - bMatch;
      return b.date.localeCompare(a.date);
    });
}

/** Live gross, deducted, net and whether net matches a bank row's expected net amount. */
export function payslipSummary(grossMinor: number | null, deductionMinors: number[], expectedNetMinor: number | null) {
  const deductedMinor = deductionMinors.reduce((sum, n) => sum + n, 0);
  const netMinor = grossMinor === null ? null : grossMinor - deductedMinor;
  const mismatch = expectedNetMinor !== null && netMinor !== null && netMinor !== expectedNetMinor;
  return { grossMinor, deductedMinor, netMinor, mismatch };
}
