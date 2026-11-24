import { describe, expect, it } from "vitest";
import type { BankConnection, BankFeed, FeedSyncResult } from "../api/types";
import { describeSyncAll, feedSyncState, relativeTime, syncOverview } from "./syncStatus";

const now = Date.parse("2026-11-24T12:00:00Z");
const ago = (ms: number) => new Date(now - ms).toISOString();
const H = 60 * 60 * 1000;

const feed = (over: Partial<BankFeed>) =>
  ({ id: "f", name: "Běžný", connectionId: "c1", lastSyncedAt: null, syncsToday: 0, lastSync: null, ...over }) as BankFeed;
const conn = (id: string, status: BankConnection["status"] = "active") => ({ id, status }) as BankConnection;
const result = (over: Partial<FeedSyncResult>) =>
  ({ feedId: "f", feedName: "Běžný", status: "synced", added: 0, existing: 0, filed: 0, message: "", ...over }) as FeedSyncResult;

describe("relativeTime", () => {
  it("reads like a person would say it", () => {
    expect(relativeTime(ago(20 * 1000), now)).toBe("just now");
    expect(relativeTime(ago(5 * 60 * 1000), now)).toBe("5 min ago");
    expect(relativeTime(ago(3 * H), now)).toBe("3 h ago");
    expect(relativeTime(ago(30 * H), now)).toBe("yesterday");
    expect(relativeTime(ago(80 * H), now)).toBe("3 days ago");
  });
});

describe("feedSyncState", () => {
  it("is nothing for a statements feed", () => {
    expect(feedSyncState(feed({ connectionId: null }), now)).toBeNull();
  });

  it("puts a failed last attempt first, with the bank's reason", () => {
    const state = feedSyncState(
      feed({ lastSyncedAt: ago(30 * H), lastSync: { at: ago(H), trigger: "scheduled", status: "error", added: 0, error: "Bank is down" } }),
      now,
    );
    expect(state).toEqual({ tone: "error", label: "Sync failed 1 h ago", error: "Bank is down" });
  });

  it("is behind after two days without a sync", () => {
    expect(feedSyncState(feed({ lastSyncedAt: ago(3 * H) }), now)?.tone).toBe("ok");
    expect(feedSyncState(feed({ lastSyncedAt: ago(50 * H) }), now)?.tone).toBe("stale");
    expect(feedSyncState(feed({}), now)?.tone).toBe("never");
  });
});

describe("syncOverview", () => {
  it("counts only feeds of active connections", () => {
    const feeds = [
      feed({ id: "a", lastSyncedAt: ago(2 * H) }),
      feed({ id: "b", lastSyncedAt: ago(5 * H), syncsToday: 4 }),
      feed({ id: "c", lastSync: { at: ago(H), trigger: "manual", status: "error", added: 0, error: "x" }, lastSyncedAt: ago(60 * H) }),
      feed({ id: "d", connectionId: "c2" }),
      feed({ id: "e", connectionId: null }),
    ];
    const o = syncOverview(feeds, [conn("c1"), conn("c2", "expired")], now);
    expect(o.liveFeeds).toBe(3);
    expect(o.failing.map((f) => f.id)).toEqual(["c"]);
    expect(o.stale).toEqual([]);
    expect(o.oldestSyncedAt).toBe(ago(60 * H));
    expect(o.syncableToday).toBe(2);
  });
});

describe("describeSyncAll", () => {
  it("adds up what came in and names what didn't work", () => {
    expect(
      describeSyncAll([
        result({ added: 3, filed: 1 }),
        result({ feedName: "Spořicí", added: 1 }),
        result({ feedName: "EUR", status: "skipped", message: "Already read 4 times today." }),
        result({ feedName: "Card", status: "error", message: "The bank didn't answer" }),
      ]),
    ).toBe(
      "2 feeds synced: 4 new rows, 1 filed by rules. Skipped EUR: Already read 4 times today. Card failed: The bank didn't answer",
    );
  });

  it("says when there was nothing to sync", () => {
    expect(describeSyncAll([])).toBe("No connected feeds to sync.");
  });
});
