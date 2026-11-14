#!/usr/bin/env node
// Converts the old app's database (data/legacy/d1.sqlite) into an import file for POST /import, which the
// Import page uploads. Only expenses and income come across, with the members, accounts, categories and trips they
// use; the old Investments expenses become contributions to assets (see "Importing the old app" in ARCHITECTURE.md).
// Bank rows, pairings and rules stay behind: the old app's automatic decisions are hints, not facts.
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
  }));

const incomeLines = income.map((r) => ({
  externalRef: `legacy:income:${r.id}`,
  // The old app only knew the month (when it was earned); the day it arrived isn't recorded.
  date: `${r.month}-01`,
  earnedMonth: r.month,
  amountMinor: r.amount_halere,
  source: r.source,
  account: null,
  recipient: person(r.recipient),
  notes: r.notes,
}));

const usedAccounts = new Set([
  ...expenses.map((e) => e.account),
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

const file = {
  currency: "CZK",
  people,
  accounts,
  categories,
  trips,
  expenses,
  income: incomeLines,
  assets: [...assets.values()],
};
writeFileSync(target, JSON.stringify(file, null, 2) + "\n");

const contributions = file.assets.reduce((n, a) => n + a.contributions.length, 0);
console.log(
  `Wrote ${target}: ${people.length} people, ${accounts.length} accounts, ${categories.length} categories, ` +
    `${trips.length} trips, ${expenses.length} expenses, ${incomeLines.length} income lines, ` +
    `${file.assets.length} assets with ${contributions} contributions.`,
);
