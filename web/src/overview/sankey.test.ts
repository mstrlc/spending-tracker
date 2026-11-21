import { describe, expect, it } from "vitest";
import { buildFlow, layoutSankey } from "./sankey";

const label = (id: string | null) => id ?? "Uncategorized";
const color = () => "hue-1";
const sum = (nodes: { valueMinor: number }[]) => nodes.reduce((s, n) => s + n.valueMinor, 0);

describe("buildFlow", () => {
  it("balances income against deductions, spending, investing and what's left", () => {
    const flow = buildFlow(
      {
        incomeSources: [{ name: "Initech", amountMinor: 60_000 }],
        deductionsMinor: 3_000,
        topCategories: [
          { categoryId: "food", amountMinor: 20_000 },
          { categoryId: null, amountMinor: 7_000 },
        ],
        investedMinor: 10_000,
      },
      label,
      color,
    );
    expect(flow.totalMinor).toBe(60_000);
    expect(flow.sources.map((n) => n.label)).toEqual(["Initech"]);
    expect(flow.uses.map((n) => [n.label, n.valueMinor])).toEqual([
      ["Deductions", 3_000],
      ["food", 20_000],
      ["Uncategorized", 7_000],
      ["Invested", 10_000],
      ["Left over", 20_000],
    ]);
    expect(flow.uses.find((n) => n.label === "Uncategorized")?.categoryId).toBe("");
  });

  it("draws on savings when spending is more than came in, and counts asset withdrawals as coming in", () => {
    const flow = buildFlow(
      { incomeSources: [{ name: "", amountMinor: 10_000 }], deductionsMinor: 0, topCategories: [{ categoryId: "car", amountMinor: 30_000 }], investedMinor: -5_000 },
      label,
      color,
    );
    expect(flow.sources.map((n) => [n.label, n.valueMinor])).toEqual([
      ["Unnamed income", 10_000],
      ["Taken from assets", 5_000],
      ["From savings", 15_000],
    ]);
    expect(sum(flow.sources)).toBe(sum(flow.uses));
  });

  it("sums small categories into one node", () => {
    const flow = buildFlow(
      {
        incomeSources: [{ name: "Pay", amountMinor: 100_000 }],
        deductionsMinor: 0,
        topCategories: [
          { categoryId: "a", amountMinor: 50_000 },
          { categoryId: "b", amountMinor: 1_000 },
          { categoryId: "c", amountMinor: 500 },
        ],
        investedMinor: 0,
      },
      label,
      color,
    );
    expect(flow.uses.map((n) => [n.label, n.valueMinor])).toEqual([
      ["a", 50_000],
      ["Other spending", 1_500],
      ["Left over", 48_500],
    ]);
  });

  it("is empty with nothing coming in or going out", () => {
    expect(buildFlow({ incomeSources: [], deductionsMinor: 0, topCategories: [], investedMinor: 0 }, label, color).sources).toEqual([]);
  });
});

describe("layoutSankey", () => {
  it("stacks each side's bands along the pot", () => {
    const flow = buildFlow(
      { incomeSources: [{ name: "A", amountMinor: 3 }, { name: "B", amountMinor: 1 }], deductionsMinor: 0, topCategories: [{ categoryId: "x", amountMinor: 4 }], investedMinor: 0 },
      label,
      color,
    );
    const layout = layoutSankey(flow, { width: 600, columns: [100, 300, 500], scale: 200, gap: 10 });
    expect(layout.pot.height).toBe(200);
    expect(layout.sources.map((n) => n.height)).toEqual([150, 50]);
    expect(layout.height).toBe(210);
    expect(layout.links).toHaveLength(3);
  });
});
