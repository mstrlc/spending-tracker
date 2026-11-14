#!/usr/bin/env node
// Compares what the inbox matcher would file on its own (the strict thresholds of slice 3: transfer pairs and
// duplicates of hand-entered expenses) with what the old app's bank-sync decided, using the old app's data in
// data/legacy/. Run it before turning a household's auto_match on.
//
//   node scripts/legacy-matcher-report.mjs [data/legacy/d1.sqlite]
//
// It prints counts only: never a name, amount, IBAN, text or id of a row, so the output is safe to paste. The old
// automatic decisions are hints, not facts (see ARCHITECTURE.md): "disagree" and "old-only" don't say the old app was
// right, only where a person should look first.
//
// The thresholds mirror server/.../inbox/AutoFiling.kt:
//   transfer pair: two rows of different feeds, one currency, exactly opposite amounts, at most 2 days apart, each
//     the only candidate of the other.
//   duplicate: a money-out row in the household currency (CZK) and an expense entered by hand (not made from a bank
//     row) on the row's account, the exact amount, at most 1 day apart, each the only candidate of the other.
// The matcher runs on rows nobody has decided; here it runs over every row of the imported feeds, as if none were.
import { DatabaseSync } from "node:sqlite";

const [source = "data/legacy/d1.sqlite"] = process.argv.slice(2);
const db = new DatabaseSync(source, { readOnly: true });
const all = (sql) => db.prepare(sql).all();

const PAIR_DAYS = 2;
const DUPLICATE_DAYS = 1;
const HOUSEHOLD_CURRENCY = "CZK";
const AUTOMATIC = new Set(["", "bank-sync"]);

const days = (a, b) => Math.abs(Date.parse(`${a}T00:00:00Z`) - Date.parse(`${b}T00:00:00Z`)) / 86_400_000;

const rows = all(`
  SELECT t.id, t.account_id AS feed, t.date, t.amount_halere, t.currency, t.native_minor, t.kind, t.status,
         t.decided_by, t.spending_id, b.ledger_account
  FROM bank_transactions t JOIN bank_accounts b ON b.id = t.account_id
  WHERE b.mode = 'import'`);
const byId = new Map(rows.map((r) => [r.id, r]));
/** The amount in the row's own currency, money out negative. */
const native = (r) => (r.currency === HOUSEHOLD_CURRENCY ? r.amount_halere : (r.native_minor ?? null));

const tally = () => ({ agree: 0, disagree: 0, matcherOnly: 0, oldOnly: 0 });
const bump = (map, key) => map.set(key, (map.get(key) ?? 0) + 1);
const lines = [];
const out = (text = "") => lines.push(text);
const breakdown = (map) => (map.size === 0 ? "" : ` (${[...map].sort().map(([k, n]) => `${k}: ${n}`).join(", ")})`);

// ---- Transfer pairs ----

const oldLinks = all("SELECT out_tx, in_tx, decided_by FROM transfer_links WHERE out_tx IS NOT NULL AND in_tx IS NOT NULL");
const oldAutomatic = oldLinks.filter((l) => AUTOMATIC.has(l.decided_by));
const pairKey = (a, b) => (a < b ? `${a}:${b}` : `${b}:${a}`);
const oldPairs = new Map(oldAutomatic.map((l) => [pairKey(l.out_tx, l.in_tx), l]));
const oldPartner = new Map(oldAutomatic.flatMap((l) => [[l.out_tx, l.in_tx], [l.in_tx, l.out_tx]]));

function partners(row, pool) {
  const amount = native(row);
  if (amount === null || amount === 0) return [];
  return pool.filter(
    (o) => o.id !== row.id && o.feed !== row.feed && o.currency === row.currency && native(o) === -amount && days(o.date, row.date) <= PAIR_DAYS,
  );
}

const matcherPairs = new Map();
for (const row of rows) {
  const mine = partners(row, rows);
  if (mine.length !== 1) continue;
  const other = mine[0];
  const back = partners(other, rows);
  if (back.length === 1 && back[0].id === row.id) matcherPairs.set(pairKey(row.id, other.id), [row, other]);
}
const consumed = new Set([...matcherPairs.values()].flat().map((r) => r.id));

const pairs = tally();
const pairMatcherOnly = new Map();
for (const [key, [a, b]] of matcherPairs) {
  if (oldPairs.has(key)) pairs.agree++;
  else if (oldPartner.has(a.id) || oldPartner.has(b.id)) pairs.disagree++;
  else {
    pairs.matcherOnly++;
    const both = (f) => f(a) && f(b);
    bump(
      pairMatcherOnly,
      both((r) => r.kind === "internal") ? "old: both rows skipped as internal" : both((r) => r.status === "pending") ? "old: both still pending" : "old: decided otherwise",
    );
  }
}
const pairOldOnly = new Map();
for (const [key, link] of oldPairs) {
  if (matcherPairs.has(key)) continue;
  pairs.oldOnly++;
  const a = byId.get(link.out_tx);
  const b = byId.get(link.in_tx);
  let why;
  if (!a || !b) why = "a row is in a feed that wasn't imported";
  else if (a.currency !== b.currency) why = "different currencies";
  else if (native(a) === null || native(a) !== -native(b)) why = "amounts differ";
  else if (days(a.date, b.date) > PAIR_DAYS) why = `more than ${PAIR_DAYS} days apart`;
  else if (a.feed === b.feed) why = "same feed";
  else why = "another candidate on one side";
  bump(pairOldOnly, why);
}
out("Transfer pairs (rows in imported feeds: " + rows.length + ")");
out(`  old app, filed by bank-sync: ${oldPairs.size} pairs (${oldLinks.length - oldAutomatic.length} more decided by a person, left out)`);
out(`  matcher: ${matcherPairs.size} pairs`);
out(`  agree (same pair): ${pairs.agree}`);
out(`  disagree (a row paired with another row): ${pairs.disagree}`);
out(`  matcher-only (neither row paired by the old app): ${pairs.matcherOnly}${breakdown(pairMatcherOnly)}`);
out(`  old-only (the matcher would not file it): ${pairs.oldOnly}${breakdown(pairOldOnly)}`);

// ---- Duplicates of hand-entered expenses ----

const oldDuplicates = rows.filter((r) => r.kind === "duplicate" && r.status === "skipped" && AUTOMATIC.has(r.decided_by) && r.spending_id != null);
// Expenses made from a bank row aren't hand-entered; the duplicates' own links (skipped rows) don't count as that.
const madeFromRows = new Set(all("SELECT spending_id FROM bank_transactions WHERE status = 'accepted' AND spending_id IS NOT NULL").map((r) => r.spending_id));
const handEntered = all("SELECT id, date, amount_halere, account FROM spending WHERE category <> 'Investments' AND account <> ''").filter(
  (s) => !madeFromRows.has(s.id),
);
const candidatesFor = (row, spendings) =>
  spendings.filter((s) => s.account === row.ledger_account && s.amount_halere === -row.amount_halere && days(s.date, row.date) <= DUPLICATE_DAYS);

const eligible = rows.filter((r) => !consumed.has(r.id) && r.currency === HOUSEHOLD_CURRENCY && r.amount_halere < 0);
const matcherLinks = new Map();
for (const row of eligible) {
  const mine = candidatesFor(row, handEntered);
  if (mine.length !== 1) continue;
  const rivals = eligible.filter((o) => candidatesFor(o, [mine[0]]).length === 1);
  if (rivals.length === 1) matcherLinks.set(row.id, mine[0]);
}
const dups = tally();
const dupMatcherOnly = new Map();
const oldDuplicateOf = new Map(oldDuplicates.map((r) => [r.id, r.spending_id]));
for (const [rowId, spending] of matcherLinks) {
  if (oldDuplicateOf.get(rowId) === spending.id) dups.agree++;
  else if (oldDuplicateOf.has(rowId)) dups.disagree++;
  else {
    dups.matcherOnly++;
    const r = byId.get(rowId);
    bump(
      dupMatcherOnly,
      r.status === "pending" ? "old: still pending" : r.status === "accepted" && !AUTOMATIC.has(r.decided_by) ? "old: a person made it an expense" : "old: decided otherwise",
    );
  }
}
const dupOldOnly = new Map();
for (const row of oldDuplicates) {
  if (matcherLinks.has(row.id)) continue;
  dups.oldOnly++;
  const s = db.prepare("SELECT id, date, amount_halere, account FROM spending WHERE id = ?").get(row.spending_id);
  let why;
  if (consumed.has(row.id)) why = "the matcher paired it as a transfer";
  else if (row.currency !== HOUSEHOLD_CURRENCY) why = "not in the household currency";
  else if (!s) why = "the expense is gone";
  else if (!s.account || s.account !== row.ledger_account) why = "expense on another account";
  else if (s.amount_halere !== -row.amount_halere) why = "amounts differ";
  else if (days(s.date, row.date) > DUPLICATE_DAYS) why = `more than ${DUPLICATE_DAYS} day apart`;
  else if (madeFromRows.has(s.id)) why = "the expense was made from a bank row";
  else why = "another candidate on one side";
  bump(dupOldOnly, why);
}
out();
out("Duplicates of hand-entered expenses");
out(`  old app, filed by bank-sync: ${oldDuplicates.length} rows`);
out(`  matcher: ${matcherLinks.size} rows`);
out(`  agree (same expense): ${dups.agree}`);
out(`  disagree (linked to another expense): ${dups.disagree}`);
out(`  matcher-only (not a duplicate to the old app): ${dups.matcherOnly}${breakdown(dupMatcherOnly)}`);
out(`  old-only (the matcher would not file it): ${dups.oldOnly}${breakdown(dupOldOnly)}`);

// ---- What the matcher doesn't do, for scale ----

const internalSingles = rows.filter((r) => r.kind === "internal" && r.status === "skipped" && AUTOMATIC.has(r.decided_by));
const inPair = internalSingles.filter((r) => oldPartner.has(r.id)).length;
out();
out("Not the matcher's job (it files pairs and duplicates only)");
out(`  rows the old app skipped as internal: ${internalSingles.length}, of which in one of its pairs: ${inPair}`);

console.log(lines.join("\n"));
