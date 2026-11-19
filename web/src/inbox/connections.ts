import type { BankConnection, BankFeed } from "../api/types";

const EXPIRY_SOON_DAYS = 14;

/** Days remaining until consent expires (negative if already past), or null if unknown. */
export function daysUntilExpiry(conn: BankConnection, now = Date.now()): number | null {
  if (!conn.consentExpiresAt) return null;
  const ms = Date.parse(conn.consentExpiresAt);
  if (Number.isNaN(ms)) return null;
  return Math.ceil((ms - now) / (1000 * 60 * 60 * 24));
}

/** Warning text when a connection needs attention (lapsed or expiring within 14 days), else null. */
export function connectionWarning(conn: BankConnection, now = Date.now()): string | null {
  if (conn.status === "expired") return "Consent expired — reconnect the bank to keep syncing.";
  if (conn.status === "revoked") return "Consent was withdrawn — reconnect the bank to keep syncing.";
  if (conn.status === "failed") return conn.lastError || "Connection failed — try connecting again.";
  if (conn.status === "active") {
    const days = daysUntilExpiry(conn, now);
    if (days !== null && days <= 0) return "Consent expired — reconnect the bank to keep syncing.";
    if (days !== null && days <= EXPIRY_SOON_DAYS) {
      return days === 1 ? "Consent expires tomorrow — reconnect soon." : `Consent expires in ${days} days.`;
    }
  }
  return null;
}

/**
 * Where a new feed of [accountId] starts: the day after the newest row already read into the account, so its history
 * doesn't come in twice, and never before the household's balances start ([start], `debtsFrom`), before which the old
 * ledger is the record. Empty when neither applies.
 */
export function importFromAfter(feeds: BankFeed[], accountId: string, start?: string | null): string {
  const latest = feeds
    .filter((f) => f.accountId === accountId && f.lastDate)
    .map((f) => f.lastDate!)
    .sort()
    .at(-1);
  let after = "";
  if (latest) {
    const next = new Date(`${latest}T00:00:00Z`);
    next.setUTCDate(next.getUTCDate() + 1);
    after = next.toISOString().slice(0, 10);
  }
  return start && start > after ? start : after;
}
