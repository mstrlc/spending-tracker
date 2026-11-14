import { describe, expect, it } from "vitest";
import type { BankRow, BankRowSuggestion } from "../api/types";
import { draftExpense, draftIncome, draftSettle, memberInText, oppositeSides, skipReason } from "./drafts";

const suggestion = (s: Partial<BankRowSuggestion>): BankRowSuggestion => ({
  record: "expense",
  amountMinor: null,
  originalAmountMinor: null,
  originalCurrency: null,
  merchant: "",
  categoryId: null,
  paidByUserId: null,
  incomeSource: "",
  duplicates: [],
  transferCandidates: [],
  transferAccountId: null,
  ruleId: null,
  ruleName: null,
  skipReason: null,
  ...s,
});

const row = (r: Partial<BankRow>): BankRow => ({
  id: "r",
  feedId: "f",
  accountId: "a",
  date: "2026-09-10",
  bookingDate: "2026-09-10",
  amountMinor: 0,
  currency: "CZK",
  counterpartyName: "",
  counterpartyIban: null,
  counterpartyAccount: "",
  text: "",
  message: "",
  code: "",
  hint: "",
  merchantKey: "",
  outcome: "pending",
  expenseId: null,
  incomeId: null,
  transferId: null,
  settlementId: null,
  skipReason: null,
  decidedByUserId: null,
  decidedAuto: false,
  ruleId: null,
  decidedAt: null,
  version: 1,
  suggestion: null,
  ...r,
});

describe("bank row drafts", () => {
  it("adds rows up into one expense, a refund netting against it", () => {
    const draft = draftExpense([
      row({ date: "2026-09-12", amountMinor: -50_000, suggestion: suggestion({ amountMinor: 50_000, merchant: "IKEA", paidByUserId: "u1" }) }),
      row({ date: "2026-09-10", amountMinor: 10_000, suggestion: suggestion({ record: "income", amountMinor: 10_000 }) }),
    ]);
    expect(draft).toMatchObject({ date: "2026-09-10", amountMinor: 40_000, merchant: "IKEA", paidByUserId: "u1", accountId: "a" });
    expect(draft.originalCurrency).toBeUndefined();
  });

  it("keeps the foreign amount, and leaves the amount empty without a rate", () => {
    const euro = row({ amountMinor: -250, currency: "EUR", suggestion: suggestion({ amountMinor: 6_250, originalAmountMinor: 250, originalCurrency: "EUR" }) });
    expect(draftExpense([euro])).toMatchObject({ amountMinor: 6_250, originalAmountMinor: 250, originalCurrency: "EUR" });
    const noRate = row({ amountMinor: -250, currency: "EUR", suggestion: suggestion({ originalAmountMinor: 250, originalCurrency: "EUR" }) });
    expect(draftExpense([noRate]).amountMinor).toBeUndefined();
  });

  it("drafts income for the account's owner and suggests a skip reason", () => {
    const pay = row({ amountMinor: 4_500_000, suggestion: suggestion({ record: "income", amountMinor: 4_500_000, incomeSource: "Employer" }) });
    expect(draftIncome([pay], "u2")).toMatchObject({ amountMinor: 4_500_000, source: "Employer", recipientUserId: "u2" });
    expect(skipReason([row({ hint: "Old app: Revolut top-up" })])).toBe("Revolut top-up");
    expect(skipReason([row({ hint: "Money in" })])).toBe("");
  });

  it("lets a matching rule decide who receives income and why a row is skipped", () => {
    const ruled = row({
      amountMinor: 4_500_000,
      hint: "Revolut top-up",
      suggestion: suggestion({ record: "income", amountMinor: 4_500_000, incomeSource: "Acme Ltd", paidByUserId: "u3", ruleId: "r1", ruleName: "Employer" }),
    });
    expect(draftIncome([ruled], "u2")).toMatchObject({ source: "Acme Ltd", recipientUserId: "u3" });
    // A rule that names nobody follows the account, which the suggestion already says.
    const followsAccount = row({ suggestion: suggestion({ record: "income", paidByUserId: null, ruleId: "r1", ruleName: "Employer" }) });
    expect(draftIncome([followsAccount], "u2").recipientUserId).toBeNull();
    const skipping = row({ hint: "Revolut top-up", suggestion: suggestion({ ruleId: "r2", ruleName: "Top-ups", skipReason: "Top-up by rule" }) });
    expect(skipReason([skipping])).toBe("Top-up by rule");
  });

  it("finds two opposite rows to pair", () => {
    const out = row({ id: "o", amountMinor: -100 });
    const incoming = row({ id: "i", amountMinor: 100 });
    expect(oppositeSides([incoming, out])).toEqual({ out, in: incoming });
    expect(oppositeSides([out, row({ id: "o2", amountMinor: -100 })])).toBeNull();
    expect(oppositeSides([out])).toBeNull();
  });

  const members = [
    { userId: "u1", displayName: "Jana Nováková" },
    { userId: "u2", displayName: "Petr" },
  ];

  it("names the one member a counterparty mentions, surname first or without accents", () => {
    expect(memberInText("NOVAKOVA JANA", members)).toBe("u1");
    expect(memberInText("Petr", members)).toBe("u2");
    expect(memberInText("Jana a Petr", members)).toBeNull();
    expect(memberInText("Jana a Petr", members, "u2")).toBe("u1");
    expect(memberInText("", members)).toBeNull();
  });

  it("prefills a settlement from the accounts' owners and the counterparty", () => {
    const owner = (id: string) => ({ jana: "u1", petr: "u2", joint: null })[id] ?? null;
    const out = row({ amountMinor: -150_000, accountId: "jana", suggestion: suggestion({ amountMinor: 150_000 }) });
    const incoming = row({ amountMinor: 150_000, accountId: "petr" });
    expect(draftSettle([out, incoming], owner, members)).toEqual({ fromUserId: "u1", toUserId: "u2", amountMinor: 150_000 });
    // One outgoing row from Jana's account to a counterparty called Petr.
    expect(draftSettle([row({ amountMinor: -500, accountId: "jana", counterpartyName: "Petr" })], owner, members)).toEqual({
      fromUserId: "u1",
      toUserId: "u2",
      amountMinor: null,
    });
    // One incoming row on a joint account from someone named Jana.
    expect(draftSettle([row({ amountMinor: 500, accountId: "joint", counterpartyName: "Novakova Jana" })], owner, members)).toMatchObject({
      fromUserId: "u1",
      toUserId: null,
    });
  });
});
