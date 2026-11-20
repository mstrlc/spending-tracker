import { describe, expect, it } from "vitest";
import type { Income } from "../api/types";
import { groupIncome, payslipSummary, sortEarningsForPicker } from "./net";

function row(overrides: Partial<Income> & { id: string }): Income {
  return {
    date: "2026-10-15",
    amountMinor: 100_00,
    source: "Acme",
    accountId: null,
    recipientUserId: null,
    notes: "",
    version: 1,
    earnedMonth: "2026-10",
    kind: "earning",
    deductedFromId: null,
    deductedMinor: 0,
    ...overrides,
  };
}

describe("groupIncome", () => {
  it("nests linked deductions under their earning and leaves unlinked deductions in place", () => {
    const salary = row({ id: "e1", source: "Acme", amountMinor: 60_000_00, deductedMinor: 11_000_00 });
    const tax = row({ id: "d1", kind: "deduction", source: "Tax", amountMinor: 8_000_00, deductedFromId: "e1" });
    const health = row({ id: "d2", kind: "deduction", source: "Health", amountMinor: 3_000_00, deductedFromId: "e1" });
    const zpmv = row({ id: "d3", kind: "deduction", source: "ZPMV", amountMinor: 2_500_00, deductedFromId: null });
    const orphan = row({ id: "d4", kind: "deduction", source: "Old", amountMinor: 1_000_00, deductedFromId: "other-month" });

    const grouped = groupIncome([salary, zpmv, tax, orphan, health]);
    expect(grouped).toEqual([
      { income: salary, linkedDeductions: [tax, health] },
      { income: zpmv, linkedDeductions: [] },
      { income: orphan, linkedDeductions: [] },
    ]);
  });
});

describe("sortEarningsForPicker", () => {
  it("puts matching recipient first and excludes deductions", () => {
    const a = row({ id: "a", recipientUserId: "u2", date: "2026-10-20" });
    const b = row({ id: "b", recipientUserId: "u1", date: "2026-10-10" });
    const c = row({ id: "c", recipientUserId: "u1", date: "2026-10-25" });
    const d = row({ id: "d", kind: "deduction", recipientUserId: "u1" });

    expect(sortEarningsForPicker([a, b, c, d], "u1").map((x) => x.id)).toEqual(["c", "b", "a"]);
  });
});

describe("payslipSummary", () => {
  it("computes deducted and net and flags mismatch against expected bank net", () => {
    expect(payslipSummary(60_000_00, [8_000_00, 4_000_00], 48_000_00)).toEqual({
      grossMinor: 60_000_00,
      deductedMinor: 12_000_00,
      netMinor: 48_000_00,
      mismatch: false,
    });
    expect(payslipSummary(60_000_00, [8_000_00], 48_000_00)).toEqual({
      grossMinor: 60_000_00,
      deductedMinor: 8_000_00,
      netMinor: 52_000_00,
      mismatch: true,
    });
  });
});
