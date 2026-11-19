#!/usr/bin/env node
// Converts the old app's database (data/legacy/d1.sqlite) into an import file for POST /import, which the
// Import page uploads. Expenses and income dated before the cutover come across, with the members, accounts,
// categories and trips they use; the old Investments expenses become contributions to assets (see "Importing the old
// app" in ARCHITECTURE.md). From the cutover on, the bank is the source: no bank feeds, rows, transfers or pairings
// come across, so nothing old needs matching against them. Feeds are added afresh and read statements from the cutover.
//
// Every bank account the old app read gets a ledger account of its own with its IBAN: the old app filed some under
// one (ČSOB Sam's savings under her current account), which hid money moving between them.
//
// The old merchant aliases (bank text -> merchant) are written to a second file as suggestion rules, to add with
// POST /inbox-rules once the import is in.
//
//   node scripts/legacy-import.mjs [data/legacy/d1.sqlite] [data/legacy/import.json] [data/legacy/rules.json]
//
// The output holds real household data: keep it in the gitignored data/legacy/.
import { DatabaseSync } from "node:sqlite";
import { writeFileSync } from "node:fs";

const [source = "data/legacy/d1.sqlite", target = "data/legacy/import.json", rulesTarget = "data/legacy/rules.json"] =
  process.argv.slice(2);

/** The day the new app takes over: records before it come from the old app, everything from it on from the bank. */
const CUTOVER = "2026-10-01";
const db = new DatabaseSync(source, { readOnly: true });
const all = (sql) => db.prepare(sql).all();

const JOINT = "Joint";
const INVESTMENTS = "Investments";
const person = (name) => (name === "" || name === JOINT ? null : name);
const blank = (value) => (value === "" ? null : value);

const spending = all(`SELECT * FROM spending WHERE date < '${CUTOVER}' ORDER BY date, id`);
// Income only knew its month, imported as the 1st: a month before the cutover's.
const income = all(`SELECT * FROM income WHERE month || '-01' < '${CUTOVER}' ORDER BY month, id`);
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

// ---- Accounts ----

// The bank accounts the old app read, each its own ledger account. The one whose IBAN is the old ledger account's
// (or the only one) keeps the old name, which the expenses use; another IBAN filed under the same name becomes
// "<name> · <the bank's name for it>" (with the last digits when two share it). Currency pockets of one IBAN stay one account.
const bankAccounts = all("SELECT * FROM bank_accounts WHERE mode = 'import' AND ledger_account != '' ORDER BY ledger_account, name, iban");
const splitAccounts = new Map();
for (const [ledger, group] of Map.groupBy(bankAccounts, (b) => b.ledger_account)) {
  const old = legacyAccounts.get(ledger);
  const ibans = [...new Set(group.map((b) => b.iban || null))];
  const main = ibans.includes(old?.iban || null) ? old?.iban || null : ibans[0];
  for (const iban of ibans) {
    const bank = group.find((b) => (b.iban || null) === iban);
    // Two with the same bank name ("Spoření s bonusem") are told apart by their account number's last digits.
    const twins = group.filter((b) => b.name === bank.name && (b.iban || null) !== main).length > 1;
    const name = iban === main ? ledger : `${ledger} · ${bank.name || iban}${twins ? ` …${iban.slice(-4)}` : ""}`;
    splitAccounts.set(`${ledger}|${iban}`, { name, ledger, iban });
  }
}

const usedAccounts = new Set([
  ...expenses.map((e) => e.account),
  ...[...assets.values()].flatMap((a) => a.contributions.map((c) => c.account)),
  ...[...splitAccounts.values()].map((a) => a.name),
].filter(Boolean));

const accounts = [...usedAccounts].sort().map((name) => {
  const split = [...splitAccounts.values()].find((a) => a.name === name);
  const old = legacyAccounts.get(split?.ledger ?? name);
  if (!old) return { name, kind: name === "Cash" ? "cash" : "other", owner: null, status: "active", iban: null };
  return {
    name,
    // "not-account" was a stand-in such as "(odpočet z faktury)": costs netted against an invoice.
    // A savings account ("Spoření", "spořicí") pays interest with tax withheld; the import gives it the Czech 15%.
    kind: old.status === "not-account" ? "other" : /kreditka/i.test(name) ? "credit_card" : /spo[řr]|saving/i.test(name) ? "savings" : "bank",
    owner: person(old.owner),
    status: old.status === "closed" ? "closed" : "active",
    iban: split ? split.iban : blank(old.iban),
  };
});

// ---- Merchant aliases as suggestion rules ----

const rules = all("SELECT * FROM merchant_aliases ORDER BY key").map((a) => ({
  name: a.merchant,
  textContains: a.key,
  outcome: "expense",
  merchant: a.merchant,
  auto: false,
}));

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

const file = {
  currency: "CZK",
  // Debts count from the cutover: before it the old app's ledger has no record of who owed whom.
  debtsFrom: CUTOVER,
  people,
  accounts,
  categories,
  trips,
  expenses,
  income: incomeLines,
  assets: [...assets.values()],
};
writeFileSync(target, JSON.stringify(file, null, 2) + "\n");
writeFileSync(rulesTarget, JSON.stringify(rules, null, 2) + "\n");

const contributions = file.assets.reduce((n, a) => n + a.contributions.length, 0);
console.log(
  `Wrote ${target}: ${people.length} people, ${accounts.length} accounts, ${categories.length} categories, ` +
    `${trips.length} trips, ${expenses.length} expenses, ${incomeLines.length} income lines, ` +
    `${file.assets.length} assets with ${contributions} contributions (before ${CUTOVER}).\n` +
    `Wrote ${rulesTarget}: ${rules.length} suggestion rules from the old merchant aliases.`,
);
