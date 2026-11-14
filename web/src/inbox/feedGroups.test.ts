import { describe, expect, it } from "vitest";
import type { Account, BankConnection, BankFeed } from "../api/types";
import { groupFeeds } from "./feedGroups";

const account = (id: string, name: string, ownerUserId: string | null, kind: Account["kind"] = "bank", status: Account["status"] = "active") =>
  ({ id, name, ownerUserId, kind, status, version: 1, iban: null }) as Account;

const feed = (id: string, accountId: string, institution: BankFeed["institution"], currency = "CZK", connectionId: string | null = null) =>
  ({ id, accountId, name: id, institution, currency, connectionId, pendingCount: 0, rowCount: 1 }) as unknown as BankFeed;

const connection = (id: string, institutionName: string) => ({ id, institutionName, accounts: [] }) as unknown as BankConnection;

describe("groupFeeds", () => {
  const accounts = [
    account("kb", "KB Partner", "partner"),
    account("kb-savings", "KB Partner · Spořicí", "partner", "savings"),
    account("csob", "ČSOB Partner", "partner"),
    account("old", "Old card", "partner", "credit_card", "closed"),
    account("joint", "Revolut Joint", null),
    account("rb", "RB Alex", "alex"),
  ];
  const members = [{ userId: "alex" }, { userId: "partner" }];
  const connections = [connection("c-kb", "Komerční banka"), connection("c-rev", "Revolut"), connection("c-new", "Fio")];
  const feeds = [
    feed("joint-eur", "joint", "revolut", "EUR", "c-rev"),
    feed("joint-czk", "joint", "revolut", "CZK", "c-rev"),
    feed("kb-savings", "kb-savings", "kb", "CZK", "c-kb"),
    feed("old", "old", "kb"),
    feed("kb", "kb", "kb", "CZK", "c-kb"),
    feed("csob", "csob", "csob"),
    feed("rb", "rb", "raiffeisen"),
  ];
  const label = (i: string) => ({ kb: "KB", csob: "ČSOB", raiffeisen: "Raiffeisenbank", revolut: "Revolut" })[i] ?? i;
  const { people, unlinked } = groupFeeds(feeds, accounts, members, connections, "CZK", label as never);

  it("groups by person in household order, joint last", () => {
    expect(people.map((p) => p.userId)).toEqual(["alex", "partner", null]);
  });

  it("splits a person's feeds by connection or statement bank, by name", () => {
    const partner = people[1];
    expect(partner.banks.map((b) => b.key)).toEqual(["statements:csob", "statements:kb", "connection:c-kb"]);
    expect(partner.banks[2].accounts.map((a) => a.accountId)).toEqual(["kb", "kb-savings"]);
  });

  it("puts the household currency first within an account", () => {
    expect(people[2].banks[0].accounts[0].feeds.map((f) => f.currency)).toEqual(["CZK", "EUR"]);
  });

  it("returns connections with no feed apart", () => {
    expect(unlinked.map((c) => c.id)).toEqual(["c-new"]);
  });
});
