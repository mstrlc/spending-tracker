import { describe, expect, it } from "vitest";
import type { BankFeed } from "../api/types";
import { importFromAfter } from "./connections";

const feed = (accountId: string, lastDate: string | null) => ({ accountId, lastDate }) as BankFeed;

describe("importFromAfter", () => {
  it("starts the day after the account's newest row across its feeds", () => {
    const feeds = [feed("a", "2026-03-10"), feed("a", "2026-04-30"), feed("b", "2026-06-01"), feed("a", null)];
    expect(importFromAfter(feeds, "a")).toBe("2026-05-01");
  });

  it("is empty for an account with no rows yet", () => {
    expect(importFromAfter([feed("b", "2026-06-01")], "a")).toBe("");
  });

  it("never starts before the household's balances do", () => {
    expect(importFromAfter([], "a", "2026-10-01")).toBe("2026-10-01");
    expect(importFromAfter([feed("a", "2026-09-10")], "a", "2026-10-01")).toBe("2026-10-01");
    expect(importFromAfter([feed("a", "2026-10-10")], "a", "2026-10-01")).toBe("2026-10-11");
  });
});
