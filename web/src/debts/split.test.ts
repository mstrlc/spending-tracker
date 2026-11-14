import { expect, test } from "vitest";
import type { Member } from "../api/types";
import { describeShares, sharesFrom, weightsFromIncome } from "./split";

const members: Member[] = [
  { userId: "a", displayName: "Alice", role: "owner", hasLogin: true },
  { userId: "b", displayName: "Bob", role: "member", hasLogin: false },
];

test("drafts become shares only when every weight is a whole number and one is above zero", () => {
  expect(sharesFrom({ a: "60", b: " 40 " })).toEqual([
    { userId: "a", weight: 60 },
    { userId: "b", weight: 40 },
  ]);
  expect(sharesFrom({ a: "1", b: "0" })).toEqual([{ userId: "a", weight: 1 }]);
  expect(sharesFrom({ a: "0", b: "" })).toBeNull();
  expect(sharesFrom({ a: "1.5", b: "1" })).toBeNull();
});

test("income splits into whole percent that add up to 100, ignoring joint income", () => {
  const income = [
    { amountMinor: 200, recipientUserId: "a" },
    { amountMinor: 100, recipientUserId: "b" },
    { amountMinor: 999, recipientUserId: null },
  ];
  expect(weightsFromIncome(income, members)).toEqual([
    { userId: "a", weight: 67 },
    { userId: "b", weight: 33 },
  ]);
  expect(weightsFromIncome([{ amountMinor: 5, recipientUserId: null }], members)).toBeNull();
});

test("income splits by what each member earned net of deductions", () => {
  const income = [
    { amountMinor: 300, recipientUserId: "a" },
    { amountMinor: 100, recipientUserId: "a", kind: "deduction" as const },
    { amountMinor: 200, recipientUserId: "b" },
  ];
  expect(weightsFromIncome(income, members)).toEqual([
    { userId: "a", weight: 50 },
    { userId: "b", weight: 50 },
  ]);
});

test("shares read as percent in the household's order", () => {
  expect(describeShares([{ userId: "b", weight: 1 }, { userId: "a", weight: 3 }], members)).toBe("Alice 75 % · Bob 25 %");
});
