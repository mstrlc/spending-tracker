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
