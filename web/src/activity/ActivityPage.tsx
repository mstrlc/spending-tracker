import { useState } from "react";
import { useAccounts, useActivity, useCategories, useMembers } from "../api/hooks";
import type { ActivityEntity, ActivityEntry } from "../api/types";
import { useHousehold } from "../households/HouseholdPages";
import { ErrorText, PageHeader, SkeletonRows } from "../ui";
import { describeAction, describeChanges, type Lookups } from "./describe";

const FILTERS: { value: ActivityEntity | ""; label: string }[] = [
  { value: "", label: "Everything" },
  { value: "expense", label: "Expenses" },
  { value: "income", label: "Income" },
  { value: "asset", label: "Assets" },
  { value: "asset_contribution", label: "Contributions" },
  { value: "asset_valuation", label: "Asset values" },
  { value: "asset_fee", label: "Asset fees" },
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
  };
  const entries = activity.data?.pages.flatMap((p) => p.entries) ?? [];

  return (
    <>
      <PageHeader title="Activity">
        <select aria-label="Show" value={entity} onChange={(e) => setEntity(e.target.value as ActivityEntity | "")}>
          {FILTERS.map((f) => (
            <option key={f.value} value={f.value}>
              {f.label}
            </option>
          ))}
        </select>
      </PageHeader>
      <p className="page-intro muted">Every change anyone in the household made, newest first.</p>

      <section className="surface">
        <ErrorText error={activity.error} />
        {activity.isPending && <SkeletonRows />}
        {activity.data && entries.length === 0 && (
          <div className="empty">
            <p className="empty-title">Nothing here yet</p>
            <p className="muted">Changes show up here as soon as someone makes them.</p>
          </div>
        )}
        {byDay(entries).map(([day, dayEntries]) => (
          <div key={day} className="activity-day">
            <h2 className="activity-day-title">{day}</h2>
            <ul className="activity-list">
              {dayEntries.map((entry) => (
                <ActivityRow key={entry.id} entry={entry} lookups={lookups} />
              ))}
            </ul>
          </div>
        ))}
        {activity.hasNextPage && (
          <div className="activity-more">
            <button className="secondary" onClick={() => activity.fetchNextPage()} disabled={activity.isFetchingNextPage}>
              {activity.isFetchingNextPage ? "Loading…" : "Show older"}
            </button>
          </div>
        )}
      </section>
    </>
  );
}

function ActivityRow({ entry, lookups }: { entry: ActivityEntry; lookups: Lookups }) {
  const [lead, name, tail] = describeAction(entry);
  const changes = describeChanges(entry, lookups);
  const time = new Date(entry.at).toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" });
  return (
    <li className={entry.action === "delete" ? "activity-row is-delete" : "activity-row"}>
      <time className="activity-time" dateTime={entry.at}>
        {time}
      </time>
      <div className="activity-body">
        <p>
          {lead && `${lead} `}
          <span className="strong">{name}</span>
          {tail && ` ${tail}`}
        </p>
        {changes.length > 0 && (
          <ul className="activity-changes">
            {changes.map((c) => (
              <li key={c.label}>
                <span className="muted">{c.label}</span>{" "}
                {c.before !== undefined && (
                  <>
                    <del>{c.before}</del> →{" "}
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
