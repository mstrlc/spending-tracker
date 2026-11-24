import { Fragment, useState } from "react";
import { useAccounts, useCategories, useExpenses, useIncome, useMembers } from "../api/hooks";
import type { Category, Income } from "../api/types";
import { expenseTotals } from "../expenses/ExpensesPage";
import { PlusIcon } from "lucide-react";
import { AccountLabel, CategoryLabel, KindIcon, PersonLabel, categoryTone, type Tone } from "../chips";
import { categoryLabel } from "../categories/tree";
import { currentMonth, formatDay, formatMonth, todayIso } from "../lib/dates";
import { formatAmount } from "../lib/money";
import { useHousehold } from "../households/HouseholdPages";
import { MonthTitle, useMonthParam, useMonthShortcuts } from "../month";
import { rankSlices, ShareBar, type Slice } from "../shares";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { EmptyState, ErrorText, Money, PageHeader, Sheet, SkeletonRows, Summary, Surface } from "../ui";
import { usePendingDeleteIds } from "../undo";
import { EmptyFilterRow, SortableHead, sortRows, useTableSortAndFilter } from "../sorting";
import type { IncomeRow } from "./net";
import { Button } from "@/components/ui/button";
import { Kbd } from "@/components/ui/kbd";
import { Table, TableBody, TableCell, TableHeader, TableRow } from "@/components/ui/table";
import { cn } from "@/lib/utils";
import { IncomeForm } from "./IncomeForm";
import { groupIncome, incomeByPerson, incomeTotals, netAmount } from "./net";

/** The month's earnings by source, largest first; clawbacks net against their source, deductions aren't sources. */
export function incomeBySource(income: Income[]): Slice[] {
  const sums = new Map<string, number>();
  for (const i of income) {
    if (i.kind === "deduction") continue;
    const name = i.source || "No source";
    sums.set(name, (sums.get(name) ?? 0) + i.amountMinor);
  }
  return rankSlices(sums);
}

/** The month's earnings by top-level category (subcategories roll up into their parent), largest first. */
export function incomeByCategory(income: Income[], categories: Category[]): Slice[] {
  const byId = new Map(categories.map((c) => [c.id, c]));
  const sums = new Map<string, number>();
  const tones = new Map<string, Tone>();
  for (const i of income) {
    if (i.kind === "deduction") continue;
    const category = i.categoryId ? byId.get(i.categoryId) : undefined;
    const top = category?.parentId ? byId.get(category.parentId) : category;
    const name = top?.name ?? "Uncategorized";
    sums.set(name, (sums.get(name) ?? 0) + i.amountMinor);
    if (!tones.has(name)) tones.set(name, categoryTone(categories, top?.id));
  }
  return rankSlices(sums, tones);
}

type Editor = { mode: "closed" } | { mode: "add" } | { mode: "edit"; income: Income };

export function IncomePage() {
  const household = useHousehold();
  const { month, from, to, goTo } = useMonthParam();

  const income = useIncome(household.id, from, to);
  // Spending for the same month, so the page can say what was left after it.
  const expenses = useExpenses(household.id, from, to);
  const categories = useCategories(household.id).data ?? [];
  const accounts = useAccounts(household.id).data ?? [];
  const members = useMembers(household.id).data ?? [];
  const [editor, setEditor] = useState<Editor>({ mode: "closed" });

  const pendingDeletes = usePendingDeleteIds();
  const rows = (income.data ?? []).filter(
    (i) => !pendingDeletes.has(i.id) && !(i.deductedFromId && pendingDeletes.has(i.deductedFromId)),
  );
  const { earned, deducted, net } = incomeTotals(rows);
  const { spent } = expenseTotals(expenses.data ?? [], categories);
  // Investing counts as saved, not spent; deductions are neither.
  const saved = net - spent;
  const savingsRate = net > 0 ? Math.round((saved / net) * 100) : null;
  const money = (amountMinor: number) => (
    <Money currency={household.currency}>
      {amountMinor < 0 ? "−" : ""}
      {formatAmount(Math.abs(amountMinor), household.currency)}
    </Money>
  );
  const showRecipient = members.length > 1;
  // Sorting orders the lines within each person's group; linked deductions stay under their earning.
  const categoryName = (i: Income) => (i.kind === "deduction" ? "" : categoryLabel(categories, i.categoryId));
  const accountName = (i: Income) => accounts.find((a) => a.id === i.accountId)?.name ?? "";
  const { sort, onSort, clearFilters, filtered, filterFor } = useTableSortAndFilter(
    rows,
    {
      day: (i) => i.date,
      source: (i) => i.source,
      category: categoryName,
      account: accountName,
      amount: (i) => netAmount(i),
    },
    {
      day: (i) => formatDay(i.date),
      source: (i) => i.source || (i.kind === "deduction" ? "Untitled deduction" : "Untitled income"),
      category: categoryName,
      account: accountName,
      amount: (i) => formatAmount(Math.abs(i.amountMinor), household.currency),
    },
  );
  const sortLines = (lines: IncomeRow[]) =>
    sortRows(lines, sort, {
      day: (l) => l.income.date,
      source: (l) => l.income.source,
      category: (l) => categoryName(l.income),
      account: (l) => accountName(l.income),
      amount: (l) => netAmount(l.income),
    });
  const defaultDate = month === currentMonth() ? todayIso() : from;
  const openAdd = () => setEditor({ mode: "add" });
  const [flashId, setFlashId] = useState<string | null>(null);
  // The share bar by kind of money or by who paid; by source until something has a category.
  const [breakdown, setBreakdown] = useState<"category" | "source">("category");
  const hasCategories = rows.some((i) => i.kind !== "deduction" && i.categoryId !== null);

  useMonthShortcuts(month, goTo, openAdd);

  return (
    <>
      <PageHeader title={<MonthTitle month={month} goTo={goTo} />}>
        <Button size="lg" onClick={openAdd} aria-keyshortcuts="N">
          <PlusIcon /> Add income
          <Kbd className="hidden bg-primary-foreground/15 text-primary-foreground md:inline-flex">N</Kbd>
        </Button>
      </PageHeader>

      <div className="flex flex-col gap-5">
        <Summary
          pending={income.isPending || expenses.isPending}
          items={[
            // Gross and deductions only add something once there's a deduction; until then net is what was earned.
            { label: deducted !== 0 ? "Net" : "Earned", value: money(net) },
            deducted !== 0 && { label: "Earned", value: money(earned) },
            deducted !== 0 && { label: "Deducted", value: money(-deducted) },
            {
              label: "Spent",
              value: <Money currency={household.currency}>{expenses.data ? formatAmount(spent, household.currency) : "–"}</Money>,
            },
            {
              label: `Saved${expenses.data && savingsRate !== null ? ` · ${savingsRate}%` : ""}`,
              value: <Money currency={household.currency}>{expenses.data ? formatAmount(saved, household.currency) : "–"}</Money>,
            },
          ]}
        />
        {hasCategories ? (
          <div className="flex flex-col gap-3">
            <Tabs value={breakdown} onValueChange={(v) => setBreakdown(v as "category" | "source")}>
              <TabsList aria-label="Break income down by">
                <TabsTrigger value="category">By category</TabsTrigger>
                <TabsTrigger value="source">By source</TabsTrigger>
              </TabsList>
            </Tabs>
            {breakdown === "category" ? (
              <ShareBar label="Income by category" slices={incomeByCategory(rows, categories)} />
            ) : (
              <ShareBar label="Income by source" slices={incomeBySource(rows)} />
            )}
          </div>
        ) : (
          <ShareBar label="Income by source" slices={incomeBySource(rows)} />
        )}
      </div>

      <Surface>
        <ErrorText error={income.error} className="p-4" />
        {income.isPending && <SkeletonRows />}
        {income.data && rows.length === 0 && (
          <EmptyState
            title={`No income in ${formatMonth(month)}`}
            action={
              <Button onClick={openAdd}>
                <PlusIcon /> Add income
              </Button>
            }
          >
            Add a salary or other payment, or a deduction such as an insurance advance, or pick another month above.
          </EmptyState>
        )}
        {rows.length > 0 && (
          <Table className="table-fixed md:min-w-[36rem]">
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <SortableHead column="day" first="desc" sort={sort} onSort={onSort} filter={filterFor("day")} className="w-28 max-md:hidden">
                  Day
                </SortableHead>
                <SortableHead column="source" sort={sort} onSort={onSort} filter={filterFor("source")}>
                  Source
                </SortableHead>
                <SortableHead column="category" sort={sort} onSort={onSort} filter={filterFor("category")} className="w-[22%] max-md:hidden">
                  Category
                </SortableHead>
                <SortableHead column="account" sort={sort} onSort={onSort} filter={filterFor("account")} className="w-[20%] max-md:hidden">
                  Account
                </SortableHead>
                <SortableHead column="amount" first="desc" sort={sort} onSort={onSort} filter={filterFor("amount")} className="w-36 text-right">
                  Amount ({household.currency})
                </SortableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {filtered.length === 0 && <EmptyFilterRow colSpan={5} onClear={clearFilters} />}
              {filtered.length > 0 && (showRecipient
                ? incomeByPerson(groupIncome(filtered), members.map((m) => m.userId))
                : [{ userId: null, rows: groupIncome(filtered), net: 0 }]
              ).map((person) => (
                <Fragment key={person.userId ?? "shared"}>
                  {/* With more than one member, each person's lines come under their name and net total. */}
                  {showRecipient && (
                    <TableRow className="bg-muted/40 hover:bg-muted/40">
                      <TableCell colSpan={5} className="py-1.5">
                        <div className="flex items-center justify-between gap-3 text-sm font-medium">
                          <PersonLabel members={members} userId={person.userId} jointLabel="Shared" />
                          <span className="amount">
                            {person.net < 0 ? "−" : ""}
                            {formatAmount(Math.abs(person.net), household.currency)}
                          </span>
                        </div>
                      </TableCell>
                    </TableRow>
                  )}
                  {sortLines(person.rows).map(({ income: i, linkedDeductions }) => {
                const renderRow = (item: Income, nested: boolean) => (
                  <TableRow
                    key={item.id}
                    className={cn("cursor-pointer", item.id === flashId && "animate-flash", nested && "text-muted-foreground")}
                    onClick={() => setEditor({ mode: "edit", income: item })}
                    onAnimationEnd={() => setFlashId(null)}
                  >
                    <TableCell className="text-muted-foreground max-md:hidden">{nested ? "" : formatDay(item.date)}</TableCell>
                    <TableCell className={cn("whitespace-normal", nested && "pl-6")}>
                      <div className="flex items-start gap-2.5">
                        <KindIcon kind={item.kind === "deduction" ? "deduction" : "income"} className={cn("mt-px", nested && "size-5")} />
                        <div className="min-w-0">
                          {/* The button makes the row reachable by keyboard; the row click is a larger mouse target. */}
                          <button
                            className={cn(
                              "text-left outline-none hover:underline focus-visible:underline",
                              nested ? "font-normal" : "font-medium",
                            )}
                            onClick={(event) => {
                              event.stopPropagation();
                              setEditor({ mode: "edit", income: item });
                            }}
                          >
                            {item.source || (item.kind === "deduction" ? "Untitled deduction" : "Untitled income")}
                          </button>
                          {item.deductedMinor !== 0 && (
                            <div className="text-xs text-muted-foreground">
                              Gross <span className="amount">{formatAmount(item.amountMinor, household.currency)}</span> → net{" "}
                              <span className="amount">{formatAmount(item.amountMinor - item.deductedMinor, household.currency)}</span>
                            </div>
                          )}
                          {item.notes && <div className="text-xs text-muted-foreground">{item.notes}</div>}
                          {!nested && <div className="text-xs text-muted-foreground md:hidden">{formatDay(item.date)}</div>}
                        </div>
                      </div>
                    </TableCell>
                    <TableCell className="overflow-hidden max-md:hidden">
                      {item.kind !== "deduction" && <CategoryLabel categories={categories} id={item.categoryId} />}
                    </TableCell>
                    <TableCell className="overflow-hidden text-muted-foreground max-md:hidden">
                      <AccountLabel account={accounts.find((a) => a.id === item.accountId)} />
                    </TableCell>
                    <TableCell className={cn("amount text-right", item.kind === "deduction" && "text-muted-foreground")}>
                      {netAmount(item) < 0 ? "−" : ""}
                      {formatAmount(Math.abs(item.amountMinor), household.currency)}
                    </TableCell>
                  </TableRow>
                );
                return [renderRow(i, false), ...linkedDeductions.map((d) => renderRow(d, true))];
                  })}
                </Fragment>
              ))}
            </TableBody>
          </Table>
        )}
      </Surface>

      <Sheet
        open={editor.mode !== "closed"}
        onClose={() => setEditor({ mode: "closed" })}
        title={editor.mode === "edit" ? (editor.income.kind === "deduction" ? "Edit deduction" : "Edit income") : "Add income"}
      >
        <IncomeForm
          key={editor.mode === "edit" ? editor.income.id : "new"}
          income={editor.mode === "edit" ? editor.income : undefined}
          defaultDate={defaultDate}
          onEditIncome={(item) => setEditor({ mode: "edit", income: item })}
          onSaved={(saved) => setFlashId(saved.id)}
          onDone={() => setEditor({ mode: "closed" })}
        />
      </Sheet>
    </>
  );
}
