import type { BankRow, TransferCandidate } from "../api/types";
import { suggestedKind } from "./filters";

/**
 * Money that moved between two accounts, shown as one inbox row with its two sides: [out] the money leaving one account,
 * [into] it arriving in the other. A side is null when the app has no row for it (yet). [rows] are the bank rows the
 * item stands for, one or two.
 */
export type Move = {
  kind: "move";
  record: "transfer" | "settlement";
  out: BankRow | null;
  into: BankRow | null;
  rows: BankRow[];
};

export type InboxItem = { kind: "row"; row: BankRow } | Move;

const sides = (a: BankRow, b: BankRow | null) => {
  const [out, into] = a.amountMinor < 0 ? [a, b] : [b, a];
  return { out, into };
};

/**
 * The inbox's [rows] with each move of money between accounts as one item. A pending transfer or settlement joins the
 * row its suggestion pairs it with when that row pairs back (the server pairs one to one, so it does), or when that
 * row is filtered out ([lookup] makes it from the candidate); without one it's a move with an empty side. A decided
 * transfer or settlement joins the other row filed into the same record. Everything else stays a row of its own, in
 * the order of [rows].
 */
export function groupMoves(rows: BankRow[], tab: "pending" | "decided", lookup: (c: TransferCandidate) => BankRow): InboxItem[] {
  const byId = new Map(rows.map((r) => [r.id, r]));
  const taken = new Set<string>();
  const items: InboxItem[] = [];
  for (const row of rows) {
    if (taken.has(row.id)) continue;
    taken.add(row.id);
    if (tab === "pending") {
      const kind = row.draft ? null : suggestedKind(row);
      if (kind !== "transfer" && kind !== "settlement") {
        items.push({ kind: "row", row });
        continue;
      }
      const best = row.suggestion?.transferCandidates[0];
      if (!best) {
        items.push({ kind: "move", record: kind, ...sides(row, null), rows: [row] });
        continue;
      }
      const shown = byId.get(best.rowId);
      const mutual = !shown || shown.suggestion?.transferCandidates[0]?.rowId === row.id;
      if (!mutual || taken.has(best.rowId)) {
        items.push({ kind: "row", row });
        continue;
      }
      const other = shown ?? lookup(best);
      taken.add(other.id);
      items.push({ kind: "move", record: kind, ...sides(row, other), rows: [row, other] });
    } else {
      const recordId = row.transferId ?? row.settlementId;
      const other = recordId ? rows.find((r) => r.id !== row.id && (r.transferId ?? r.settlementId) === recordId) : undefined;
      if (!recordId || !other || other.amountMinor * row.amountMinor >= 0) {
        items.push({ kind: "row", row });
        continue;
      }
      taken.add(other.id);
      items.push({ kind: "move", record: row.transferId ? "transfer" : "settlement", ...sides(row, other), rows: [row, other] });
    }
  }
  return items;
}

/** Why a move's side is empty, which decides what to do with it. */
export type EmptySide =
  /** The other account is read by the app but nothing came yet: wait, then pair. */
  | { why: "waiting"; accountId: string }
  /** A week went by and nothing came: maybe nothing will. */
  | { why: "late"; accountId: string }
  /** The other account isn't read by the app: only this side will ever arrive. */
  | { why: "unread"; accountId: string }
  /** Nothing says where the money went. */
  | { why: "unknown" };

/** How many days a side the app reads may take to arrive before the move asks instead of waiting. */
export const WAIT_DAYS = 7;

/**
 * Why [move]'s other side is empty: [otherAccountId] is where its suggestion says the money went or came from, and
 * [readAccountIds] the accounts with a bank feed.
 */
export function emptySide(row: BankRow, otherAccountId: string | null, readAccountIds: Set<string>, today: string): EmptySide {
  if (!otherAccountId) return { why: "unknown" };
  if (!readAccountIds.has(otherAccountId)) return { why: "unread", accountId: otherAccountId };
  const days = (Date.parse(today) - Date.parse(row.date)) / 86_400_000;
  return { why: days > WAIT_DAYS ? "late" : "waiting", accountId: otherAccountId };
}

/** Moves alike in everything but their amounts, shown as one row: ten top-ups of 300 from one card on one day. */
export type MoveGroup = { kind: "group"; key: string; moves: Move[]; rows: BankRow[] };

/**
 * [items] with every set of two or more moves that share a key ([keyOf]; null never groups) as one group, at the
 * place of its first move. Rows and lone moves stay as they are.
 */
export function groupAlike(items: InboxItem[], keyOf: (m: Move) => string | null): (InboxItem | MoveGroup)[] {
  const byKey = new Map<string, Move[]>();
  for (const item of items) {
    if (item.kind !== "move") continue;
    const key = keyOf(item);
    if (key !== null) byKey.set(key, [...(byKey.get(key) ?? []), item]);
  }
  const placed = new Set<string>();
  const out: (InboxItem | MoveGroup)[] = [];
  for (const item of items) {
    const key = item.kind === "move" ? keyOf(item) : null;
    const alike = key !== null ? byKey.get(key)! : [];
    if (key === null || alike.length < 2) {
      out.push(item);
    } else if (!placed.has(key)) {
      placed.add(key);
      out.push({ kind: "group", key, moves: alike, rows: alike.flatMap((m) => m.rows) });
    }
  }
  return out;
}
