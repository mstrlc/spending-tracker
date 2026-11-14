import type { Account, ActivityEntry, Category, FieldChange, Member, Merchant, Trip } from "../api/types";
import { formatDay } from "../lib/dates";
import { formatMoney } from "../lib/money";

/** What the activity log needs to turn ids and minor units back into names and amounts. */
export type Lookups = {
  currency: string;
  categories: Category[];
  accounts: Account[];
  members: Member[];
  trips: Trip[];
  merchants: Merchant[];
};

const NOUNS: Record<ActivityEntry["entity"], string> = {
  household: "household",
  member: "member",
  invite: "invite",
  account: "account",
  category: "category",
  expense: "expense",
  income: "income",
  asset: "asset",
  asset_contribution: "contribution to",
  asset_valuation: "value of",
  asset_fee: "fee on",
  trip: "trip",
  merchant: "merchant",
  split_rule: "split",
  split_share: "share of",
  settlement: "settlement",
};

const LABELS: Record<string, string> = {
  name: "Name",
  date: "Date",
  amountMinor: "Amount",
  valueMinor: "Worth",
  merchant: "Merchant",
  merchantId: "Merchant",
  defaultCategoryId: "Category",
  source: "Source",
  email: "Email",
  role: "Role",
  categoryId: "Category",
  parentId: "Under",
  kind: "Counts as",
  accountId: "Account",
  paidByUserId: "Whose",
  recipientUserId: "Received by",
  tripId: "Trip",
  originalAmountMinor: "Amount paid",
  originalCurrency: "Paid in",
  earnedMonth: "Earned in",
  startDate: "From",
  endDate: "To",
  iban: "IBAN",
  ownerUserId: "Owner",
  currency: "Currency",
  status: "Status",
  archived: "Archived",
  sortOrder: "Order",
  notes: "Notes",
  validFrom: "From",
  weight: "Share",
  fromUserId: "Paid by",
  toUserId: "To",
  createsDebt: "Creates a debt",
  expiresAt: "Expires",
  acceptedAt: "Accepted",
};

export const fieldLabel = (field: string) => LABELS[field] ?? field;

/**
 * The entry as a sentence around the record's name: ["Alice added expense", "Albert", ""] or ["", "Bob", "left"].
 * The name is returned apart so it can be emphasised.
 */
export function describeAction(entry: ActivityEntry): [string, string, string] {
  const who = entry.actorName ?? "Someone";
  // Contributions and values are labelled by their holding, which may be gone since.
  const name =
    entry.label ||
    (entry.entity === "asset_contribution" || entry.entity === "asset_valuation" || entry.entity === "asset_fee"
      ? "a deleted asset"
      : `untitled ${NOUNS[entry.entity]}`);
  if (entry.entity === "member") {
    const self = entry.actorUserId !== null && entry.actorUserId === entry.entityId;
    if (entry.action === "create") return self ? ["", name, "joined"] : [`${who} added`, name, ""];
    if (entry.action === "delete") return self ? ["", name, "left"] : [`${who} removed`, name, ""];
    return [`${who} changed the role of`, name, ""];
  }
  if (entry.entity === "invite" && entry.action === "update" && entry.changes.some((c) => c.field === "acceptedAt")) {
    return ["", name, "accepted the invite"];
  }
  const verb = { create: "added", update: "changed", delete: "deleted" }[entry.action];
  return [`${who} ${verb} ${NOUNS[entry.entity]}`, name, ""];
}

/** A field's value as a person reads it: names for ids, amounts in the currency, "Shared" for no owner. */
export function formatValue(field: string, value: string | null, lookups: Lookups): string {
  switch (field) {
    case "amountMinor":
    case "valueMinor":
      return value === null ? "–" : formatMoney(Number(value), lookups.currency);
    case "categoryId":
    case "parentId":
    case "defaultCategoryId":
      if (value === null) return field === "parentId" ? "Top level" : field === "defaultCategoryId" ? "None" : "Uncategorized";
      return lookups.categories.find((c) => c.id === value)?.name ?? "a deleted category";
    case "accountId":
      if (value === null) return "No account";
      return lookups.accounts.find((a) => a.id === value)?.name ?? "a deleted account";
    case "paidByUserId":
    case "recipientUserId":
    case "ownerUserId":
      if (value === null) return "Shared";
      return lookups.members.find((m) => m.userId === value)?.displayName ?? "a former member";
    case "fromUserId":
    case "toUserId":
      return lookups.members.find((m) => m.userId === value)?.displayName ?? "a former member";
    case "merchantId":
      if (value === null) return "No merchant";
      return lookups.merchants.find((m) => m.id === value)?.name ?? "a merged or deleted merchant";
    case "tripId":
      if (value === null) return "No trip";
      return lookups.trips.find((t) => t.id === value)?.name ?? "a deleted trip";
    case "date":
    case "validFrom":
    case "startDate":
    case "endDate":
      return value === null ? "–" : formatDay(value);
    case "expiresAt":
    case "acceptedAt":
      return value === null ? "–" : new Date(value).toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" });
    case "archived":
    case "createsDebt":
      return value === "true" ? "Yes" : "No";
    case "kind":
      // Category and income kinds; asset kinds (fund, stock, …) read as they are.
      if (value === "investment") return "Investing";
      if (value === "consumption") return "Spending";
      if (value === "earning") return "Income";
      return value === null ? "–" : value.charAt(0).toUpperCase() + value.slice(1);
    default:
      return value === null || value === "" ? "–" : value;
  }
}

/**
 * The lines shown under an entry. Updates list each changed field; a created or deleted record is summed up by
 * its fields that carry something, so a deleted expense can still be recognised.
 */
export function describeChanges(entry: ActivityEntry, lookups: Lookups): { label: string; before?: string; after: string }[] {
  if (entry.action === "update") {
    return entry.changes.map((c) => ({
      label: fieldLabel(c.field),
      before: formatValue(c.field, c.before, lookups),
      after: formatValue(c.field, c.after, lookups),
    }));
  }
  return entry.changes
    .filter((c) => isMeaningful(entry, c))
    .map((c) => {
      const value = entry.action === "create" ? c.after : c.before;
      return { label: fieldLabel(c.field), after: formatValue(c.field, value, lookups) };
    });
}

// The label already names the record, and empty or default fields say nothing about it.
function isMeaningful(entry: ActivityEntry, change: FieldChange): boolean {
  const value = entry.action === "create" ? change.after : change.before;
  const nameFields = { expense: ["merchant", "merchantId"], income: ["source"], invite: ["email"] }[entry.entity as string] ?? ["name"];
  if (nameFields.includes(change.field) || value === null || value === "") return false;
  if (change.field === "archived" && value === "false") return false;
  if (change.field === "sortOrder" || change.field === "status" || change.field === "kind") return false;
  return true;
}
