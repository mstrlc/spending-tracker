import { describe, expect, test } from "vitest";
import { formatMoney, parseMoney, toInputValue } from "./money";

describe("parseMoney", () => {
  test.each([
    ["129.90", 12990],
    ["129,9", 12990],
    ["129", 12900],
    ["-45.5", -4550],
    ["1 299,50", 129950],
    ["0.01", 1],
  ])("%s → %d", (input, expected) => {
    expect(parseMoney(input, "CZK")).toBe(expected);
  });

  test.each(["", "abc", "1.234", "1,2,3", "--5"])("rejects %j", (input) => {
    expect(parseMoney(input, "CZK")).toBeNull();
  });

  test("respects zero-decimal currencies", () => {
    expect(parseMoney("1500", "JPY")).toBe(1500);
    expect(parseMoney("15.5", "JPY")).toBeNull();
  });
});

describe("toInputValue", () => {
  test("round-trips through parseMoney", () => {
    for (const minor of [12990, -4550, 1, 100]) {
      expect(parseMoney(toInputValue(minor, "EUR"), "EUR")).toBe(minor);
    }
  });
});

describe("formatMoney", () => {
  test("formats minor units in the currency", () => {
    expect(formatMoney(12990, "EUR", "en-US")).toBe("€129.90");
    expect(formatMoney(-4550, "USD", "en-US")).toBe("-$45.50");
  });
});
