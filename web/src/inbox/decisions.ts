import { api } from "../api/client";
import { unwrap } from "../api/errors";
import type { BankRow } from "../api/types";
import { shiftDays } from "../lib/dates";
import { monthOffsetOf, type RuleDecision } from "./ruleDrafts";

/** How far from the bank's date a record made or linked from a row may be dated (an income line dated the 1st can be weeks off). */
const WINDOW_DAYS = 60;

/**
 * What the decided [row] became, read from its expense, income line, transfer or skip reason, as a rule would file it;
 * null for a settlement (a rule can't make one) or when the record isn't to be found.
 */
export async function loadDecision(householdId: string, row: BankRow): Promise<RuleDecision | null> {
  const path = { householdId };
  const query = { from: shiftDays(row.date, -WINDOW_DAYS), to: shiftDays(row.date, WINDOW_DAYS) };
  switch (row.outcome) {
    case "expense": {
      const expenses = unwrap(await api.GET("/api/households/{householdId}/expenses", { params: { path, query } }));
      const e = expenses.find((x) => x.id === row.expenseId);
      return e ? { outcome: "expense", merchant: e.merchant, categoryId: e.categoryId, paidByUserId: e.paidByUserId } : null;
    }
    case "income": {
      const income = unwrap(await api.GET("/api/households/{householdId}/income", { params: { path, query } }));
      const i = income.find((x) => x.id === row.incomeId);
      if (!i) return null;
      // A deduction is for a month of its own: the rule keeps how far that is from the row's.
      return i.kind === "deduction"
        ? { outcome: "deduction", source: i.source, recipientUserId: i.recipientUserId, monthOffset: monthOffsetOf(row.date, i.earnedMonth) }
        : { outcome: "income", source: i.source, recipientUserId: i.recipientUserId };
    }
    case "transfer": {
      const transfers = unwrap(await api.GET("/api/households/{householdId}/transfers", { params: { path, query } }));
      const t = transfers.find((x) => x.id === row.transferId);
      if (!t) return null;
      const other = t.fromAccountId === row.accountId ? t.toAccountId : t.fromAccountId;
      return { outcome: "transfer", otherAccountId: other === row.accountId ? null : other };
    }
    case "skipped":
      return { outcome: "skip", reason: row.skipReason ?? "" };
    default:
      return null;
  }
}
