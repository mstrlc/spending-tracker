import type { TableSort } from "../sorting";
import type { Account, BankRow, Category } from "../api/types";
import type { RecordKind } from "../chips";

/** What a pending row probably is, from its suggestion: the kind its chip and main button show. */
export function suggestedKind(row: BankRow): RecordKind {
  const s = row.suggestion;
  // A prepared split makes it an expense, whatever the guess was.
  if (row.draft) return row.amountMinor > 0 ? "refund" : "expense";
  if (s?.skipReason != null) return "skip";
  if (s?.record === "settlement") return "settlement";
  if (s?.record === "contribution") return "contribution";
  if (s?.record === "transfer") return "transfer";
  if (s?.record !== "income") return row.amountMinor > 0 ? "refund" : "expense";
  return s.incomeKind === "deduction" ? "deduction" : "income";
}

/** What a decided row became. */
export function decidedKind(row: BankRow): RecordKind {
  if (row.outcome === "skipped" || row.outcome === "pending") return "skip";
  if (row.outcome === "expense" && row.amountMinor > 0) return "refund";
  return row.outcome;
}

export const rowKind = (row: BankRow) => (row.outcome === "pending" ? suggestedKind(row) : decidedKind(row));

/**
 * Whose a row is: a pending expense's or income's suggested whose, otherwise its account's owner.
 * Null is joint.
 */
export function rowPerson(row: BankRow, accounts: Account[]): string | null {
  const s = row.suggestion;
  const kind = rowKind(row);
  if (row.outcome === "pending" && s && (kind === "expense" || kind === "refund" || kind === "income" || kind === "deduction")) {
    return s.paidByUserId;
  }
  return accounts.find((a) => a.id === row.accountId)?.ownerUserId ?? null;
}

/** The amount to compare rows by: the household-currency amount when the suggestion has one, else the bank's. */
const comparable = (row: BankRow) =>
  Math.sign(row.amountMinor) * Math.abs(row.suggestion?.amountMinor ?? row.amountMinor);

export type InboxSort = "newest" | "oldest" | "spent" | "received" | "largest" | "smallest" | "name" | "nameDesc";

export const SORTS: { value: InboxSort; label: string }[] = [
  { value: "newest", label: "Newest first" },
  { value: "oldest", label: "Oldest first" },
  { value: "spent", label: "Most spent first" },
  { value: "received", label: "Most received first" },
  { value: "largest", label: "Largest either way" },
  { value: "smallest", label: "Smallest either way" },
  { value: "name", label: "Name A–Z" },
  { value: "nameDesc", label: "Name Z–A" },
];

export type InboxFilters = {
  query: string;
  from: string;
  to: string;
  accountId: string;
  /** "" for anyone, "joint", or a member's user id. */
  person: string;
  kind: "" | RecordKind;
  /** "" for any, "none" for uncategorised, or a category id (its subcategories count too). */
  categoryId: string;
  direction: "" | "out" | "in";
  sort: InboxSort;
};

export const NO_FILTERS: InboxFilters = {
  query: "",
  from: "",
  to: "",
  accountId: "",
  person: "",
  kind: "",
  categoryId: "",
  direction: "",
  sort: "newest",
};

/** How many filters narrow the list (the sort doesn't count). */
export const activeFilterCount = (f: InboxFilters) =>
  (Object.keys(NO_FILTERS) as (keyof InboxFilters)[]).filter((k) => k !== "sort" && f[k] !== NO_FILTERS[k]).length;

const fold = (s: string) => s.normalize("NFD").replace(/\p{Diacritic}/gu, "").toLowerCase();

/** The rows that pass [f], in its order. Category only narrows pending rows, the only ones that carry one. */
export function filterRows(rows: BankRow[], f: InboxFilters, accounts: Account[], categories: Category[]): BankRow[] {
  const query = fold(f.query.trim());
  const categoryIds =
    f.categoryId && f.categoryId !== "none"
      ? new Set([f.categoryId, ...categories.filter((c) => c.parentId === f.categoryId).map((c) => c.id)])
      : null;

  const kept = rows.filter((row) => {
    if (f.from && row.date < f.from) return false;
    if (f.to && row.date > f.to) return false;
    if (f.accountId && row.accountId !== f.accountId) return false;
    if (f.direction === "out" && row.amountMinor >= 0) return false;
    if (f.direction === "in" && row.amountMinor <= 0) return false;
    if (f.kind && rowKind(row) !== f.kind) return false;
    if (f.person) {
      const person = rowPerson(row, accounts);
      if (f.person === "joint" ? person !== null : person !== f.person) return false;
    }
    if (f.categoryId) {
      const s = row.suggestion;
      const kind = rowKind(row);
      const categorised = row.outcome === "pending" && s && (kind === "expense" || kind === "refund");
      if (!categorised) return false;
      if (f.categoryId === "none" ? s.categoryId !== null : !categoryIds!.has(s.categoryId ?? "")) return false;
    }
    if (query) {
      const s = row.suggestion;
      const haystack = [row.text, row.counterpartyName, row.counterpartyAccount, row.message, row.hint, s?.merchant, s?.incomeSource, row.outcomeLabel, row.skipReason]
        .filter(Boolean)
        .join(" ");
      if (!fold(haystack).includes(query)) return false;
    }
    return true;
  });

  const byDate = (a: BankRow, b: BankRow) => b.date.localeCompare(a.date) || b.id.localeCompare(a.id);
  const compare: Record<InboxSort, (a: BankRow, b: BankRow) => number> = {
    newest: byDate,
    oldest: (a, b) => -byDate(a, b),
    spent: (a, b) => comparable(a) - comparable(b) || byDate(a, b),
    received: (a, b) => comparable(b) - comparable(a) || byDate(a, b),
    largest: (a, b) => Math.abs(comparable(b)) - Math.abs(comparable(a)) || byDate(a, b),
    smallest: (a, b) => Math.abs(comparable(a)) - Math.abs(comparable(b)) || byDate(a, b),
    name: (a, b) => (a.text || a.counterpartyAccount).localeCompare(b.text || b.counterpartyAccount) || byDate(a, b),
    nameDesc: (a, b) => (b.text || b.counterpartyAccount).localeCompare(a.text || a.counterpartyAccount) || byDate(a, b),
  };
  return kept.sort(compare[f.sort]);
}

/** The Inbox's sort as a table header shows it: which column, which way; null for a sort no header stands for. */
export function headerSort(sort: InboxSort): TableSort<InboxColumn> {
  const map: Partial<Record<InboxSort, TableSort<InboxColumn>>> = {
    newest: { key: "day", dir: "desc" },
    oldest: { key: "day", dir: "asc" },
    largest: { key: "amount", dir: "desc" },
    smallest: { key: "amount", dir: "asc" },
    name: { key: "name", dir: "asc" },
    nameDesc: { key: "name", dir: "desc" },
  };
  return map[sort] ?? null;
}

/** The Inbox sort a header sort stands for; the table's own order (none) is newest first. */
export function inboxSortOf(sort: TableSort<InboxColumn>): InboxSort {
  if (!sort) return "newest";
  if (sort.key === "day") return sort.dir === "desc" ? "newest" : "oldest";
  if (sort.key === "amount") return sort.dir === "desc" ? "largest" : "smallest";
  return sort.dir === "asc" ? "name" : "nameDesc";
}

export type InboxColumn = "day" | "name" | "amount";
