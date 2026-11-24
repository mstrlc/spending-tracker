import type { BankConnection, BankFeed, FeedSyncResult } from "../api/types";

/** How often the bank lets one feed be read in a day (`BANK_SYNCS_PER_DAY` on the server). */
export const SYNCS_PER_DAY = 4;

/** A live feed not synced for this long is behind: the daily run should have read it (as on the Overview). */
const STALE_MS = 2 * 24 * 60 * 60 * 1000;

const MINUTE = 60 * 1000;
const HOUR = 60 * MINUTE;

/** "just now", "5 min ago", "3 h ago", "yesterday", "4 days ago". */
export function relativeTime(iso: string, now = Date.now()): string {
  const ms = now - Date.parse(iso);
  if (ms < MINUTE) return "just now";
  if (ms < HOUR) return `${Math.floor(ms / MINUTE)} min ago`;
  if (ms < 24 * HOUR) return `${Math.floor(ms / HOUR)} h ago`;
  const days = Math.floor(ms / (24 * HOUR));
  return days === 1 ? "yesterday" : `${days} days ago`;
}

/** "today at 06:00", "tomorrow at 06:00", or the date for anything further. */
export function upcomingTime(iso: string, now = new Date()): string {
  const at = new Date(iso);
  const time = at.toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" });
  const dayDiff = Math.round(
    (new Date(at.getFullYear(), at.getMonth(), at.getDate()).getTime() -
      new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()) /
      (24 * HOUR),
  );
  if (dayDiff === 0) return `today at ${time}`;
  if (dayDiff === 1) return `tomorrow at ${time}`;
  return `${at.toLocaleDateString(undefined, { day: "numeric", month: "short" })} at ${time}`;
}

export type SyncTone = "ok" | "stale" | "error" | "never";

export interface FeedSyncState {
  tone: SyncTone;
  /** "Synced 2 h ago", "Sync failed 10 min ago", "Never synced". */
  label: string;
  /** Why the last attempt failed, for an error. */
  error?: string;
}

/** Where a live feed's syncing stands; null for a statements feed. */
export function feedSyncState(feed: BankFeed, now = Date.now()): FeedSyncState | null {
  if (!feed.connectionId) return null;
  const last = feed.lastSync;
  if (last?.status === "error") {
    return { tone: "error", label: `Sync failed ${relativeTime(last.at, now)}`, error: last.error };
  }
  if (!feed.lastSyncedAt) return { tone: "never", label: "Never synced" };
  const tone = now - Date.parse(feed.lastSyncedAt) > STALE_MS ? "stale" : "ok";
  return { tone, label: `Synced ${relativeTime(feed.lastSyncedAt, now)}` };
}

export interface SyncOverview {
  /** Live feeds: those read through an active connection. */
  liveFeeds: number;
  failing: BankFeed[];
  stale: BankFeed[];
  /** The oldest successful sync among live feeds that have one: everything is at least this fresh. */
  oldestSyncedAt: string | null;
  /** Live feeds that can still be read today. */
  syncableToday: number;
}

/** The household's live feeds at a glance, for the sync status line. */
export function syncOverview(feeds: BankFeed[], connections: BankConnection[], now = Date.now()): SyncOverview {
  const active = new Set(connections.filter((c) => c.status === "active").map((c) => c.id));
  const live = feeds.filter((f) => f.connectionId && active.has(f.connectionId));
  const states = live.map((f) => [f, feedSyncState(f, now)!] as const);
  const synced = live.map((f) => f.lastSyncedAt).filter((t): t is string => !!t).sort();
  return {
    liveFeeds: live.length,
    failing: states.filter(([, s]) => s.tone === "error").map(([f]) => f),
    stale: states.filter(([, s]) => s.tone === "stale" || s.tone === "never").map(([f]) => f),
    oldestSyncedAt: synced[0] ?? null,
    syncableToday: live.filter((f) => f.syncsToday < SYNCS_PER_DAY).length,
  };
}

/** What a "Sync all" did, in a sentence or two: totals, then what didn't work and why. */
export function describeSyncAll(results: FeedSyncResult[]): string {
  if (results.length === 0) return "No connected feeds to sync.";
  const synced = results.filter((r) => r.status === "synced");
  const added = synced.reduce((sum, r) => sum + r.added, 0);
  const filed = synced.reduce((sum, r) => sum + r.filed, 0);
  const parts: string[] = [];
  if (synced.length > 0) {
    let line = `${synced.length} ${synced.length === 1 ? "feed" : "feeds"} synced: ${added} new ${added === 1 ? "row" : "rows"}`;
    if (filed > 0) line += `, ${filed} filed by rules`;
    parts.push(`${line}.`);
  }
  const skipped = results.filter((r) => r.status === "skipped");
  if (skipped.length > 0) {
    parts.push(`Skipped ${skipped.map((r) => r.feedName).join(", ")}: ${skipped[0].message}`);
  }
  for (const r of results.filter((r) => r.status === "error")) parts.push(`${r.feedName} failed: ${r.message}`);
  return parts.join(" ");
}
