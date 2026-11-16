import { describe, expect, it } from "vitest";
import type { BankRow } from "../api/types";
import { draftRule, monthLabel, monthOffsetOf, ruleConditions } from "./ruleDrafts";

const row = (r: Partial<BankRow>): BankRow => ({
  id: "r",
  feedId: "f",
  accountId: "a",
  date: "2026-09-10",
  bookingDate: "2026-09-10",
  amountMinor: -12_000,
  currency: "CZK",
  counterpartyName: "",
  counterpartyIban: null,
  counterpartyAccount: "",
  text: "Bakery Prague 4310",
  message: "",
  code: "",
  hint: "",
  merchantKey: "BAKERY PRAGUE",
  outcome: "expense",
  expenseId: "e",
  incomeId: null,
  transferId: null,
  settlementId: null,
  skipReason: null,
  decidedByUserId: "u1",
  decidedAuto: false,
  ruleId: null,
  decidedAt: null,
  version: 2,
  suggestion: null,
  ...r,
});

describe("rule drafts", () => {
  it("makes an expense rule from the row's merchant key and what the expense says", () => {
    const draft = draftRule(row({}), { outcome: "expense", merchant: "Corner Bakery", categoryId: "c1", paidByUserId: "u1" });
    expect(draft).toEqual({
      name: "Corner Bakery",
      merchantKey: "BAKERY PRAGUE",
      counterpartyIban: null,
      textContains: null,
      direction: "out",
      auto: false,
      outcome: "expense",
      merchant: "Corner Bakery",
      categoryId: "c1",
      personUserId: "u1",
    });
  });

  it("falls back to the counterparty's IBAN when the descriptor is only that, then to the text", () => {
    const iban = "CZ6508000000192000145399";
    const byIban = draftRule(row({ merchantKey: iban, counterpartyIban: iban, text: "" }), { outcome: "skip", reason: "Rent" });
    expect(byIban).toMatchObject({ merchantKey: null, counterpartyIban: iban, textContains: null, outcome: "skip", skipReason: "Rent" });
    const byText = draftRule(row({ merchantKey: "", text: "", counterpartyName: "Jana Nováková" }), null);
    expect(byText).toMatchObject({ merchantKey: null, counterpartyIban: null, textContains: "Jana Nováková" });
    expect(byText.outcome).toBeUndefined();
  });

  it("makes income and transfer rules for money in, and names them after the source", () => {
    const income = draftRule(row({ amountMinor: 3_000_000, text: "Employer" }), { outcome: "income", source: "Acme Ltd", categoryId: "cat-salary", recipientUserId: null });
    expect(income).toMatchObject({ name: "Acme Ltd", direction: "in", outcome: "income", incomeSource: "Acme Ltd", categoryId: "cat-salary", personUserId: null });
    const transfer = draftRule(row({ text: "To Savings" }), { outcome: "transfer", otherAccountId: "acc2" });
    expect(transfer).toMatchObject({ name: "To Savings", outcome: "transfer", otherAccountId: "acc2" });
  });

  it("describes the conditions a rule sets", () => {
    expect(
      ruleConditions(
        { counterpartyIban: null, merchantKey: "KAUFLAND CZ", textContains: "KAUFLAND", direction: "out" },
        { account: "Revolut" },
      ),
    ).toEqual(["Merchant KAUFLAND CZ", "Text contains KAUFLAND", "Account Revolut", "Money out"]);
  });

  it("makes a deduction rule from a deduction, keeping how far its month is from the row's", () => {
    const draft = draftRule(row({ text: "ZPMV advance", merchantKey: "ZPMV ADVANCE", outcome: "income" }), {
      outcome: "deduction",
      source: "ZPMV",
      recipientUserId: "u1",
      monthOffset: -1,
    });
    expect(draft).toMatchObject({ name: "ZPMV", outcome: "deduction", incomeSource: "ZPMV", personUserId: "u1", monthOffset: -1, direction: "out" });
  });

  it("counts months between a row's date and the month a deduction is for", () => {
    expect(monthOffsetOf("2026-09-12", "2026-08")).toBe(-1);
    expect(monthOffsetOf("2026-09-12", "2026-09")).toBe(0);
    expect(monthOffsetOf("2026-01-05", "2025-11")).toBe(-2);
    expect(monthOffsetOf("2026-12-31", "2027-01")).toBe(1);
    expect(monthLabel(-1)).toBe("For the previous month");
    expect(monthLabel(0)).toBe("For the same month");
    expect(monthLabel(-3)).toBe("For 3 months before");
  });

  it("makes a contribution rule from a row that went into an asset", () => {
    const draft = draftRule(row({ text: "Portu", merchantKey: "PORTU", outcome: "contribution", contributionId: "c" }), {
      outcome: "contribution",
      assetId: "asset-1",
    });
    expect(draft).toMatchObject({ name: "Portu", merchantKey: "PORTU", direction: "out", outcome: "contribution", assetId: "asset-1" });
  });
});
