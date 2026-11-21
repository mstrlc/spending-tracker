#!/usr/bin/env node
// Converts the old app's database (data/legacy/d1.sqlite) into an import file for POST /import, which the
// Import page uploads. Expenses and income come across, with the members, accounts, categories and trips they use;
// the old Investments expenses become contributions to assets (see "Importing the old app" in ARCHITECTURE.md).
// Bank feeds and their rows come across too, with what a person decided about each. The old app's automatic
// decisions (bank-sync) are hints, not facts: those rows stay in the inbox with the old decision as their hint.
// Pairings and rules stay behind.
//
//   node scripts/legacy-import.mjs [data/legacy/d1.sqlite] [data/legacy/import.json]
//
// The output holds real household data: keep it in the gitignored data/legacy/.
import { DatabaseSync } from "node:sqlite";
import { writeFileSync } from "node:fs";

const [source = "data/legacy/d1.sqlite", target = "data/legacy/import.json"] = process.argv.slice(2);
const db = new DatabaseSync(source, { readOnly: true });
const all = (sql) => db.prepare(sql).all();

const JOINT = "Joint";
const INVESTMENTS = "Investments";
const person = (name) => (name === "" || name === JOINT ? null : name);
const blank = (value) => (value === "" ? null : value);

const spending = all("SELECT * FROM spending ORDER BY date, id");
const income = all("SELECT * FROM income ORDER BY month, id");
const legacyAccounts = new Map(all("SELECT * FROM accounts").map((a) => [a.name, a]));

// ---- Assets: the Investments expenses become contributions ----

/** Which asset an old Investments expense paid into, by merchant, held by whoever it was logged under. */
function assetFor(row) {
  const owner = person(row.who);
  const suffix = owner ? ` · ${owner}` : "";
  if (row.merchant === "Portu") return { name: `Portu${suffix}`, kind: "fund", owner };
  if (row.merchant === "ČSOB Stavební spořitelna") return { name: `ČSOB Stavební spoření${suffix}`, kind: "scheme", owner };
  return { name: `${row.merchant}${suffix}`, kind: "other", owner };
}

const assets = new Map();
for (const row of spending.filter((r) => r.category === INVESTMENTS)) {
  const { name, kind, owner } = assetFor(row);
  if (!assets.has(name)) assets.set(name, { name, kind, owner, notes: "", contributions: [] });
  const contributions = assets.get(name).contributions;
  // One row's note records a lump sum paid in on the same day but left out of the old app's stats.
  const lump = /additional ([\d ]+) lump sum/i.exec(row.notes);
  contributions.push({
    date: row.date,
    amountMinor: row.amount_halere,
    account: blank(row.account),
    notes: lump || /^Portu( Investice)?$/.test(row.notes) ? "" : row.notes,
  });
  if (lump) {
    contributions.push({
      date: row.date,
      amountMinor: Number(lump[1].replace(/ /g, "")) * 100,
      account: null,
      notes: "Lump sum, from a note in the old app",
    });
  }
}

// ---- Expenses and what they refer to ----

const expenses = spending
  .filter((r) => r.category !== INVESTMENTS)
  .map((r) => ({
    externalRef: `legacy:spending:${r.id}`,
    date: r.date,
    amountMinor: r.amount_halere,
    merchant: r.merchant,
    category: blank(r.category),
    subcategory: r.category ? blank(r.subcategory) : null,
    account: blank(r.account),
    person: person(r.who),
    notes: r.notes,
    trip: blank(r.trip),
    createsDebt: false,
  }));

// The old app recorded health and social insurance advances as negative income; here they're deductions.
const DEDUCTION_SOURCES = new Set(["ZPMV", "OSSZ"]);

const incomeLines = income.map((r) => {
  const deduction = DEDUCTION_SOURCES.has(r.source) && r.amount_halere < 0;
  return {
    externalRef: `legacy:income:${r.id}`,
    // The old app only knew the month (when it was earned); the day it arrived isn't recorded.
    date: `${r.month}-01`,
    earnedMonth: r.month,
    kind: deduction ? "deduction" : "earning",
    amountMinor: deduction ? -r.amount_halere : r.amount_halere,
    source: r.source,
    account: null,
    recipient: person(r.recipient),
    notes: r.notes,
  };
});

// ---- Bank feeds and rows ----

const INSTITUTIONS = {
  REVOLUT_REVOLT21: "revolut",
  CSOB_CEKOCZPPXXX: "csob",
  KOMERCNI_KOMBCZPPXXX: "kb",
  RAIFFEISEN_RZBCCZPP: "raiffeisen",
};
const AUTOMATIC = new Set(["", "bank-sync"]);
const investmentSpending = new Set(spending.filter((r) => r.category === INVESTMENTS).map((r) => r.id));

const feedNames = new Set();
const bankFeeds = [];
const feedOf = new Map();
for (const b of all("SELECT * FROM bank_accounts WHERE mode = 'import' ORDER BY ledger_account, currency, id")) {
  let name = [b.ledger_account, b.name, b.currency === "CZK" ? "" : b.currency].filter(Boolean).join(" · ");
  for (let n = 2; feedNames.has(name.toLowerCase()); n++) name = `${name.replace(/ \(\d+\)$/, "")} (${n})`;
  feedNames.add(name.toLowerCase());
  feedOf.set(b.id, name);
  bankFeeds.push({
    name,
    account: b.ledger_account,
    currency: b.currency,
    institution: INSTITUTIONS[b.institution_id] ?? "other",
    iban: blank(b.iban),
    importFrom: blank(b.import_from),
  });
}

const bankRows = all("SELECT * FROM bank_transactions ORDER BY date, id")
  .filter((t) => feedOf.has(t.account_id))
  .map((t) => {
    const row = { feed: feedOf.get(t.account_id), raw: JSON.parse(t.raw), hint: "" };
    const human = !AUTOMATIC.has(t.decided_by);
    if (human && t.status === "accepted" && t.spending_id != null) {
      if (investmentSpending.has(t.spending_id)) row.skipped = "Paid into an asset (old app)";
      else row.expense = `legacy:spending:${t.spending_id}`;
    } else if (human && t.status === "skipped") {
      row.skipped = t.reason || "Skipped in the old app";
    } else if (t.status !== "pending") {
      row.hint = `Old app: ${t.reason || t.kind}`;
    } else {
      row.hint = t.reason;
    }
    return row;
  });

const usedAccounts = new Set([
  ...expenses.map((e) => e.account),
  ...bankFeeds.map((f) => f.account),
  ...[...assets.values()].flatMap((a) => a.contributions.map((c) => c.account)),
].filter(Boolean));

const accounts = [...usedAccounts].sort().map((name) => {
  const old = legacyAccounts.get(name);
  if (!old) return { name, kind: name === "Cash" ? "cash" : "other", owner: null, status: "active", iban: null };
  return {
    name,
    // "not-account" was a stand-in such as "(odpočet z faktury)": costs netted against an invoice.
    kind: old.status === "not-account" ? "other" : /kreditka/i.test(name) ? "credit_card" : "bank",
    owner: person(old.owner),
    status: old.status === "closed" ? "closed" : "active",
    iban: blank(old.iban),
  };
});

const categoryNames = new Map();
for (const e of expenses) {
  if (!e.category) continue;
  if (!categoryNames.has(e.category)) categoryNames.set(e.category, new Set());
  if (e.subcategory) categoryNames.get(e.category).add(e.subcategory);
}
const categories = [...categoryNames].flatMap(([name, subs]) => [
  { name, parent: null, kind: "consumption" },
  ...[...subs].map((sub) => ({ name: sub, parent: name, kind: "consumption" })),
]);

const usedTrips = new Set(expenses.map((e) => e.trip).filter(Boolean));
const trips = all("SELECT * FROM trips ORDER BY start_date")
  .filter((t) => usedTrips.has(t.name))
  .map((t) => ({ name: t.name, startDate: t.start_date, endDate: t.end_date, notes: "" }));

const people = all("SELECT * FROM people WHERE kind = 'person' ORDER BY sort_order").map((p) => ({
  name: p.name,
  email: p.email,
}));

function nextDay(iso) {
  const d = new Date(`${iso}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + 1);
  return d.toISOString().slice(0, 10);
}
const newestExpense = expenses.reduce((max, e) => (e.date > max ? e.date : max), "");
const debtsFrom = newestExpense ? nextDay(newestExpense) : null;

const file = {
  currency: "CZK",
  ...(debtsFrom ? { debtsFrom } : {}),
  people,
  accounts,
  categories,
  trips,
  expenses,
  income: incomeLines,
  assets: [...assets.values()],
  bankFeeds,
  bankRows,
};
writeFileSync(target, JSON.stringify(file, null, 2) + "\n");

const contributions = file.assets.reduce((n, a) => n + a.contributions.length, 0);
console.log(
  `Wrote ${target}: ${people.length} people, ${accounts.length} accounts, ${categories.length} categories, ` +
    `${trips.length} trips, ${expenses.length} expenses, ${incomeLines.length} income lines, ` +
    `${file.assets.length} assets with ${contributions} contributions, ${bankFeeds.length} bank feeds with ${bankRows.length} rows.`,
);
