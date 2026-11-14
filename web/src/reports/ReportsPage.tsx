import { useState, type ReactNode } from "react";
import { useSearchParams } from "react-router";
import { useQueryClient } from "@tanstack/react-query";
import { EyeOffIcon, XIcon } from "lucide-react";
import { useAccounts, useCategories, useMembers, useMerchants, useSpendingReport, type SpendingReportQuery } from "../api/hooks";
import { api } from "../api/client";
import { unwrap } from "../api/errors";
import type { Category, Expense, ReportExpense, SpendingReport } from "../api/types";
import { AccountLabel, CategoryLabel, Dot, categoryTone } from "../chips";
import { categoryTree } from "../categories/tree";
import { ExpenseForm } from "../expenses/ExpenseForm";
import { useHousehold } from "../households/HouseholdPages";
import { formatDate, formatDay, formatMonth, todayIso } from "../lib/dates";
import { formatAmount } from "../lib/money";
import { EmptyState, ErrorText, Money, PageHeader, Sheet, SkeletonRows, Summary, Surface } from "../ui";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { Table, TableBody, TableCell, TableHeader, TableRow } from "@/components/ui/table";
import { cn } from "@/lib/utils";
import { EmptyFilterRow, SortableHead, useTableSortAndFilter } from "../sorting";
import { compactAmount, niceTicks } from "./chart";
import { byYear, LAST_PERIODS, monthPeriod, percentChange, periodRange, periodValue, readPeriod, writePeriod, type Period } from "./period";

const FILTERS = ["category", "merchant", "account", "whose"] as const;
type Filter = (typeof FILTERS)[number];

/**
 * Spending over any period, narrowed by category, merchant, account and whose: "how much went on haircuts in 2026",
 * how it moved month by month, and what it was. Everything lives in the URL, so a view can be bookmarked or shared.
 */
export function ReportsPage() {
  const household = useHousehold();
  const today = todayIso();
  const [params, setParams] = useSearchParams();
  const period = readPeriod(params, today);
  const filter = (name: Filter) => params.get(name) ?? undefined;
  const excluded = params.getAll("exclude");
  const query: SpendingReportQuery = {
    ...periodRange(period, today),
    categoryId: filter("category"),
    excludeCategoryId: excluded.length > 0 ? excluded : undefined,
    merchantId: filter("merchant"),
    accountId: filter("account"),
    whose: filter("whose"),
  };
  const report = useSpendingReport(household.id, query, today);
  const categories = useCategories(household.id).data ?? [];
  const [editing, setEditing] = useState<Expense | null>(null);

  const update = (change: (next: URLSearchParams) => void) => {
    const next = new URLSearchParams(params);
    change(next);
    // Each change is a step in the history, so Back undoes the last narrowing instead of leaving the page.
    if (next.toString() !== params.toString()) setParams(next);
  };
  const setFilter = (name: Filter, value: string | undefined) =>
    update((next) => (value ? next.set(name, value) : next.delete(name)));
  const setPeriod = (p: Period) => update((next) => writePeriod(next, p));
  const anyFilter = FILTERS.some((f) => params.has(f)) || excluded.length > 0;
  const exclude = (id: string) => update((next) => !excluded.includes(id) && next.append("exclude", id));
  const include = (id: string) => update((next) => next.delete("exclude", id));

  const qc = useQueryClient();
  const openExpense = async (row: ReportExpense) => {
    // There's no single-expense read: the day's expenses are a small list.
    const day = await qc.fetchQuery({
      queryKey: ["households", household.id, "expenses", row.date, row.date],
      queryFn: async () =>
        unwrap(
          await api.GET("/api/households/{householdId}/expenses", {
            params: { path: { householdId: household.id }, query: { from: row.date, to: row.date } },
          }),
        ),
    });
    const expense = day.find((e) => e.id === row.expenseId);
    if (expense) setEditing(expense);
  };

  return (
    <>
      <PageHeader title="Reports" />

      <Filters
        period={period}
        setPeriod={setPeriod}
        filter={filter}
        setFilter={setFilter}
        excluded={excluded}
        exclude={exclude}
        include={include}
        clear={anyFilter ? () => update((next) => [...FILTERS, "exclude"].forEach((f) => next.delete(f))) : undefined}
        today={today}
      />

      <ErrorText error={report.error} />
      {report.isPending && (
        <Surface>
          <SkeletonRows />
        </Surface>
      )}
      {report.data && (
        <div className={cn("flex flex-col gap-5 transition-opacity", report.isPlaceholderData && "opacity-60")}>
          <Figures report={report.data} currency={household.currency} period={period} />
          {report.data.count === 0 ? (
            <Surface>
              <EmptyState title="No spending here">Nothing matches these filters in this period. Try a longer period or fewer filters.</EmptyState>
            </Surface>
          ) : (
            <>
              <ReportCard title={chartTitle(report.data)}>
                <TrendChart report={report.data} currency={household.currency} onPickMonth={(m) => setPeriod(monthPeriod(m))} />
              </ReportCard>
              <div className="grid gap-5 lg:grid-cols-2">
                <CategoryBreakdown
                  report={report.data}
                  categories={categories}
                  currency={household.currency}
                  filtered={filter("category")}
                  onPick={(id) => setFilter("category", id ?? "none")}
                  onExclude={exclude}
                />
                <MerchantBreakdown
                  report={report.data}
                  currency={household.currency}
                  filtered={filter("merchant") !== undefined}
                  onPick={(id) => setFilter("merchant", id ?? "none")}
                />
              </div>
              <ExpenseList report={report.data} categories={categories} currency={household.currency} onOpen={openExpense} />
            </>
          )}
        </div>
      )}

      <Sheet open={editing !== null} onClose={() => setEditing(null)} title="Edit expense">
        {editing && (
          <ExpenseForm
            key={editing.id}
            expense={editing}
            onOpenExpense={(e) => setEditing(e)}
            onDone={() => setEditing(null)}
          />
        )}
      </Sheet>
    </>
  );
}

// ---- Filters ------------------------------------------------------------------------------------------------------

const YEARS_BACK = 6;

function Filters({
  period,
  setPeriod,
  filter,
  setFilter,
  excluded,
  exclude,
  include,
  clear,
  today,
}: {
  excluded: string[];
  exclude: (id: string) => void;
  include: (id: string) => void;
  period: Period;
  setPeriod: (p: Period) => void;
  filter: (name: Filter) => string | undefined;
  setFilter: (name: Filter, value: string | undefined) => void;
  clear?: () => void;
  today: string;
}) {
  const household = useHousehold();
  const categories = useCategories(household.id).data ?? [];
  const merchants = useMerchants(household.id).data ?? [];
  const accounts = useAccounts(household.id).data ?? [];
  const members = useMembers(household.id).data ?? [];
  const thisYear = Number(today.slice(0, 4));
  const tree = categoryTree(categories.filter((c) => c.kind !== "income"));

  return (
    <div className="flex flex-col gap-3" role="search" aria-label="Report filters">
      <div className="flex flex-wrap items-end gap-3">
        <Labelled label="Period">
          <NativeSelect
            aria-label="Period"
            value={period.kind === "custom" ? "custom" : periodValue(period)}
            onChange={(e) => {
              const v = e.target.value;
              const last = LAST_PERIODS.find((p) => p.value === v);
              if (last) setPeriod({ kind: "last", months: last.months });
              else if (v === "all") setPeriod({ kind: "all" });
              else if (v === "custom") {
                const range = periodRange(period, today);
                setPeriod({ kind: "custom", from: range.from ?? `${thisYear}-01-01`, to: range.to ?? today });
              } else setPeriod({ kind: "year", year: Number(v) });
            }}
          >
            <optgroup label="Up to today">
              {LAST_PERIODS.map((p) => (
                <NativeSelectOption key={p.value} value={p.value}>
                  {p.label}
                </NativeSelectOption>
              ))}
            </optgroup>
            <optgroup label="Years">
              {Array.from({ length: YEARS_BACK }, (_, i) => thisYear - i).map((y) => (
                <NativeSelectOption key={y} value={String(y)}>
                  {y}
                </NativeSelectOption>
              ))}
              {period.kind === "year" && period.year <= thisYear - YEARS_BACK && (
                <NativeSelectOption value={String(period.year)}>{period.year}</NativeSelectOption>
              )}
            </optgroup>
            <NativeSelectOption value="all">All time</NativeSelectOption>
            <NativeSelectOption value="custom">Custom dates…</NativeSelectOption>
          </NativeSelect>
        </Labelled>
        {period.kind === "custom" && (
          <>
            <Labelled label="From">
              <Input
                type="date"
                aria-label="From"
                className="h-8 w-40"
                value={period.from}
                max={period.to}
                onChange={(e) => e.target.value && setPeriod({ ...period, from: e.target.value })}
              />
            </Labelled>
            <Labelled label="To">
              <Input
                type="date"
                aria-label="To"
                className="h-8 w-40"
                value={period.to}
                min={period.from}
                onChange={(e) => e.target.value && setPeriod({ ...period, to: e.target.value })}
              />
            </Labelled>
          </>
        )}
        <Labelled label="Category">
          <NativeSelect aria-label="Category" value={filter("category") ?? ""} onChange={(e) => setFilter("category", e.target.value || undefined)} className="max-w-56">
            <NativeSelectOption value="">All categories</NativeSelectOption>
            {tree.map((parent) => [
              <NativeSelectOption key={parent.id} value={parent.id}>
                {parent.name}
              </NativeSelectOption>,
              ...parent.children.map((child) => (
                <NativeSelectOption key={child.id} value={child.id}>
                  {" "}
                  {child.name}
                </NativeSelectOption>
              )),
            ])}
            <NativeSelectOption value="none">Uncategorized</NativeSelectOption>
          </NativeSelect>
        </Labelled>
        <Labelled label="Leave out">
          <NativeSelect
            aria-label="Leave out a category"
            value=""
            onChange={(e) => e.target.value && exclude(e.target.value)}
            className="max-w-48"
          >
            <NativeSelectOption value="">{excluded.length > 0 ? "Another category…" : "A category…"}</NativeSelectOption>
            {tree.map((parent) => [
              <NativeSelectOption key={parent.id} value={parent.id} disabled={excluded.includes(parent.id) || parent.id === filter("category")}>
                {parent.name}
              </NativeSelectOption>,
              ...parent.children.map((child) => (
                <NativeSelectOption key={child.id} value={child.id} disabled={excluded.includes(child.id) || child.id === filter("category")}>
                  {"\u2003"}
                  {child.name}
                </NativeSelectOption>
              )),
            ])}
          </NativeSelect>
        </Labelled>
        <Labelled label="Merchant">
          <MerchantPicker merchants={merchants} value={filter("merchant")} onChange={(id) => setFilter("merchant", id)} />
        </Labelled>
        {accounts.length > 1 && (
          <Labelled label="Account">
            <NativeSelect aria-label="Account" value={filter("account") ?? ""} onChange={(e) => setFilter("account", e.target.value || undefined)} className="max-w-48">
              <NativeSelectOption value="">All accounts</NativeSelectOption>
              {accounts.map((a) => (
                <NativeSelectOption key={a.id} value={a.id}>
                  {a.name}
                </NativeSelectOption>
              ))}
            </NativeSelect>
          </Labelled>
        )}
        {members.length > 1 && (
          <Labelled label="Whose">
            <NativeSelect aria-label="Whose" value={filter("whose") ?? ""} onChange={(e) => setFilter("whose", e.target.value || undefined)}>
              <NativeSelectOption value="">Everyone</NativeSelectOption>
              <NativeSelectOption value="joint">Joint</NativeSelectOption>
              {members.map((m) => (
                <NativeSelectOption key={m.userId} value={m.userId}>
                  {m.displayName}
                </NativeSelectOption>
              ))}
            </NativeSelect>
          </Labelled>
        )}
        {clear && (
          <Button variant="ghost" size="sm" onClick={clear}>
            <XIcon /> Clear filters
          </Button>
        )}
      </div>
      {excluded.length > 0 && (
        <ul className="flex flex-wrap items-center gap-2 text-sm" aria-label="Left out">
          <li className="text-xs text-muted-foreground">Left out</li>
          {excluded.map((id) => (
            <li key={id}>
              <span className="inline-flex items-center gap-1 rounded-full border py-0.5 pr-1 pl-2.5">
                <CategoryLabel categories={categories} id={id} />
                <button
                  type="button"
                  className="rounded-full p-0.5 text-muted-foreground outline-none hover:bg-muted hover:text-foreground focus-visible:bg-muted"
                  aria-label={`Count ${categories.find((c) => c.id === id)?.name ?? "this category"} again`}
                  title="Count it again"
                  onClick={() => include(id)}
                >
                  <XIcon className="size-3.5" />
                </button>
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function Labelled({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-1">
      <span className="text-xs text-muted-foreground" aria-hidden="true">
        {label}
      </span>
      {children}
    </div>
  );
}

/** A text box that suggests merchant names as you type; picking one (an exact name) sets the filter. */
function MerchantPicker({
  merchants,
  value,
  onChange,
}: {
  merchants: { id: string; name: string }[];
  value: string | undefined;
  onChange: (id: string | undefined) => void;
}) {
  const selected = value === "none" ? "(no merchant)" : (merchants.find((m) => m.id === value)?.name ?? "");
  const [text, setText] = useState<string | null>(null);
  const shown = text ?? selected;
  const sorted = [...merchants].sort((a, b) => a.name.localeCompare(b.name));
  const commit = (input: string) => {
    const name = input.trim().toLowerCase();
    if (name === "") {
      onChange(undefined);
      setText(null);
      return;
    }
    const match = merchants.find((m) => m.name.toLowerCase() === name);
    if (match) {
      onChange(match.id);
      setText(null);
    }
  };
  return (
    <>
      <Input
        aria-label="Merchant"
        list="report-merchants"
        placeholder="All merchants"
        className="h-8 w-52"
        value={shown}
        onChange={(e) => {
          setText(e.target.value);
          // Choosing from the suggestions fills the whole name at once.
          if (merchants.some((m) => m.name === e.target.value)) commit(e.target.value);
          else if (e.target.value === "") commit("");
        }}
        onBlur={() => {
          if (text !== null) commit(text);
          setText(null);
        }}
        onKeyDown={(e) => {
          if (e.key === "Enter") commit(e.currentTarget.value);
        }}
      />
      <datalist id="report-merchants">
        {sorted.map((m) => (
          <option key={m.id} value={m.name} />
        ))}
      </datalist>
    </>
  );
}

// ---- Figures ------------------------------------------------------------------------------------------------------

function periodLabel(from: string, to: string): string {
  return `${formatDate(from)} – ${formatDate(to)}`;
}

function Figures({ report, currency, period }: { report: SpendingReport; currency: string; period: Period }) {
  const change = report.previous ? percentChange(report.totalMinor, report.previous.totalMinor) : null;
  return (
    <div className="flex flex-col gap-2">
      <Summary
        items={[
          { label: "Spent", value: <Money currency={currency}>{formatAmount(report.totalMinor, currency)}</Money> },
          report.months.length > 1 && {
            label: "Per month",
            value: <Money currency={currency}>{formatAmount(report.averageMonthMinor, currency)}</Money>,
          },
          { label: "Expenses", value: <span className="amount">{report.count}</span> },
          report.investedMinor !== null &&
            report.investedMinor !== 0 && {
              label: report.investedMinor > 0 ? "Invested" : "Taken out of investments",
              value: <Money currency={currency}>{formatAmount(Math.abs(report.investedMinor), currency)}</Money>,
            },
          report.previous && {
            label: period.kind === "year" ? "Same time a year before" : "A year before",
            value: (
              <span className="flex items-baseline gap-2">
                <Money currency={currency}>{formatAmount(report.previous.totalMinor, currency)}</Money>
                {change !== null && (
                  <span className={cn("amount text-sm", change > 0 ? "text-destructive" : change < 0 ? "text-(--tone-income)" : "text-muted-foreground")}>
                    {change > 0 ? "+" : change < 0 ? "−" : "±"}
                    {Math.abs(change)}%
                  </span>
                )}
              </span>
            ),
          },
        ]}
      />
      <p className="text-sm text-muted-foreground">
        {periodLabel(report.from, report.to)}
        {report.previous && <> · compared with {periodLabel(report.previous.from, report.previous.to)}</>}
      </p>
    </div>
  );
}

function ReportCard({ title, subtitle, children }: { title: string; subtitle?: ReactNode; children: ReactNode }) {
  return (
    <Card className="gap-4">
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

// ---- Trend --------------------------------------------------------------------------------------------------------

const MAX_MONTH_BARS = 36;
const CHART_HEIGHT = 200;
/** Investing on the bars: thin stripes of the spending colour, lighter than spending's solid fill. */
const INVESTED_FILL =
  "repeating-linear-gradient(135deg, color-mix(in oklab, var(--chart-1) 45%, transparent) 0 2px, transparent 2px 5px)";

const chartTitle = (report: SpendingReport) => (report.months.length > MAX_MONTH_BARS ? "By year" : "By month");

function shortMonth(month: string): string {
  const [y, m] = month.split("-").map(Number);
  return new Date(y, m - 1, 1).toLocaleDateString(undefined, { month: "short" });
}

type Bar = { month: string; amountMinor: number; count: number; investedMinor: number };

/**
 * Bars per month (per year for long periods) on a labelled amount axis, each with its figure on top, the monthly
 * average as a dashed line, and a card with the details for the bar under the pointer or keyboard focus. Click a
 * month to look at it alone. Above it: the highest and lowest month and how many months had any spending.
 */
function TrendChart({ report, currency, onPickMonth }: { report: SpendingReport; currency: string; onPickMonth: (month: string) => void }) {
  const [active, setActive] = useState<number | null>(null);
  const [investingOn, setInvestingOn] = useState(true);
  const yearly = report.months.length > MAX_MONTH_BARS;
  const bars: Bar[] = yearly ? byYear(report.months) : report.months;
  // Investing sits on top of spending, striped, when the report can count it and there was any.
  const hasInvesting = report.investedMinor !== null && bars.some((b) => b.investedMinor > 0);
  const showInvesting = hasInvesting && investingOn;
  const invested = (b: Bar) => (showInvesting ? Math.max(0, b.investedMinor) : 0);
  const ticks = niceTicks(Math.max(0, ...bars.map((b) => b.amountMinor + invested(b))), currency);
  const top = ticks[ticks.length - 1];
  const height = (value: number) => (Math.max(0, value) / top) * CHART_HEIGHT;
  const showAverage = !yearly && bars.length > 1 && report.averageMonthMinor > 0;
  const spansYears = !yearly && new Set(bars.map((b) => b.month.slice(0, 4))).size > 1;
  const name = (b: Bar) => (yearly ? b.month : formatMonth(b.month));
  // Figures on the bars only where they fit: all of them up to a year's worth, every bar's on wider screens up to two.
  const figures = bars.length <= 12 ? "" : bars.length <= 24 ? "max-lg:hidden" : "hidden";
  const shown = active !== null ? bars[active] : null;

  return (
    <figure className="flex flex-col gap-4">
      <Highlights bars={bars} yearly={yearly} currency={currency} />
      <div className="flex flex-wrap items-center gap-x-5 gap-y-2 text-xs text-muted-foreground">
        {hasInvesting && (
          <>
            <span className="flex items-center gap-2">
              <span className="size-2.5 rounded-sm bg-chart-1" aria-hidden="true" />
              Spent
            </span>
            <button
              type="button"
              className={cn("flex items-center gap-2 rounded outline-none hover:text-foreground focus-visible:text-foreground", !investingOn && "line-through opacity-60")}
              aria-pressed={investingOn}
              title={investingOn ? "Hide investing" : "Show investing"}
              onClick={() => setInvestingOn(!investingOn)}
            >
              <span className="size-2.5 rounded-sm border border-dashed border-chart-1" style={{ background: INVESTED_FILL }} aria-hidden="true" />
              Invested
            </button>
          </>
        )}
        {showAverage && (
          <span className="flex items-center gap-2">
            <span className="w-4 border-t border-dashed border-muted-foreground" aria-hidden="true" />
            Average month spent <span className="amount">{formatAmount(report.averageMonthMinor, currency)}</span>
          </span>
        )}
      </div>
      <div className="flex gap-2">
        {/* The amount axis. */}
        <div className="relative w-10 shrink-0 text-right text-xs text-muted-foreground" style={{ height: CHART_HEIGHT }} aria-hidden="true">
          {ticks.map((t) => (
            <span key={t} className="amount absolute right-0 translate-y-1/2" style={{ bottom: height(t) }}>
              {compactAmount(t, currency)}
            </span>
          ))}
        </div>
        <div className="flex min-w-0 flex-1 flex-col gap-2">
          <div className="relative flex items-end gap-1 border-b sm:gap-2" style={{ height: CHART_HEIGHT }} onMouseLeave={() => setActive(null)}>
            {ticks.slice(1).map((t) => (
              <span key={t} className="pointer-events-none absolute inset-x-0 border-t border-border/60" style={{ bottom: height(t) }} aria-hidden="true" />
            ))}
            {showAverage && (
              <span
                className="pointer-events-none absolute inset-x-0 border-t border-dashed border-muted-foreground/70"
                style={{ bottom: height(report.averageMonthMinor) }}
                aria-hidden="true"
              />
            )}
            {bars.map((b, i) => {
              const label =
                `${name(b)}: spent ${formatAmount(b.amountMinor, currency)} ${currency}, ${b.count} ${b.count === 1 ? "expense" : "expenses"}` +
                (showInvesting && b.investedMinor !== 0 ? `, invested ${formatAmount(b.investedMinor, currency)} ${currency}` : "");
              const content = (
                <>
                  {b.amountMinor > 0 && (
                    <span className={cn("amount mb-0.5 text-[0.65rem] leading-none whitespace-nowrap text-muted-foreground", figures, active === i && "text-foreground")}>
                      {compactAmount(b.amountMinor, currency)}
                    </span>
                  )}
                  {invested(b) > 0 && (
                    <span
                      className={cn(
                        "w-full max-w-8 rounded-t-sm border border-b-0 border-dashed border-chart-1/70 transition-opacity",
                        active !== null && active !== i && "opacity-40",
                      )}
                      style={{ height: height(invested(b)), background: INVESTED_FILL }}
                    />
                  )}
                  <span
                    className={cn(
                      "w-full max-w-8 bg-chart-1 transition-opacity",
                      invested(b) > 0 ? "" : "rounded-t-sm",
                      active !== null && active !== i && "opacity-40",
                    )}
                    style={{ height: height(b.amountMinor) }}
                  />
                </>
              );
              const common = {
                onMouseEnter: () => setActive(i),
                onFocus: () => setActive(i),
                onBlur: () => setActive(null),
                className: "relative z-10 flex h-full min-w-0 flex-1 flex-col items-center justify-end rounded-sm outline-none",
              };
              return yearly ? (
                <div key={b.month} {...common} tabIndex={0} role="img" aria-label={label}>
                  {content}
                </div>
              ) : (
                <button
                  key={b.month}
                  type="button"
                  {...common}
                  className={cn(common.className, "cursor-pointer hover:bg-muted/60 focus-visible:bg-muted/60")}
                  aria-label={`${label}. Show only this month`}
                  onClick={() => onPickMonth(b.month)}
                >
                  {content}
                </button>
              );
            })}
            {shown && active !== null && (
              <BarDetails
                investing={report.investedMinor !== null}
                bar={shown}
                name={name(shown)}
                report={report}
                yearly={yearly}
                currency={currency}
                // Keep the card inside the chart: anchored by its left edge on the left half, its right edge on the right.
                position={(active + 0.5) / bars.length}
              />
            )}
          </div>
          <div className="flex gap-1 text-center text-xs text-muted-foreground sm:gap-2" aria-hidden="true">
            {bars.map((b, i) => (
              // Labels may spill over their neighbours, which are hidden whenever bars get narrow.
              <span key={b.month} className="flex min-w-0 flex-1 flex-col items-center whitespace-nowrap">
                <span className={cn(bars.length > 18 && i % 3 !== 0 && "invisible", bars.length > 12 && i % 2 !== 0 && "max-sm:invisible")}>
                  {yearly ? b.month : shortMonth(b.month)}
                </span>
                {/* Where a year starts, its number goes under the month. */}
                {spansYears && (i === 0 || b.month.endsWith("-01")) && <span>{b.month.slice(0, 4)}</span>}
              </span>
            ))}
          </div>
        </div>
      </div>
    </figure>
  );
}

/** The card for one bar: its amount, expenses, the average expense, and how it compares with the period. */
function BarDetails({
  investing,
  bar,
  name,
  report,
  yearly,
  currency,
  position,
}: {
  investing: boolean;
  bar: Bar;
  name: string;
  report: SpendingReport;
  yearly: boolean;
  currency: string;
  position: number;
}) {
  const average = yearly ? null : report.averageMonthMinor;
  const vsAverage = average ? percentChange(bar.amountMinor, average) : null;
  const share = report.totalMinor > 0 ? Math.round((bar.amountMinor / report.totalMinor) * 100) : null;
  const left = position <= 0.5;
  return (
    <div
      className="pointer-events-none absolute top-0 z-20 w-max min-w-44 rounded-lg border bg-popover px-3 py-2 text-sm text-popover-foreground shadow-md"
      style={left ? { left: `${position * 100}%`, marginLeft: 12 } : { right: `${(1 - position) * 100}%`, marginRight: 12 }}
      role="status"
    >
      <p className="font-medium">{name}</p>
      <p className="mt-1">
        <Money currency={currency}>{formatAmount(bar.amountMinor, currency)}</Money>
      </p>
      <dl className="mt-1 grid grid-cols-[auto_auto] gap-x-3 text-xs text-muted-foreground">
        <dt>Expenses</dt>
        <dd className="amount text-right">{bar.count}</dd>
        {bar.count > 0 && (
          <>
            <dt>Average expense</dt>
            <dd className="amount text-right">{formatAmount(Math.round(bar.amountMinor / bar.count), currency)}</dd>
          </>
        )}
        {vsAverage !== null && (
          <>
            <dt>Against the average month</dt>
            <dd className={cn("amount text-right", vsAverage > 0 ? "text-destructive" : vsAverage < 0 ? "text-(--tone-income)" : undefined)}>
              {vsAverage > 0 ? "+" : vsAverage < 0 ? "−" : "±"}
              {Math.abs(vsAverage)}%
            </dd>
          </>
        )}
        {share !== null && (
          <>
            <dt>Of the period's spending</dt>
            <dd className="amount text-right">{share}%</dd>
          </>
        )}
        {investing && bar.investedMinor !== 0 && (
          <>
            <dt>{bar.investedMinor > 0 ? "Invested" : "Taken out of investments"}</dt>
            <dd className="amount text-right">{formatAmount(Math.abs(bar.investedMinor), currency)}</dd>
          </>
        )}
      </dl>
      {!yearly && <p className="mt-1.5 text-xs text-muted-foreground">Click to see only this month</p>}
    </div>
  );
}

/** The highest and lowest month (or year), and how many had any spending. */
function Highlights({ bars, yearly, currency }: { bars: Bar[]; yearly: boolean; currency: string }) {
  if (bars.length < 2) return null;
  const highest = bars.reduce((a, b) => (b.amountMinor > a.amountMinor ? b : a));
  // Empty months are counted below, so the lowest is the lowest that had any spending.
  const spent = bars.filter((b) => b.amountMinor > 0);
  const lowest = spent.length > 0 ? spent.reduce((a, b) => (b.amountMinor < a.amountMinor ? b : a)) : highest;
  const withSpending = spent.length;
  const name = (b: Bar) => (yearly ? b.month : formatMonth(b.month));
  const unit = yearly ? "years" : "months";
  return (
    <dl className="grid grid-cols-2 gap-x-6 gap-y-2 text-sm sm:flex sm:flex-wrap">
      <div>
        <dt className="text-xs text-muted-foreground">Highest</dt>
        <dd>
          {name(highest)} · <span className="amount">{formatAmount(highest.amountMinor, currency)}</span>
        </dd>
      </div>
      <div>
        <dt className="text-xs text-muted-foreground">Lowest</dt>
        <dd>
          {name(lowest)} · <span className="amount">{formatAmount(lowest.amountMinor, currency)}</span>
        </dd>
      </div>
      <div>
        <dt className="text-xs text-muted-foreground">{yearly ? "Years" : "Months"} with spending</dt>
        <dd>
          <span className="amount">{withSpending}</span> of <span className="amount">{bars.length}</span> {unit}
        </dd>
      </div>
    </dl>
  );
}

// ---- Breakdowns ---------------------------------------------------------------------------------------------------

type BreakdownRow = {
  key: string;
  name: ReactNode;
  amountMinor: number;
  count: number;
  tone?: string;
  onPick?: () => void;
  /** Leaves the row out of the report; [label] names it for screen readers. */
  exclude?: { label: string; onExclude: () => void };
};

/** Rows with a bar for each one's share of the largest, the amount and the share of the total. */
function Breakdown({ rows, total, currency, limit }: { rows: BreakdownRow[]; total: number; currency: string; limit?: number }) {
  const [all, setAll] = useState(false);
  const shown = limit && !all ? rows.slice(0, limit) : rows;
  const max = Math.max(1, ...rows.map((r) => r.amountMinor));
  return (
    <div className="flex flex-col gap-2">
      <ul className="flex flex-col text-sm">
        {shown.map((r) => {
          const share = total > 0 ? Math.round((r.amountMinor / total) * 100) : 0;
          const body = (
            <>
              <span className="flex items-baseline justify-between gap-3">
                <span className="min-w-0 truncate">{r.name}</span>
                <span className="flex shrink-0 items-baseline gap-2">
                  <span className="amount">{formatAmount(r.amountMinor, currency)}</span>
                  <span className="amount w-9 text-right text-xs text-muted-foreground">{share < 1 && r.amountMinor > 0 ? "<1" : share}%</span>
                </span>
              </span>
              <span className="mt-1 block h-1.5 overflow-hidden rounded-full bg-muted" aria-hidden="true">
                <span
                  className="block h-full rounded-full"
                  style={{ width: `${(Math.max(0, r.amountMinor) / max) * 100}%`, background: r.tone ? `var(--${r.tone})` : "var(--chart-1)" }}
                />
              </span>
              <span className="mt-0.5 block text-xs text-muted-foreground">
                {r.count} {r.count === 1 ? "expense" : "expenses"}
              </span>
            </>
          );
          return (
            <li key={r.key} className="relative">
              {r.exclude && (
                // Beside the row's own button rather than inside it, on the line with the count.
                <button
                  type="button"
                  className="absolute right-0 bottom-1.5 z-10 inline-flex items-center gap-1 rounded px-1 text-xs text-muted-foreground outline-none hover:bg-background hover:text-foreground focus-visible:bg-background focus-visible:text-foreground"
                  aria-label={`Leave out ${r.exclude.label}`}
                  onClick={r.exclude.onExclude}
                >
                  <EyeOffIcon className="size-3.5" /> Leave out
                </button>
              )}
              {r.onPick ? (
                <button type="button" className="-mx-2 block w-[calc(100%+1rem)] rounded-md px-2 py-1.5 text-left outline-none hover:bg-muted focus-visible:bg-muted" onClick={r.onPick}>
                  {body}
                </button>
              ) : (
                <div className="py-1.5">{body}</div>
              )}
            </li>
          );
        })}
      </ul>
      {limit && rows.length > limit && (
        <Button variant="ghost" size="sm" className="self-start" onClick={() => setAll(!all)}>
          {all ? "Show fewer" : `Show all ${rows.length}`}
        </Button>
      )}
    </div>
  );
}

const toneVar = (tone: string) => (tone === "neutral" ? "chart-4" : tone.startsWith("hue-") ? tone : `tone-${tone}`);

function CategoryBreakdown({
  report,
  categories,
  currency,
  filtered,
  onPick,
  onExclude,
}: {
  report: SpendingReport;
  categories: Category[];
  currency: string;
  filtered: string | undefined;
  onPick: (id: string | null) => void;
  onExclude: (id: string) => void;
}) {
  const filteredCategory = categories.find((c) => c.id === filtered);
  // Only one level is shown at a time: a top-level category picked from the list opens into its subcategories.
  const hasChildren = (id: string | null) => id !== null && categories.some((c) => c.parentId === id);
  const rows: BreakdownRow[] = report.categories.map((c) => ({
    key: c.categoryId ?? "none",
    name:
      filteredCategory && c.categoryId === filteredCategory.id && !filteredCategory.parentId && hasChildren(c.categoryId) ? (
        <span className="inline-flex items-center gap-1.5">
          <Dot tone={categoryTone(categories, c.categoryId)} />
          {filteredCategory.name} <span className="text-muted-foreground">itself</span>
        </span>
      ) : (
        <CategoryLabel categories={categories} id={c.categoryId} short={filteredCategory !== undefined} />
      ),
    amountMinor: c.amountMinor,
    count: c.count,
    tone: toneVar(categoryTone(categories, c.categoryId)),
    onPick: c.categoryId === filtered || (filteredCategory?.parentId && c.categoryId === filtered) ? undefined : () => onPick(c.categoryId),
    // Any category but the one filtered on (leaving that out would leave nothing).
    exclude:
      c.categoryId && c.categoryId !== filtered
        ? { label: categories.find((x) => x.id === c.categoryId)?.name ?? "this category", onExclude: () => onExclude(c.categoryId!) }
        : undefined,
  }));
  const subtitle = filteredCategory
    ? filteredCategory.parentId
      ? undefined
      : `Within ${filteredCategory.name}`
    : "Pick one to narrow the report to it";
  return (
    <ReportCard title={filteredCategory && !filteredCategory.parentId ? "By subcategory" : "By category"} subtitle={subtitle}>
      <Breakdown rows={rows} total={report.totalMinor} currency={currency} />
    </ReportCard>
  );
}

const MERCHANT_LIMIT = 8;

function MerchantBreakdown({
  report,
  currency,
  filtered,
  onPick,
}: {
  report: SpendingReport;
  currency: string;
  filtered: boolean;
  onPick: (id: string | null) => void;
}) {
  const rows: BreakdownRow[] = report.merchants.map((m) => ({
    key: m.merchantId ?? "none",
    name: m.merchantId ? m.name : <span className="text-muted-foreground">No merchant</span>,
    amountMinor: m.amountMinor,
    count: m.count,
    onPick: filtered ? undefined : () => onPick(m.merchantId),
  }));
  return (
    <ReportCard title="By merchant" subtitle={filtered ? undefined : "Pick one to narrow the report to it"}>
      <Breakdown rows={rows} total={report.totalMinor} currency={currency} limit={MERCHANT_LIMIT} />
    </ReportCard>
  );
}

// ---- Expenses -----------------------------------------------------------------------------------------------------

function ExpenseList({
  report,
  categories,
  currency,
  onOpen,
}: {
  report: SpendingReport;
  categories: Category[];
  currency: string;
  onOpen: (row: ReportExpense) => void;
}) {
  const household = useHousehold();
  const accounts = useAccounts(household.id).data ?? [];
  const spansYears = report.from.slice(0, 4) !== report.to.slice(0, 4);
  const dayLabel = (e: ReportExpense) => (spansYears ? formatDate(e.date) : formatDay(e.date));
  const categoryName = (e: ReportExpense) =>
    (e.categoryIds as (string | null)[]).map((id) => categories.find((c) => c.id === id)?.name ?? "Uncategorized").join(", ");
  const accountName = (e: ReportExpense) => accounts.find((a) => a.id === e.accountId)?.name ?? "";
  const { sort, onSort, clearFilters, shown, filterFor } = useTableSortAndFilter(
    report.expenses,
    {
      day: (e) => e.date,
      merchant: (e) => e.merchant,
      category: categoryName,
      account: accountName,
      amount: (e) => e.amountMinor,
    },
    {
      day: dayLabel,
      merchant: (e) => e.merchant || "Untitled expense",
      category: categoryName,
      account: (e) => accountName(e) || "No account",
      amount: (e) => formatAmount(Math.abs(e.amountMinor), currency),
    },
  );
  return (
    <section className="flex flex-col gap-2" aria-label="Expenses">
      <h2 className="font-medium">
        Expenses
        {report.count > report.expenses.length && (
          <span className="ml-2 text-sm font-normal text-muted-foreground">the latest {report.expenses.length} of {report.count}</span>
        )}
      </h2>
      <Surface>
        <Table className="table-fixed md:min-w-[46rem]">
          <TableHeader>
            <TableRow className="hover:bg-transparent">
              <SortableHead column="day" first="desc" sort={sort} onSort={onSort} filter={filterFor("day")} className="w-36 max-md:hidden">
                Day
              </SortableHead>
              <SortableHead column="merchant" sort={sort} onSort={onSort} filter={filterFor("merchant")}>
                Merchant
              </SortableHead>
              <SortableHead column="category" sort={sort} onSort={onSort} filter={filterFor("category")} className="w-[24%] max-md:hidden">
                Category
              </SortableHead>
              <SortableHead column="account" sort={sort} onSort={onSort} filter={filterFor("account")} className="w-[18%] max-md:hidden">
                Account
              </SortableHead>
              <SortableHead column="amount" first="desc" sort={sort} onSort={onSort} filter={filterFor("amount")} className="w-36 text-right">
                Amount ({currency})
              </SortableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {shown.length === 0 && <EmptyFilterRow colSpan={5} onClear={clearFilters} />}
            {shown.map((e) => {
              const day = dayLabel(e);
              const ids = e.categoryIds as (string | null)[];
              const category =
                ids.length > 1 ? (
                  <span className="inline-flex min-w-0 max-w-full items-center gap-1.5">
                    <span className="flex -space-x-0.5">
                      {ids.map((id) => (
                        <Dot key={id ?? "none"} tone={categoryTone(categories, id)} className="ring-2 ring-background" />
                      ))}
                    </span>
                    <span className="truncate">{ids.map((id) => categories.find((c) => c.id === id)?.name ?? "Uncategorized").join(", ")}</span>
                  </span>
                ) : (
                  <CategoryLabel categories={categories} id={ids[0] ?? null} short />
                );
              return (
                <TableRow key={e.expenseId} className="cursor-pointer" onClick={() => onOpen(e)}>
                  <TableCell className="text-muted-foreground max-md:hidden">{day}</TableCell>
                  <TableCell className="whitespace-normal">
                    <button
                      className="text-left font-medium outline-none hover:underline focus-visible:underline"
                      onClick={(event) => {
                        event.stopPropagation();
                        onOpen(e);
                      }}
                    >
                      {e.merchant || "Untitled expense"}
                    </button>
                    <div className="text-xs text-muted-foreground md:hidden">
                      <span className="inline-flex min-w-0 items-center gap-1.5">
                        {day} · {category}
                      </span>
                    </div>
                  </TableCell>
                  <TableCell className="overflow-hidden max-md:hidden">{category}</TableCell>
                  <TableCell className="overflow-hidden text-muted-foreground max-md:hidden">
                    <AccountLabel account={accounts.find((a) => a.id === e.accountId)} />
                  </TableCell>
                  <TableCell className="amount text-right">
                    {e.amountMinor < 0 ? "−" : ""}
                    {formatAmount(Math.abs(e.amountMinor), currency)}
                  </TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      </Surface>
    </section>
  );
}
