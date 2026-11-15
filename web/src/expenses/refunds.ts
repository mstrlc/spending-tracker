import type { Expense, ExpenseLine } from "../api/types";

/**
 * [amountMinor] shared out over [weights] in whole minor units that add up exactly: each gets its rounded-down part
 * and the units left over go to the largest remainders (ties to the earlier one). The server's `spreadByWeight` does
 * the same, so a refund is spread over its original's lines the same way in reports and in the form.
 */
export function spreadByWeight(amountMinor: number, weights: number[]): number[] {
  if (weights.length === 0) return [];
  const sizes = weights.map(Math.abs);
  const total = sizes.reduce((a, b) => a + b, 0);
  if (total === 0) return weights.map((_, i) => (i === 0 ? amountMinor : 0));
  const sign = amountMinor < 0 ? -1 : 1;
  const amount = Math.abs(amountMinor);
  const parts = sizes.map((w) => Math.floor((amount * w) / total));
  let left = amount - parts.reduce((a, b) => a + b, 0);
  const byRemainder = sizes.map((w, i) => ({ i, rest: (amount * w) % total })).sort((a, b) => b.rest - a.rest || a.i - b.i);
  for (const { i } of byRemainder) {
    if (left === 0) break;
    parts[i] += 1;
    left--;
  }
  return parts.map((p) => (p === 0 ? 0 : p * sign));
}

/** One slice of spending as reports count it: a category (null for none) and an amount. */
export type SpendingPart = { categoryId: string | null; amountMinor: number };

/**
 * What [expense] contributes to the month's spending by category, or nothing for a refund: it nets off its original,
 * which carries `refundedMinor` (positive), so a refund counts in its original's month and category wherever it is
 * dated. A split original's refunds are spread over its lines in proportion. Lists still show the refund on its own date.
 */
export function spendingParts(expense: Pick<Expense, "amountMinor" | "categoryId" | "lines" | "refundOfId" | "refundedMinor">): SpendingPart[] {
  if (expense.refundOfId) return [];
  const parts: SpendingPart[] =
    expense.lines && expense.lines.length > 0
      ? expense.lines.map((l) => ({ categoryId: l.categoryId, amountMinor: l.amountMinor }))
      : [{ categoryId: expense.categoryId, amountMinor: expense.amountMinor }];
  const refunded = expense.refundedMinor ?? 0;
  if (!refunded) return parts;
  const back = spreadByWeight(-refunded, parts.map((p) => p.amountMinor));
  return parts.map((p, i) => ({ ...p, amountMinor: p.amountMinor + back[i] }));
}

/** [lines] of an original refunded by [refundMinor] (negative): the same categories and whose, the refund spread in proportion. */
export function refundLines(
  lines: ExpenseLine[],
  refundMinor: number,
): { categoryId: string | null; amountMinor: number; notes: string; paidByUserId: string | null }[] {
  const amounts = spreadByWeight(refundMinor, lines.map((l) => l.amountMinor));
  return lines.map((l, i) => ({ categoryId: l.categoryId, amountMinor: amounts[i], notes: "", paidByUserId: l.paidByUserId }));
}

/**
 * The expenses a refund may be of: positive, not refunds themselves, newest first, at most [limit]; the one
 * already chosen ([keep]) stays in the list wherever it falls.
 */
export function refundOriginals(expenses: Expense[], keep: string | null, limit = 30): Expense[] {
  const open = expenses.filter((e) => e.amountMinor > 0 && !e.refundOfId).sort((a, b) => b.date.localeCompare(a.date));
  const head = open.slice(0, limit);
  const chosen = keep && !head.some((e) => e.id === keep) ? open.find((e) => e.id === keep) : undefined;
  return chosen ? [...head, chosen] : head;
}
