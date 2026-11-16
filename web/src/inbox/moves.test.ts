import { describe, expect, it } from "vitest";
import type { BankRow, BankRowSuggestion, TransferCandidate } from "../api/types";
import { emptySide, groupAlike, groupMoves, type Move } from "./moves";

const candidate = (r: BankRow): TransferCandidate => ({
  rowId: r.id,
  version: r.version,
  accountId: r.accountId,
  date: r.date,
  amountMinor: r.amountMinor,
  currency: r.currency,
  text: r.text,
});

const row = (id: string, amountMinor: number, s: Partial<BankRowSuggestion> | null, extra: Partial<BankRow> = {}): BankRow =>
  ({
    id,
    feedId: "f",
    accountId: amountMinor < 0 ? "from" : "to",
    date: "2026-11-10",
    amountMinor,
    currency: "CZK",
    text: id,
    version: 1,
    outcome: "pending",
    transferId: null,
    settlementId: null,
    draft: null,
    suggestion: s && { record: "transfer", skipReason: null, transferCandidates: [], ...s },
    ...extra,
  }) as BankRow;

const lookup = (c: TransferCandidate) => row(c.rowId, c.amountMinor, null, { accountId: c.accountId });

describe("inbox moves", () => {
  it("joins two pending rows that pair with each other into one move, out first", () => {
    const out = row("out", -300, {});
    const into = row("in", 300, {});
    out.suggestion!.transferCandidates = [candidate(into)];
    into.suggestion!.transferCandidates = [candidate(out)];
    const items = groupMoves([into, row("lunch", -150, { record: "expense" }), out], "pending", lookup);
    expect(items).toHaveLength(2);
    expect(items[0]).toMatchObject({ kind: "move", record: "transfer", out: { id: "out" }, into: { id: "in" } });
    expect(items[1]).toMatchObject({ kind: "row", row: { id: "lunch" } });
  });

  it("keeps an empty side for a transfer with no other row, and fills a filtered-out one from the candidate", () => {
    const alone = row("alone", 300, {});
    expect(groupMoves([alone], "pending", lookup)[0]).toMatchObject({ kind: "move", out: null, into: { id: "alone" } });

    const out = row("out", -300, { transferCandidates: [{ ...candidate(row("hidden", 300, null)) }] });
    expect(groupMoves([out], "pending", lookup)[0]).toMatchObject({ kind: "move", out: { id: "out" }, into: { id: "hidden" } });
  });

  it("leaves a row whose best match pairs with another row on its own", () => {
    const a = row("a", -300, {});
    const b = row("b", -300, {});
    const into = row("in", 300, {});
    a.suggestion!.transferCandidates = [candidate(into)];
    b.suggestion!.transferCandidates = [candidate(into)];
    into.suggestion!.transferCandidates = [candidate(b)];
    const items = groupMoves([a, b, into], "pending", lookup);
    expect(items.map((i) => (i.kind === "row" ? i.row.id : i.rows.map((r) => r.id).join("+")))).toEqual(["a", "b+in"]);
  });

  it("joins decided rows filed into one transfer", () => {
    const out = row("out", -300, null, { outcome: "transfer", transferId: "t" });
    const into = row("in", 300, null, { outcome: "transfer", transferId: "t" });
    const other = row("x", -100, null, { outcome: "transfer", transferId: "u" });
    const items = groupMoves([out, other, into], "decided", lookup);
    expect(items).toHaveLength(2);
    expect(items[0]).toMatchObject({ kind: "move", out: { id: "out" }, into: { id: "in" } });
  });

  it("says why a side is empty: waiting for a read account, late after a week, an unread account, or unknown", () => {
    const r = row("r", 300, null, { date: "2026-11-15" });
    const read = new Set(["csob"]);
    expect(emptySide(r, "csob", read, "2026-11-16")).toEqual({ why: "waiting", accountId: "csob" });
    expect(emptySide(r, "csob", read, "2026-11-30")).toEqual({ why: "late", accountId: "csob" });
    expect(emptySide(r, "savings", read, "2026-11-16")).toEqual({ why: "unread", accountId: "savings" });
    expect(emptySide(r, null, read, "2026-11-16")).toEqual({ why: "unknown" });
  });

  it("groups moves alike in all but the amount, where the first of them was", () => {
    const top = (id: string, amountMinor: number, text = "Top-Up by *7998") => row(id, amountMinor, {}, { text });
    const items = groupMoves([top("a", 300), row("lunch", -150, { record: "expense" }), top("b", 300), top("c", 500, "Other")], "pending", lookup);
    const grouped = groupAlike(items, (m: Move) => (m.into ?? m.out)!.text);
    expect(grouped.map((i) => (i.kind === "group" ? i.rows.map((r) => r.id).join("+") : i.kind === "row" ? i.row.id : i.rows[0].id))).toEqual([
      "a+b",
      "lunch",
      "c",
    ]);
    expect(groupAlike(items, () => null)).toHaveLength(4);
  });
});
