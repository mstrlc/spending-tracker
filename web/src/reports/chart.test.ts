import { describe, expect, it } from "vitest";
import { compactAmount, niceTicks } from "./chart";

describe("chart helpers", () => {
  it("picks round gridlines covering the largest bar", () => {
    // 4,350 CZK → 0, 1,000 … 5,000
    expect(niceTicks(435_000, "CZK")).toEqual([0, 100_000, 200_000, 300_000, 400_000, 500_000]);
    // 58,397.18 CZK → steps of 20,000
    expect(niceTicks(5_839_718, "CZK")).toEqual([0, 2_000_000, 4_000_000, 6_000_000]);
    // 900 CZK → steps of 200
    expect(niceTicks(90_000, "CZK")).toEqual([0, 20_000, 40_000, 60_000, 80_000, 100_000]);
    expect(niceTicks(0, "CZK")).toEqual([0, 20, 40, 60, 80, 100]);
  });

  it("shortens amounts", () => {
    expect(compactAmount(95_000, "CZK", "en")).toBe("950");
    expect(compactAmount(290_000, "CZK", "en")).toBe("2.9K");
    expect(compactAmount(5_839_718, "CZK", "en")).toBe("58K");
  });
});
