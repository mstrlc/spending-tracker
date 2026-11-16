import { describe, expect, it } from "vitest";
import { columnOptions, filterRows, isFilterActive, nextSort, sortRows } from "./sorting";

type Row = { id: string; name: string | null; amount: number };
const rows: Row[] = [
  { id: "a", name: "Žabka", amount: 100 },
  { id: "b", name: null, amount: 50 },
  { id: "c", name: "Albert", amount: 100 },
  { id: "d", name: "Čedok", amount: 900 },
];
const values = { name: (r: Row) => r.name, amount: (r: Row) => r.amount };
const ids = (list: Row[]) => list.map((r) => r.id).join("");

describe("table sorting", () => {
  it("sorts text the Czech way and numbers as numbers, empty last, ties in their own order", () => {
    expect(ids(sortRows(rows, { key: "name", dir: "asc" }, values))).toBe("cdab");
    expect(ids(sortRows(rows, { key: "name", dir: "desc" }, values))).toBe("adcb");
    expect(ids(sortRows(rows, { key: "amount", dir: "desc" }, values))).toBe("dacb");
    expect(ids(sortRows(rows, null, values))).toBe("abcd");
  });

  it("goes first direction, other direction, then back to the table's own order", () => {
    const first = nextSort(null, "amount", "desc");
    expect(first).toEqual({ key: "amount", dir: "desc" });
    const second = nextSort(first, "amount", "desc");
    expect(second).toEqual({ key: "amount", dir: "asc" });
    expect(nextSort(second, "amount", "desc")).toBeNull();
    expect(nextSort(second, "name", "asc")).toEqual({ key: "name", dir: "asc" });
  });

  it("filters rows by query and selected values across columns", () => {
    expect(ids(filterRows(rows, { name: { query: "al" } }, values))).toBe("c");
    expect(ids(filterRows(rows, { amount: { selected: ["100"] } }, values))).toBe("ac");
    expect(ids(filterRows(rows, { name: { selected: [""] } }, values))).toBe("b");
    expect(ids(filterRows(rows, { amount: { selected: ["100"] }, name: { query: "ž" } }, values))).toBe("a");
    expect(isFilterActive({ query: "  " })).toBe(false);
    expect(isFilterActive({ selected: [] })).toBe(false);
    expect(isFilterActive({ query: "a" })).toBe(true);
  });

  it("collects distinct sorted options scoped by other active filters", () => {
    expect(columnOptions(rows, {}, values, "name")).toEqual(["", "Albert", "Čedok", "Žabka"]);
    expect(columnOptions(rows, { amount: { selected: ["100"] } }, values, "name")).toEqual(["Albert", "Žabka"]);
    expect(columnOptions(rows, { amount: { selected: ["100"] } }, values, "amount")).toEqual(["50", "100", "900"]);
  });
});
