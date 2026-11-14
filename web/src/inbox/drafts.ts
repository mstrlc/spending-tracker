import type { BankRow } from "../api/types";
import type { ExpenseDraft } from "../expenses/ExpenseForm";
import type { IncomeDraft } from "../income/IncomeForm";

/** A row in the household currency, money in positive; null without an exchange rate for its day. */
function signedAmount(row: BankRow): number | null {
  const s = row.suggestion;
  if (!s || s.amountMinor == null) return null;
  return s.record === "income" ? s.amountMinor : -s.amountMinor;
}

function sum(values: (number | null)[]): number | undefined {
  return values.some((v) => v === null) ? undefined : values.reduce<number>((a, v) => a + (v ?? 0), 0);
}

const earliest = (rows: BankRow[]) => rows.map((r) => r.date).sort()[0];
const sameAccount = (rows: BankRow[]) => (rows.every((r) => r.accountId === rows[0].accountId) ? rows[0].accountId : undefined);

/**
 * The expense [rows] become together: their amounts added up (money out positive), dated by the earliest, filled in
 * from the first row's suggestion. Rows in one foreign currency keep what they cost in it.
 */
export function draftExpense(rows: BankRow[]): ExpenseDraft {
  const total = sum(rows.map(signedAmount));
  const s = rows.find((r) => r.suggestion)?.suggestion;
  const currency = rows[0].currency;
  const foreign = s?.originalCurrency != null && rows.every((r) => r.currency === currency);
  return {
    date: earliest(rows),
    amountMinor: total === undefined ? undefined : -total,
    merchant: s?.merchant,
    categoryId: s?.categoryId ?? undefined,
    accountId: sameAccount(rows),
    paidByUserId: s ? s.paidByUserId : undefined,
    originalAmountMinor: foreign ? -rows.reduce((a, r) => a + r.amountMinor, 0) : undefined,
    originalCurrency: foreign ? currency : undefined,
  };
}

/**
 * The income line [rows] become together: their amounts added up, from the first row's payer, for the account's owner
 * (or whoever a matching rule names).
 */
export function draftIncome(rows: BankRow[], accountOwner: string | null): IncomeDraft {
  const s = rows.find((r) => r.suggestion)?.suggestion;
  return {
    date: earliest(rows),
    amountMinor: sum(rows.map(signedAmount)),
    source: s?.incomeSource,
    accountId: sameAccount(rows),
    recipientUserId: s?.ruleId ? s.paidByUserId : accountOwner,
  };
}

/** What skipping [rows] is probably about: a matching rule's reason, else the first row's hint, without saying it came from the old app. */
export function skipReason(rows: BankRow[]): string {
  const ruled = rows.find((r) => r.suggestion?.skipReason)?.suggestion?.skipReason;
  if (ruled) return ruled;
  const hint = rows.find((r) => r.hint)?.hint ?? "";
  return hint.replace(/^Old app: /, "").replace(/^Money in$/, "");
}

/** [rows] as the two sides of a move of money (one out, one in), or null when they aren't exactly that. */
export function oppositeSides(rows: BankRow[]): { out: BankRow; in: BankRow } | null {
  if (rows.length !== 2) return null;
  const out = rows.find((r) => r.amountMinor < 0);
  const incoming = rows.find((r) => r.amountMinor > 0);
  return out && incoming ? { out, in: incoming } : null;
}

const plain = (text: string) =>
  text
    .normalize("NFD")
    .replace(/\p{M}/gu, "")
    .toLowerCase();

/**
 * The one member whose name appears in [text] (a counterparty name, which banks often write surname first), by full
 * name or by any word of three letters or more; null when none or several match. [skip] is left out.
 */
export function memberInText(text: string, members: { userId: string; displayName: string }[], skip?: string | null): string | null {
  const haystack = plain(text);
  if (!haystack.trim()) return null;
  const words = new Set(haystack.split(/[^\p{L}\p{N}]+/u));
  const found = members.filter((m) => {
    if (m.userId === skip) return false;
    const name = plain(m.displayName).trim();
    return name !== "" && (haystack.includes(name) || name.split(/\s+/).some((w) => w.length >= 3 && words.has(w)));
  });
  return found.length === 1 ? found[0].userId : null;
}

export type SettleDraft = {
  fromUserId: string | null;
  toUserId: string | null;
  /** Household currency, from the outgoing row's suggestion (else the incoming one's); null without an exchange rate. */
  amountMinor: number | null;
};

/**
 * Who paid whom for the settlement [rows] become: the payer is the owner of the outgoing row's account, the
 * receiver the owner of the incoming row's. A side with no row, or a joint account, is the member named by the
 * row's counterparty.
 */
export function draftSettle(
  rows: BankRow[],
  ownerOf: (accountId: string) => string | null,
  members: { userId: string; displayName: string }[],
): SettleDraft {
  const out = rows.find((r) => r.amountMinor < 0);
  const incoming = rows.find((r) => r.amountMinor > 0);
  const named = (row: BankRow | undefined, skip: string | null) =>
    row ? memberInText(`${row.counterpartyName} ${row.text}`, members, skip) : null;
  const fromUserId = (out ? ownerOf(out.accountId) : null) ?? null;
  const toUserId = (incoming ? ownerOf(incoming.accountId) : null) ?? null;
  const from = fromUserId ?? (incoming ? named(incoming, toUserId) : null);
  const to = toUserId ?? (out ? named(out, from) : null);
  const priced = out?.suggestion?.amountMinor ?? incoming?.suggestion?.amountMinor ?? null;
  return { fromUserId: from, toUserId: to, amountMinor: priced };
}
