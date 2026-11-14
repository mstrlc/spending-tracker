import type { ReactNode } from "react";
import { useCategories, useOverview } from "../api/hooks";
import type { Category, MonthFlow, Overview } from "../api/types";
import { useHousehold } from "../households/HouseholdPages";
import { formatMonth, todayIso } from "../lib/dates";
import { formatAmount } from "../lib/money";
import { MonthTitle, useMonthParam, useMonthShortcuts } from "../month";
import { rankSlices, ShareBar } from "../shares";
import { ErrorText, Money, PageHeader, SkeletonRows, Summary, Surface } from "../ui";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { cn } from "@/lib/utils";

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
  const overview = useOverview(household.id, month, todayIso());
  const categories = useCategories(household.id).data ?? [];
  useMonthShortcuts(month, goTo, () => {});

  return (
    <>
      <PageHeader title={<MonthTitle month={month} goTo={goTo} />} />
      <ErrorText error={overview.error} />
      {overview.isPending && (
        <Surface>
          <SkeletonRows />
        </Surface>
      )}
      {overview.data && <OverviewBody data={overview.data} categories={categories} currency={household.currency} />}
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

function OverviewBody({ data, categories, currency }: { data: Overview; categories: Category[]; currency: string }) {
  const { thisMonth, typicalMonth } = data;
  const inProgress = data.daysElapsed > 0 && data.daysElapsed < data.daysInMonth;
  const pace = inProgress ? Math.round((thisMonth.spendingMinor / data.daysElapsed) * data.daysInMonth) : null;
  const rate = thisMonth.incomeMinor > 0 ? Math.round((saved(thisMonth) / thisMonth.incomeMinor) * 100) : null;
  const categorySlices = rankSlices(new Map(data.topCategories.map((c) => [categoryName(categories, c.categoryId), c.amountMinor])));
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
        <ShareBar label="Spending by category" slices={categorySlices} />
      </div>

      <div className="grid gap-4 lg:grid-cols-2 2xl:grid-cols-3">
        <OverviewCard title="Income and spending" className="lg:col-span-2 2xl:col-span-3">
          <FlowChart history={data.history} currency={currency} />
        </OverviewCard>

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
