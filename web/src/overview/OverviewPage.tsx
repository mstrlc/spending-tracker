import { useCategories, useOverview } from "../api/hooks";
import type { Category, MonthFlow, Overview } from "../api/types";
import { useHousehold } from "../households/HouseholdPages";
import { formatMonth, todayIso } from "../lib/dates";
import { formatAmount } from "../lib/money";
import { MonthTitle, useMonthParam, useMonthShortcuts } from "../month";
import { rankSlices, ShareBar } from "../shares";
import { ErrorText, PageHeader, SkeletonRows } from "../ui";

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
        <section className="surface">
          <SkeletonRows />
        </section>
      )}
      {overview.data && <OverviewBody data={overview.data} categories={categories} currency={household.currency} />}
    </>
  );
}

function Money({ minor, currency }: { minor: number; currency: string }) {
  return (
    <>
      {minor < 0 ? "−" : ""}
      {formatAmount(Math.abs(minor), currency)}
      <span className="currency">{currency}</span>
    </>
  );
}

function OverviewBody({ data, categories, currency }: { data: Overview; categories: Category[]; currency: string }) {
  const { thisMonth, typicalMonth } = data;
  const inProgress = data.daysElapsed > 0 && data.daysElapsed < data.daysInMonth;
  const pace = inProgress ? Math.round((thisMonth.spendingMinor / data.daysElapsed) * data.daysInMonth) : null;
  const rate = thisMonth.incomeMinor > 0 ? Math.round((saved(thisMonth) / thisMonth.incomeMinor) * 100) : null;
  const categorySlices = rankSlices(new Map(data.topCategories.map((c) => [categoryName(categories, c.categoryId), c.amountMinor])));
  const subscriptionTotal = data.subscriptions.reduce((sum, s) => sum + s.monthlyMinor, 0);

  return (
    <>
      <div className="overview">
        <dl className="summary">
          <div>
            <dt>Spent{inProgress && ` · ${data.daysElapsed} of ${data.daysInMonth} days`}</dt>
            <dd className="summary-primary">
              <Money minor={thisMonth.spendingMinor} currency={currency} />
            </dd>
          </div>
          <div>
            <dt>Income</dt>
            <dd>
              <Money minor={thisMonth.incomeMinor} currency={currency} />
            </dd>
          </div>
          <div>
            <dt>Saved{rate !== null && ` · ${rate}%`}</dt>
            <dd>
              <Money minor={saved(thisMonth)} currency={currency} />
            </dd>
          </div>
          <div>
            <dt>Invested</dt>
            <dd>
              <Money minor={thisMonth.investedMinor} currency={currency} />
            </dd>
          </div>
        </dl>
        {(pace !== null || typicalMonth) && (
          <p className="muted overview-note">
            {pace !== null && (
              <>
                On pace to spend <span className="mono">{formatAmount(pace, currency)}</span> by the end of the month.{" "}
              </>
            )}
            {typicalMonth && (
              <>
                A typical month over the last {typicalMonth.monthCount === 1 ? "month" : `${typicalMonth.monthCount} months`}:{" "}
                <span className="mono">{formatAmount(typicalMonth.incomeMinor, currency)}</span> in,{" "}
                <span className="mono">{formatAmount(typicalMonth.spendingMinor, currency)}</span> spent,{" "}
                <span className="mono">{formatAmount(saved(typicalMonth), currency)}</span> saved
                {typicalMonth.outlierMonths.length > 0 &&
                  ` (${typicalMonth.outlierMonths.map((m) => formatMonth(m)).join(", ")} left out as unusually expensive)`}
                .
              </>
            )}
          </p>
        )}
        <ShareBar label="Spending by category" slices={categorySlices} />
      </div>

      <div className="card-grid">
        <section className="surface card card-wide">
          <h2 className="card-title">Income and spending</h2>
          <FlowChart history={data.history} currency={currency} />
        </section>

        <section className="surface card">
          <h2 className="card-title">Unusual spending</h2>
          <p className="muted small card-subtitle">
            {formatMonth(data.anomalyMonth)} against its usual month over the year before
            {data.anomalyMonth === data.month && inProgress && ", scaled to the days so far"}
          </p>
          {data.anomalies.length === 0 ? (
            <p className="muted card-empty">Nothing out of the ordinary.</p>
          ) : (
            <ul className="card-list">
              {data.anomalies.slice(0, 6).map((a) => (
                <li key={a.categoryId ?? "none"}>
                  <span>
                    {categoryName(categories, a.categoryId)}
                    <span className="muted small block">usually {formatAmount(a.baselineMinor, currency)}</span>
                  </span>
                  <span className="num">
                    {formatAmount(a.spentMinor, currency)}
                    <span className="badge">{a.ratio >= 1 ? `+${Math.round((a.ratio - 1) * 100)}%` : `−${Math.round((1 - a.ratio) * 100)}%`}</span>
                  </span>
                </li>
              ))}
            </ul>
          )}
        </section>

        <section className="surface card">
          <h2 className="card-title">Subscriptions</h2>
          <p className="muted small card-subtitle">
            {data.subscriptions.length > 0 ? (
              <>
                Charged most months by a steady amount: <span className="mono">{formatAmount(subscriptionTotal, currency)}</span> a
                month, <span className="mono">{formatAmount(subscriptionTotal * 12, currency)}</span> a year
              </>
            ) : (
              "Merchants charged by a steady amount in 3 of the last 6 months"
            )}
          </p>
          {data.subscriptions.length === 0 ? (
            <p className="muted card-empty">No recurring payments found.</p>
          ) : (
            <ul className="card-list">
              {data.subscriptions.slice(0, 10).map((s) => (
                <li key={s.merchant}>
                  <span>
                    {s.merchant}
                    <span className="muted small block">{categoryName(categories, s.categoryId)}</span>
                  </span>
                  <span className="num">{formatAmount(s.monthlyMinor, currency)}</span>
                </li>
              ))}
            </ul>
          )}
        </section>

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
    <section className="surface card">
      <h2 className="card-title">{title}</h2>
      {rows.length === 0 ? (
        <p className="muted card-empty">{empty}</p>
      ) : (
        <ul className="card-list">
          {rows.map((r) => (
            <li key={r.name}>
              <span>{r.name}</span>
              <span className="num">{formatAmount(r.amountMinor, currency)}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

const CHART_HEIGHT = 140;

/** Income and spending side by side per month, on one shared scale. Hover a month for its figures. */
function FlowChart({ history, currency }: { history: MonthFlow[]; currency: string }) {
  const max = Math.max(1, ...history.flatMap((m) => [m.incomeMinor, m.spendingMinor]));
  const height = (value: number) => (Math.max(0, value) / max) * CHART_HEIGHT;
  return (
    <figure className="flow-chart">
      <ul className="category-legend" aria-hidden="true">
        <li>
          <span className="swatch" style={{ background: "var(--chart-4)" }} />
          Income
        </li>
        <li>
          <span className="swatch" style={{ background: "var(--chart-1)" }} />
          Spent
        </li>
      </ul>
      <div className="flow-bars" style={{ height: CHART_HEIGHT }}>
        {history.map((m) => {
          const label = `${formatMonth(m.month)}: income ${formatAmount(m.incomeMinor, currency)}, spent ${formatAmount(m.spendingMinor, currency)} ${currency}`;
          return (
            <div key={m.month} className="flow-month" title={label} aria-label={label} role="img">
              <span style={{ height: height(m.incomeMinor), background: "var(--chart-4)" }} />
              <span style={{ height: height(m.spendingMinor), background: "var(--chart-1)" }} />
            </div>
          );
        })}
      </div>
      <div className="flow-axis" aria-hidden="true">
        {history.map((m) => (
          <span key={m.month}>{shortMonth(m.month)}</span>
        ))}
      </div>
    </figure>
  );
}
