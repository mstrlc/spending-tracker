import { expect, test } from "vitest";
import type { ActivityEntry } from "../api/types";
import { describeAction, describeChanges, type Lookups } from "./describe";

const lookups: Lookups = {
  currency: "CZK",
  categories: [{ id: "c1", parentId: null, name: "Food", kind: "consumption", archived: false, sortOrder: 0, version: 1 }],
  accounts: [],
  members: [{ userId: "u1", displayName: "Alice", role: "owner" }],
};

const entry = (overrides: Partial<ActivityEntry>): ActivityEntry => ({
  id: 1,
  at: "2026-10-31T12:00:00Z",
  actorUserId: "u1",
  actorName: "Alice",
  entity: "expense",
  entityId: "e1",
  action: "update",
  label: "Albert",
  changes: [],
  ...overrides,
});

test("an update lists each changed field with names and amounts", () => {
  const changes = describeChanges(
    entry({
      changes: [
        { field: "amountMinor", before: "12990", after: "13490" },
        { field: "categoryId", before: null, after: "c1" },
        { field: "accountId", before: "gone", after: null },
      ],
    }),
    lookups,
  );
  expect(changes.map((c) => [c.label, c.before, c.after])).toEqual([
    ["Amount", expect.stringContaining("129.90"), expect.stringContaining("134.90")],
    ["Category", "Uncategorized", "Food"],
    ["Account", "a deleted account", "No account"],
  ]);
});

test("a deleted record is summed up by the fields that carry something", () => {
  const changes = describeChanges(
    entry({
      action: "delete",
      changes: [
        { field: "date", before: "2026-10-03", after: null },
        { field: "merchant", before: "Albert", after: null },
        { field: "notes", before: "", after: null },
        { field: "paidByUserId", before: "u1", after: null },
      ],
    }),
    lookups,
  );
  expect(changes.map((c) => c.label)).toEqual(["Date", "Paid by"]);
  expect(changes[1].after).toBe("Alice");
});

test("members joining and leaving read as their own actions", () => {
  expect(describeAction(entry({ entity: "member", entityId: "u1", action: "create", label: "Alice" }))).toEqual(["", "Alice", "joined"]);
  expect(describeAction(entry({ entity: "member", entityId: "u2", action: "delete", label: "Bob" }))).toEqual(["Alice removed", "Bob", ""]);
  expect(describeAction(entry({ action: "delete", label: "" }))).toEqual(["Alice deleted expense", "untitled expense", ""]);
});
