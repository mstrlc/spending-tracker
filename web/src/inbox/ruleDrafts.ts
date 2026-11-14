import type { BankRow, InboxRule, InboxRuleInput, RuleOutcome } from "../api/types";

/** What a row was decided to be, as far as a rule can say it: filled in from the record it became. */
export type RuleDecision =
  | { outcome: "expense"; merchant: string; categoryId: string | null; paidByUserId: string | null }
  | { outcome: "income"; source: string; recipientUserId: string | null }
  | { outcome: "deduction"; source: string; recipientUserId: string | null; monthOffset: number }
  | { outcome: "transfer"; otherAccountId: string | null }
  | { outcome: "skip"; reason: string };

/** The starting point of the rule form: any input field, the rest left to the form's defaults. */
export type RuleDraft = Partial<InboxRuleInput>;

/** A readable name for the rule: the merchant or source it files under, else what the row says. */
function nameFor(row: BankRow, decision: RuleDecision | null): string {
  const named =
    decision?.outcome === "expense" ? decision.merchant : decision?.outcome === "income" || decision?.outcome === "deduction" ? decision.source : "";
  return (named || row.text || row.counterpartyName || row.counterpartyAccount || "New rule").trim();
}

/**
 * The rule that would have filed [row] the way it was decided ([decision]; null when it can't be put as a rule, a
 * settlement say). It matches on the row's merchant key, or on the counterparty's IBAN when the descriptor says
 * nothing, or on its text; and on the direction the money went. Not auto: the person turns that on.
 */
export function draftRule(row: BankRow, decision: RuleDecision | null): RuleDraft {
  const iban = row.counterpartyIban;
  // With no descriptor the merchant key is the IBAN itself.
  const key = row.merchantKey && row.merchantKey !== iban ? row.merchantKey : null;
  const draft: RuleDraft = {
    name: nameFor(row, decision),
    merchantKey: key,
    counterpartyIban: key ? null : iban,
    textContains: key || iban ? null : row.text || row.counterpartyName || null,
    direction: row.amountMinor > 0 ? "in" : row.amountMinor < 0 ? "out" : null,
    auto: false,
  };
  if (!decision) return draft;
  const outcome: RuleOutcome = decision.outcome;
  switch (decision.outcome) {
    case "expense":
      return { ...draft, outcome, merchant: decision.merchant, categoryId: decision.categoryId, personUserId: decision.paidByUserId };
    case "income":
      return { ...draft, outcome, incomeSource: decision.source, personUserId: decision.recipientUserId };
    case "deduction":
      return { ...draft, outcome, incomeSource: decision.source, personUserId: decision.recipientUserId, monthOffset: decision.monthOffset };
    case "transfer":
      return { ...draft, outcome, otherAccountId: decision.otherAccountId };
    case "skip":
      return { ...draft, outcome, skipReason: decision.reason };
  }
}

/** How many months [earnedMonth] (YYYY-MM) is from the month of [date] (YYYY-MM-DD): -1 for the month before. */
export function monthOffsetOf(date: string, earnedMonth: string): number {
  const [y, m] = date.split("-").map(Number);
  const [ey, em] = earnedMonth.split("-").map(Number);
  return (ey - y) * 12 + (em - m);
}

/** What a deduction rule's month offset says, in a phrase: "For the previous month". */
export function monthLabel(offset: number): string {
  if (offset === 0) return "For the same month";
  if (offset === -1) return "For the previous month";
  if (offset === 1) return "For the next month";
  return offset < 0 ? `For ${-offset} months before` : `For ${offset} months after`;
}

/** The conditions a rule sets, one short phrase each, for listing it. */
export function ruleConditions(
  rule: Pick<InboxRule, "counterpartyIban" | "merchantKey" | "textContains" | "direction">,
  names: { feed?: string | null; account?: string | null } = {},
): string[] {
  return [
    rule.merchantKey && `Merchant ${rule.merchantKey}`,
    rule.textContains && `Text contains ${rule.textContains}`,
    rule.counterpartyIban && `IBAN ${rule.counterpartyIban}`,
    names.feed && `Feed ${names.feed}`,
    names.account && `Account ${names.account}`,
    rule.direction && (rule.direction === "in" ? "Money in" : "Money out"),
  ].filter((c): c is string => !!c);
}
