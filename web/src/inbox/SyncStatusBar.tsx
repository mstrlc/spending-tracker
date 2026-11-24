import { useEffect, useState } from "react";
import { CircleAlertIcon, CircleCheckIcon, ClockAlertIcon, RefreshCwIcon } from "lucide-react";
import { useBankConnections, useBankFeeds, useSyncAllBanks } from "../api/hooks";
import { useHousehold } from "../households/HouseholdPages";
import { ErrorText } from "../ui";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { describeSyncAll, relativeTime, syncOverview, upcomingTime } from "./syncStatus";

/** The current time, ticking every [ms] so "5 min ago" stays true while the page is open. */
function useNow(ms = 60_000) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), ms);
    return () => window.clearInterval(id);
  }, [ms]);
  return now;
}

/** The feeds' names when there are a few to name (two people's feeds can share one); a long list says nothing the count doesn't. */
function names(feeds: { name: string }[]) {
  const unique = [...new Set(feeds.map((f) => f.name))];
  return unique.length <= 3 ? unique.join(", ") : null;
}

/**
 * How the household's live bank feeds stand: all synced, some behind, or some failing, when the next daily sync runs,
 * and a button that syncs every connected bank at once. Nothing when no bank is connected.
 */
export function SyncStatusBar({ onDetails, className }: { onDetails?: () => void; className?: string }) {
  const household = useHousehold();
  const feeds = useBankFeeds(household.id);
  const connections = useBankConnections(household.id);
  const syncAll = useSyncAllBanks(household.id);
  const [result, setResult] = useState<string | null>(null);
  const now = useNow();

  const overview = syncOverview(feeds.data ?? [], connections.data?.connections ?? [], now);
  if (!connections.data?.configured || overview.liveFeeds === 0) return null;

  const { liveFeeds, failing, stale, oldestSyncedAt, syncableToday } = overview;
  const plural = (n: number, word: string) => `${n} ${word}${n === 1 ? "" : "s"}`;

  let tone: "ok" | "warning" | "danger" = "ok";
  let headline: string;
  if (syncAll.isPending) {
    headline = `Syncing ${plural(syncableToday, "feed")}…`;
  } else if (failing.length > 0) {
    tone = "danger";
    headline = `${plural(failing.length, "feed")} failed to sync`;
  } else if (stale.length > 0) {
    tone = "warning";
    headline = `${plural(stale.length, "feed")} behind`;
  } else {
    headline = liveFeeds === 1 ? "Bank feed up to date" : `All ${liveFeeds} bank feeds up to date`;
  }

  const details = [
    failing.length > 0 ? names(failing) : stale.length > 0 ? names(stale) : null,
    oldestSyncedAt && failing.length === 0 && stale.length === 0
      ? `last synced ${liveFeeds > 1 ? "at least " : ""}${relativeTime(oldestSyncedAt, now)}`
      : null,
    connections.data.nextSyncAt ? `next daily sync ${upcomingTime(connections.data.nextSyncAt, new Date(now))}` : null,
    syncableToday < liveFeeds ? `${plural(liveFeeds - syncableToday, "feed")} at today's limit` : null,
  ].filter(Boolean);
  const detailText = details.join(" · ");

  const Icon = syncAll.isPending ? RefreshCwIcon : tone === "ok" ? CircleCheckIcon : tone === "danger" ? CircleAlertIcon : ClockAlertIcon;

  function run() {
    setResult(null);
    syncAll.mutate(undefined, { onSuccess: (res) => setResult(describeSyncAll(res.feeds)) });
  }

  return (
    <section aria-label="Bank sync" className={cn("flex flex-col gap-2 rounded-lg border px-3 py-2.5", className)}>
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
        <Icon
          aria-hidden="true"
          className={cn(
            "size-4 shrink-0",
            syncAll.isPending && "animate-spin text-muted-foreground",
            !syncAll.isPending && tone === "ok" && "text-(--tone-income)",
            !syncAll.isPending && tone === "warning" && "text-(--tone-warning)",
            !syncAll.isPending && tone === "danger" && "text-destructive",
          )}
        />
        <div className="min-w-40 flex-1">
          <div className="text-sm font-medium" role="status">
            {headline}
          </div>
          {detailText && <div className="truncate text-xs text-muted-foreground" title={detailText}>{detailText}</div>}
        </div>
        <div className="ml-auto flex items-center gap-1">
        {onDetails && (failing.length > 0 || stale.length > 0) && (
          <Button type="button" variant="ghost" size="sm" onClick={onDetails}>
            Details
          </Button>
        )}
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={run}
          disabled={syncAll.isPending || syncableToday === 0}
          title={syncableToday === 0 ? "Every feed has been read 4 times today, the bank's limit" : "Read new rows and balances from every connected bank"}
        >
          <RefreshCwIcon className={cn(syncAll.isPending && "animate-spin")} />
          {syncAll.isPending ? "Syncing…" : "Sync all banks"}
        </Button>
        </div>
      </div>
      <ErrorText error={syncAll.error} />
      {result && (
        <p className="rounded-md bg-muted px-3 py-2 text-xs" role="status">
          {result}
        </p>
      )}
    </section>
  );
}
