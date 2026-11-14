import { describe, expect, it } from "vitest";
import type { BankRow } from "../api/types";
import { draftRule, ruleConditions } from "./ruleDrafts";

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
    const income = draftRule(row({ amountMinor: 3_000_000, text: "Employer" }), { outcome: "income", source: "Acme Ltd", recipientUserId: null });
    expect(income).toMatchObject({ name: "Acme Ltd", direction: "in", outcome: "income", incomeSource: "Acme Ltd", personUserId: null });
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
});
