import type { ReactNode } from "react";
import { Link, useSearchParams } from "react-router";
import { useCategories, useMembers, useOverview } from "../api/hooks";
import type { Category, FeedFreshness, Freshness, MonthFlow, NetWorth, Overview } from "../api/types";
import { categoryTone, Chip } from "../chips";
import { useHousehold } from "../households/HouseholdPages";
import { formatDate, formatMonth, shiftDays, todayIso } from "../lib/dates";
import { formatAmount } from "../lib/money";
import { MonthTitle, useMonthParam, useMonthShortcuts } from "../month";
import { monthPeriod, writePeriod } from "../reports/period";
import { rankSlices, ShareBar } from "../shares";
import { ErrorText, Money, PageHeader, SkeletonRows, Summary, Surface } from "../ui";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { cn } from "@/lib/utils";
import { MoneyFlow } from "./MoneyFlow";
import { buildFlow } from "./sankey";

/** "Mar" for "2026-03", in the user's locale. */
function shortMonth(month: string): string {
  const [year, m] = month.split("-").map(Number);
  return new Date(year, m - 1, 1).toLocaleDateString(undefined, { month: "short" });
}

export function categoryName(categories: Category[], id: string | null | undefined): string {
  if (!id) return "Uncategorized";
  return categories.find((c) => c.id === id)?.name ?? "Deleted category";
}

/** Saved = income − spending; investing is part of what was saved, not of what was spent. */
const saved = (flow: Pick<MonthFlow, "incomeMinor" | "spendingMinor">) => flow.incomeMinor - flow.spendingMinor;

export function OverviewPage() {
  const household = useHousehold();
  const { month, goTo } = useMonthParam();
  const members = useMembers(household.id).data ?? [];
  const [params, setParams] = useSearchParams();
  // ?whose=joint or a member's id; anything else (a member who left) shows everyone.
  const whoseParam = params.get("whose");
  const whose = whoseParam === "joint" || members.some((m) => m.userId === whoseParam) ? whoseParam! : undefined;
  const setWhose = (value: string) =>
    setParams((current) => {
      const next = new URLSearchParams(current);
      if (value === "all") next.delete("whose");
      else next.set("whose", value);
      return next;
    });
  const overview = useOverview(household.id, month, todayIso(), whose);
  const categories = useCategories(household.id).data ?? [];
  useMonthShortcuts(month, goTo, () => {});

  return (
    <>
      <PageHeader title={<MonthTitle month={month} goTo={goTo} />}>
        {members.length > 1 && (
          <Tabs value={whose ?? "all"} onValueChange={setWhose}>
            <TabsList aria-label="Whose">
              <TabsTrigger value="all">Everyone</TabsTrigger>
              <TabsTrigger value="joint">Joint</TabsTrigger>
              {members.map((m) => (
                <TabsTrigger key={m.userId} value={m.userId}>
                  {m.displayName}
                </TabsTrigger>
              ))}
            </TabsList>
          </Tabs>
        )}
      </PageHeader>
      <ErrorText error={overview.error} />
      {overview.isPending && (
        <Surface>
          <SkeletonRows />
        </Surface>
      )}
      {overview.data && (
        <OverviewBody
          data={overview.data}
          categories={categories}
          currency={household.currency}
          inboxPath={`/h/${household.id}/inbox`}
          recurringPath={`/h/${household.id}/recurring`}
          reportsPath={`/h/${household.id}/reports`}
          whose={whose}
        />
      )}
    </>
  );
}

/** A signed amount with its currency. */
function Signed({ minor, currency }: { minor: number; currency: string }) {
  return (
    <Money currency={currency}>
      {minor < 0 ? "−" : ""}
      {formatAmount(Math.abs(minor), currency)}
    </Money>
  );
}

/** One overview card: a title, an optional line under it, and its contents. */
function OverviewCard({
  title,
  subtitle,
  className,
  children,
}: {
  title: string;
  subtitle?: ReactNode;
  className?: string;
  children: ReactNode;
}) {
  return (
    <Card className={cn("gap-4", className)}>
      <CardHeader>
        <CardTitle>
          <h2 className="font-medium">{title}</h2>
        </CardTitle>
        {subtitle && <CardDescription>{subtitle}</CardDescription>}
      </CardHeader>
      <CardContent>{children}</CardContent>
    </Card>
  );
}

/** Name on the left, figure on the right, one per row. */
function FigureList({ rows }: { rows: { key: string; name: ReactNode; detail?: ReactNode; figure: ReactNode }[] }) {
  return (
    <ul className="flex flex-col divide-y text-sm">
      {rows.map((r) => (
        <li key={r.key} className="flex items-baseline justify-between gap-3 py-2 first:pt-0 last:pb-0">
          <span className="min-w-0">
            <span className="block truncate">{r.name}</span>
            {r.detail && <span className="block text-xs text-muted-foreground">{r.detail}</span>}
          </span>
          <span className="amount flex items-center gap-2">{r.figure}</span>
        </li>
      ))}
    </ul>
  );
}

const Empty = ({ children }: { children: ReactNode }) => <p className="text-sm text-muted-foreground">{children}</p>;

function OverviewBody({
  data,
  categories,
  currency,
  inboxPath,
  recurringPath,
  reportsPath,
  whose,
}: {
  data: Overview;
  categories: Category[];
  currency: string;
  inboxPath: string;
  recurringPath: string;
  reportsPath: string;
  /** Joint or one member's figures (net worth and bank data stay the household's); undefined for everyone. */
  whose: string | undefined;
}) {
  const filtered = whose !== undefined;
  const { thisMonth, typicalMonth } = data;
  const inProgress = data.daysElapsed > 0 && data.daysElapsed < data.daysInMonth;
  const pace = inProgress ? Math.round((thisMonth.spendingMinor / data.daysElapsed) * data.daysInMonth) : null;
  const rate = thisMonth.incomeMinor > 0 ? Math.round((saved(thisMonth) / thisMonth.incomeMinor) * 100) : null;
  const categorySlices = rankSlices(
    new Map(data.topCategories.map((c) => [categoryName(categories, c.categoryId), c.amountMinor])),
    new Map(data.topCategories.map((c) => [categoryName(categories, c.categoryId), categoryTone(categories, c.categoryId)])),
  );
  const flow = buildFlow(
    { ...data, investedMinor: thisMonth.investedMinor },
    (id) => categoryName(categories, id),
    (id) => {
      const tone = categoryTone(categories, id);
      return tone === "neutral" ? "chart-3" : tone;
    },
  );
  const categoryHref = (categoryId: string) => {
    const params = new URLSearchParams();
    writePeriod(params, monthPeriod(data.month));
    params.set("category", categoryId || "none");
    if (whose) params.set("whose", whose);
    return `${reportsPath}?${params}`;
  };
  const subscriptionTotal = data.subscriptions.reduce((sum, s) => sum + s.monthlyMinor, 0);

  return (
    <>
      <div className="flex flex-col gap-5">
        <Summary
          items={[
            {
              label: `Spent${inProgress ? ` · ${data.daysElapsed} of ${data.daysInMonth} days` : ""}`,
              value: <Signed minor={thisMonth.spendingMinor} currency={currency} />,
            },
            { label: "Income", value: <Signed minor={thisMonth.incomeMinor} currency={currency} /> },
            { label: `Saved${rate !== null ? ` · ${rate}%` : ""}`, value: <Signed minor={saved(thisMonth)} currency={currency} /> },
            { label: "Invested", value: <Signed minor={thisMonth.investedMinor} currency={currency} /> },
          ]}
        />
        {(pace !== null || typicalMonth) && (
          <p className="max-w-prose text-sm text-muted-foreground">
            {pace !== null && (
              <>
                On pace to spend <span className="amount text-foreground">{formatAmount(pace, currency)}</span> by the end of the
                month.{" "}
              </>
            )}
            {typicalMonth && (
              <>
                A typical month over the last {typicalMonth.monthCount === 1 ? "month" : `${typicalMonth.monthCount} months`}:{" "}
                <span className="amount text-foreground">{formatAmount(typicalMonth.incomeMinor, currency)}</span> in,{" "}
                <span className="amount text-foreground">{formatAmount(typicalMonth.spendingMinor, currency)}</span> spent,{" "}
                <span className="amount text-foreground">{formatAmount(saved(typicalMonth), currency)}</span> saved
                {typicalMonth.outlierMonths.length > 0 &&
                  ` (${typicalMonth.outlierMonths.map((m) => formatMonth(m)).join(", ")} left out as unusually expensive)`}
                .
              </>
            )}
          </p>
        )}
        <PendingNote freshness={data.freshness} month={data.month} inboxPath={inboxPath} />
        <ShareBar label="Spending by category" slices={categorySlices} />
      </div>

      <div className="grid gap-4 lg:grid-cols-2 2xl:grid-cols-3">
        {flow.totalMinor > 0 && (
          <OverviewCard
            title="Where the money went"
            subtitle={`${formatMonth(data.month)}'s income by source, and what it paid for`}
            className="lg:col-span-2 2xl:col-span-3"
          >
            <MoneyFlow flow={flow} currency={currency} categoryHref={categoryHref} />
          </OverviewCard>
        )}

        <OverviewCard title="Income and spending" className="lg:col-span-2 2xl:col-span-3">
          <FlowChart history={data.history} currency={currency} />
        </OverviewCard>

        <NetWorthCard netWorth={data.netWorth} currency={currency} household={filtered} />
        <FreshnessCard feeds={data.freshness.feeds} />

        <OverviewCard
          title="Unusual spending"
          subtitle={
            <>
              {formatMonth(data.anomalyMonth)} against its usual month over the year before
              {data.anomalyMonth === data.month && inProgress && ", scaled to the days so far"}
            </>
          }
        >
          {data.anomalies.length === 0 ? (
            <Empty>Nothing out of the ordinary.</Empty>
          ) : (
            <FigureList
              rows={data.anomalies.slice(0, 6).map((a) => ({
                key: a.categoryId ?? "none",
                name: categoryName(categories, a.categoryId),
                detail: `usually ${formatAmount(a.baselineMinor, currency)}`,
                figure: (
                  <>
                    {formatAmount(a.spentMinor, currency)}
                    <Badge variant="secondary" className="amount">
                      {a.ratio >= 1 ? `+${Math.round((a.ratio - 1) * 100)}%` : `−${Math.round((1 - a.ratio) * 100)}%`}
                    </Badge>
                  </>
                ),
              }))}
            />
          )}
        </OverviewCard>

        <OverviewCard
          title="Subscriptions"
          subtitle={
            data.subscriptions.length > 0 ? (
              <>
                Charged most months by a steady amount: <span className="amount">{formatAmount(subscriptionTotal, currency)}</span> a
                month, <span className="amount">{formatAmount(subscriptionTotal * 12, currency)}</span> a year
              </>
            ) : (
              "Merchants charged by a steady amount in 3 of the last 6 months"
            )
          }
        >
          {data.subscriptions.length === 0 ? (
            <Empty>No recurring payments found.</Empty>
          ) : (
            <FigureList
              rows={data.subscriptions.slice(0, 10).map((s) => ({
                key: s.merchant,
                name: s.merchant,
                detail: categoryName(categories, s.categoryId),
                figure: formatAmount(s.monthlyMinor, currency),
              }))}
            />
          )}
          <Link to={recurringPath} className="mt-4 inline-block text-sm font-medium underline underline-offset-4">
            Track recurring payments
          </Link>
        </OverviewCard>

        <TopList title="Top merchants" rows={data.topMerchants} currency={currency} empty="No spending this month." />
        <TopList title="Top income" rows={data.topSources} currency={currency} empty="No income this month." />
      </div>
    </>
  );
}

function TopList({
  title,
  rows,
  currency,
  empty,
}: {
  title: string;
  rows: { name: string; amountMinor: number }[];
  currency: string;
  empty: string;
}) {
  return (
    <OverviewCard title={title}>
      {rows.length === 0 ? (
        <Empty>{empty}</Empty>
      ) : (
        <FigureList rows={rows.map((r) => ({ key: r.name, name: r.name, figure: formatAmount(r.amountMinor, currency) }))} />
      )}
    </OverviewCard>
  );
}

const CHART_HEIGHT = 140;

/** Income and spending side by side per month, on one shared scale. Hover a month for its figures. */
function FlowChart({ history, currency }: { history: MonthFlow[]; currency: string }) {
  const max = Math.max(1, ...history.flatMap((m) => [m.incomeMinor, m.spendingMinor]));
  const height = (value: number) => (Math.max(0, value) / max) * CHART_HEIGHT;
  return (
    <figure className="flex flex-col gap-3">
      <ul className="flex gap-5 text-sm" aria-hidden="true">
        <li className="flex items-center gap-2">
          <span className="size-2.5 rounded-full bg-chart-4" />
          Income
        </li>
        <li className="flex items-center gap-2">
          <span className="size-2.5 rounded-full bg-chart-1" />
          Spent
        </li>
      </ul>
      <div className="flex items-end gap-1 border-b sm:gap-2" style={{ height: CHART_HEIGHT }}>
        {history.map((m) => {
          const label = `${formatMonth(m.month)}: income ${formatAmount(m.incomeMinor, currency)}, spent ${formatAmount(m.spendingMinor, currency)} ${currency}`;
          return (
            <div key={m.month} className="flex h-full flex-1 items-end justify-center gap-0.5 rounded-sm hover:bg-muted" title={label} aria-label={label} role="img">
              <span className="w-full max-w-4 rounded-t-sm bg-chart-4" style={{ height: height(m.incomeMinor) }} />
              <span className="w-full max-w-4 rounded-t-sm bg-chart-1" style={{ height: height(m.spendingMinor) }} />
            </div>
          );
        })}
      </div>
      <div className="flex gap-1 text-center text-xs text-muted-foreground sm:gap-2" aria-hidden="true">
        {history.map((m) => (
          <span key={m.month} className="flex-1">
            {shortMonth(m.month)}
          </span>
        ))}
      </div>
    </figure>
  );
}

/** Bank rows not filed yet are left out of the figures: say so when any are from this month. */
function PendingNote({ freshness, month, inboxPath }: { freshness: Freshness; month: string; inboxPath: string }) {
  if (freshness.pendingInMonth === 0) return null;
  const rows = (n: number) => `${n} bank ${n === 1 ? "row" : "rows"}`;
  return (
    <p className="max-w-prose text-sm text-muted-foreground">
      {rows(freshness.pendingInMonth)} from {formatMonth(month)} still{" "}
      {freshness.pendingInMonth === 1 ? "waits" : "wait"} in the{" "}
      <Link to={inboxPath} className="font-medium text-foreground underline underline-offset-4">
        Inbox
      </Link>{" "}
      and {freshness.pendingInMonth === 1 ? "isn't" : "aren't"} counted yet
      {freshness.pendingRows > freshness.pendingInMonth && ` (${freshness.pendingRows} in all)`}.
    </p>
  );
}

/** "10 months" for a runway, "over 10 years" when it's long enough not to matter. */
function runwayLabel(months: number): string {
  if (months >= 120) return "over 10 years";
  if (months >= 24) return `${Math.floor(months / 12)} years`;
  if (months < 1) return `${Math.max(0, Math.round(months * 30))} days`;
  const whole = Math.floor(months);
  return `${whole} ${whole === 1 ? "month" : "months"}`;
}

function NetWorthCard({ netWorth, currency, household }: { netWorth: NetWorth; currency: string; household: boolean }) {
  const nothing = netWorth.assetsMinor === 0 && netWorth.liabilitiesMinor === 0 && netWorth.accountsRead === 0;
  const isToday = netWorth.on === todayIso();
  const when = isToday ? "Today" : `On ${formatDate(netWorth.on)}`;
  return (
    <OverviewCard title="Net worth" subtitle={household ? `${when}, the whole household's` : when}>
      {nothing ? (
        <Empty>No assets, debts or bank balances yet.</Empty>
      ) : (
        <div className="flex flex-col gap-4">
          <p className="text-2xl font-medium">
            <Signed minor={netWorth.netMinor} currency={currency} />
          </p>
          <FigureList
            rows={[
              {
                key: "accounts",
                name: "In accounts",
                detail:
                  netWorth.accountsRead === 0
                    ? "No balance read from a bank yet"
                    : `Bank balances of ${netWorth.accountsRead} ${netWorth.accountsRead === 1 ? "account" : "accounts"}, less what assets already count`,
                figure: <Signed minor={netWorth.accountsMinor} currency={currency} />,
              },
              { key: "assets", name: "Assets", figure: <Signed minor={netWorth.assetsMinor} currency={currency} /> },
              { key: "owed", name: "Owed", figure: <Signed minor={netWorth.liabilitiesMinor} currency={currency} /> },
              {
                key: "liquid",
                name: "Money at hand",
                detail:
                  netWorth.runwayMonths !== null
                    ? `Lasts ${runwayLabel(netWorth.runwayMonths)} at a typical month's ${household ? "household " : ""}spending, with no income`
                    : "Current, savings and cash accounts, and savings",
                figure: <Signed minor={netWorth.liquidMinor} currency={currency} />,
              },
            ]}
          />
        </div>
      )}
    </OverviewCard>
  );
}

/** A feed is behind when nothing came in for a week: a lapsed sync, or statements not uploaded lately. */
const STALE_DAYS = 7;

function feedStatus(feed: FeedFreshness, today: string): { detail: string; stale: boolean } {
  const synced = feed.lastSyncedAt ? formatDate(feed.lastSyncedAt.slice(0, 10)) : null;
  const expired = feed.consentExpiresAt !== null && feed.consentExpiresAt.slice(0, 10) < today;
  if (feed.live) {
    if (expired) return { detail: "Bank access expired: reconnect it", stale: true };
    const syncedLately = feed.lastSyncedAt !== null && feed.lastSyncedAt.slice(0, 10) >= shiftDays(today, -2);
    return { detail: synced ? `Synced ${synced}` : "Not synced yet", stale: !syncedLately };
  }
  const behind = feed.lastDate === null || feed.lastDate < shiftDays(today, -STALE_DAYS);
  return { detail: "From statements", stale: behind };
}

/** Lists only the feeds that are behind; the rest are summed up in one line. */
function FreshnessCard({ feeds }: { feeds: FeedFreshness[] }) {
  const today = todayIso();
  const behind = feeds.map((feed) => ({ feed, status: feedStatus(feed, today) })).filter((f) => f.status.stale);
  const plural = (n: number) => `${n} ${n === 1 ? "feed" : "feeds"}`;
  return (
    <OverviewCard
      title="Bank data"
      subtitle={
        feeds.length === 0
          ? undefined
          : behind.length === 0
            ? `All ${plural(feeds.length)} up to date`
            : `${plural(behind.length)} of ${feeds.length} behind`
      }
    >
      {feeds.length === 0 ? (
        <Empty>No bank feeds yet: everything here was entered by hand.</Empty>
      ) : behind.length === 0 ? (
        <Empty>Every live feed synced in the last two days, and statements reach the last week.</Empty>
      ) : (
        <FigureList
          rows={behind.map(({ feed, status }) => ({
            key: feed.feedId,
            name: feed.name,
            detail: status.detail,
            figure: (
              <>
                <Chip tone="warning">Behind</Chip>
                {feed.lastDate ? formatDate(feed.lastDate) : "No rows"}
              </>
            ),
          }))}
        />
      )}
    </OverviewCard>
  );
}
