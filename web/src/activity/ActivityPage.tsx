import { useState } from "react";
import { useAccounts, useActivity, useCategories, useMembers, useTrips } from "../api/hooks";
import type { ActivityEntity, ActivityEntry } from "../api/types";
import { useHousehold } from "../households/HouseholdPages";
import { EmptyState, ErrorText, PageHeader, PageIntro, SkeletonRows, Surface } from "../ui";
import { Button } from "@/components/ui/button";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { cn } from "@/lib/utils";
import { describeAction, describeChanges, type Lookups } from "./describe";

const FILTERS: { value: ActivityEntity | ""; label: string }[] = [
  { value: "", label: "Everything" },
  { value: "expense", label: "Expenses" },
  { value: "income", label: "Income" },
  { value: "asset", label: "Assets" },
  { value: "asset_contribution", label: "Contributions" },
  { value: "asset_valuation", label: "Asset values" },
  { value: "asset_fee", label: "Asset fees" },
  { value: "trip", label: "Trips" },
  { value: "account", label: "Accounts" },
  { value: "category", label: "Categories" },
  { value: "member", label: "Members" },
  { value: "invite", label: "Invites" },
];

/** Entries grouped under their local calendar day, keeping the newest-first order. */
function byDay(entries: ActivityEntry[]): [string, ActivityEntry[]][] {
  const days = new Map<string, ActivityEntry[]>();
  for (const entry of entries) {
    const day = new Date(entry.at).toLocaleDateString(undefined, { weekday: "long", day: "numeric", month: "long", year: "numeric" });
    days.set(day, [...(days.get(day) ?? []), entry]);
  }
  return [...days];
}

export function ActivityPage() {
  const household = useHousehold();
  const [entity, setEntity] = useState<ActivityEntity | "">("");
  const activity = useActivity(household.id, entity || undefined);
  const lookups: Lookups = {
    currency: household.currency,
    categories: useCategories(household.id).data ?? [],
    accounts: useAccounts(household.id).data ?? [],
    members: useMembers(household.id).data ?? [],
    trips: useTrips(household.id).data ?? [],
  };
  const entries = activity.data?.pages.flatMap((p) => p.entries) ?? [];

  return (
    <>
      <PageHeader title="Activity">
        <NativeSelect aria-label="Show" value={entity} onChange={(e) => setEntity(e.target.value as ActivityEntity | "")}>
          {FILTERS.map((f) => (
            <NativeSelectOption key={f.value} value={f.value}>
              {f.label}
            </NativeSelectOption>
          ))}
        </NativeSelect>
      </PageHeader>
      <PageIntro>Every change anyone in the household made, newest first.</PageIntro>

      <Surface>
        <ErrorText error={activity.error} className="p-4" />
        {activity.isPending && <SkeletonRows />}
        {activity.data && entries.length === 0 && (
          <EmptyState title="Nothing here yet">Changes show up here as soon as someone makes them.</EmptyState>
        )}
        {byDay(entries).map(([day, dayEntries]) => (
          <div key={day} className="border-b last:border-b-0">
            <h2 className="bg-muted/50 px-4 py-2 text-xs font-medium text-muted-foreground">{day}</h2>
            <ul className="divide-y">
              {dayEntries.map((entry) => (
                <ActivityRow key={entry.id} entry={entry} lookups={lookups} />
              ))}
            </ul>
          </div>
        ))}
        {activity.hasNextPage && (
          <div className="flex justify-center border-t p-3">
            <Button variant="outline" onClick={() => activity.fetchNextPage()} disabled={activity.isFetchingNextPage}>
              {activity.isFetchingNextPage ? "Loading…" : "Show older"}
            </Button>
          </div>
        )}
      </Surface>
    </>
  );
}

function ActivityRow({ entry, lookups }: { entry: ActivityEntry; lookups: Lookups }) {
  const [lead, name, tail] = describeAction(entry);
  const changes = describeChanges(entry, lookups);
  const time = new Date(entry.at).toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" });
  return (
    <li className="flex gap-4 px-4 py-3 text-sm">
      <time className="amount w-12 shrink-0 text-muted-foreground" dateTime={entry.at}>
        {time}
      </time>
      <div className="flex min-w-0 flex-col gap-1">
        <p className={cn(entry.action === "delete" && "text-muted-foreground")}>
          {lead && `${lead} `}
          <span className="font-medium text-foreground">{name}</span>
          {tail && ` ${tail}`}
        </p>
        {changes.length > 0 && (
          <ul className="flex flex-col gap-0.5 text-xs">
            {changes.map((c) => (
              <li key={c.label}>
                <span className="text-muted-foreground">{c.label}</span>{" "}
                {c.before !== undefined && (
                  <>
                    <del className="text-muted-foreground">{c.before}</del> →{" "}
                  </>
                )}
                {c.after}
              </li>
            ))}
          </ul>
        )}
      </div>
    </li>
  );
}
