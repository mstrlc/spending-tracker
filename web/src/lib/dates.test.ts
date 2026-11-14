import { expect, test } from "vitest";
import { monthRange, shiftDays, shiftMonth } from "./dates";

test("monthRange covers the whole month, including leap February", () => {
  expect(monthRange("2026-10")).toEqual({ from: "2026-10-01", to: "2026-10-31" });
  expect(monthRange("2028-02")).toEqual({ from: "2028-02-01", to: "2028-02-29" });
});

test("shiftMonth crosses year boundaries", () => {
  expect(shiftMonth("2026-01", -1)).toBe("2025-12");
  expect(shiftMonth("2026-12", 1)).toBe("2027-01");
});

test("shiftDays crosses month and year boundaries", () => {
  expect(shiftDays("2026-03-01", -1)).toBe("2026-02-28");
  expect(shiftDays("2026-12-30", 3)).toBe("2027-01-02");
  expect(shiftDays("2026-09-10", 0)).toBe("2026-09-10");
});
