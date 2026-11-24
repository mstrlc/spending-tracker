import { useState } from "react";
import { useAccounts, useCategories, useExpenses, useMembers } from "../api/hooks";
import type { Category, Expense } from "../api/types";
import { PlusIcon } from "lucide-react";
import { AccountLabel, CategoryLabel, Dot, KindChip, ExpenseWhose, categoryTone, signedAmountClass, type Tone } from "../chips";
import { currentMonth, formatDay, formatMonth, todayIso } from "../lib/dates";
import { formatAmount } from "../lib/money";
import { useHousehold } from "../households/HouseholdPages";
import { MonthTitle, useMonthParam, useMonthShortcuts } from "../month";
import { rankSlices, ShareBar, type Slice } from "../shares";
import { EmptyState, ErrorText, Money, PageHeader, Sheet, SkeletonRows, Summary, Surface } from "../ui";
import { usePendingDeleteIds } from "../undo";
import { EmptyFilterRow, SortableHead, useTableSortAndFilter } from "../sorting";
import { Button } from "@/components/ui/button";
import { Kbd } from "@/components/ui/kbd";
import { Table, TableBody, TableCell, TableHeader, TableRow } from "@/components/ui/table";
import { cn } from "@/lib/utils";
import { ExpenseForm } from "./ExpenseForm";
import { spendingParts } from "./refunds";

/**
 * Splits a month's total into spending and investing (investment categories are not consumption). Refunds count
 * against their original, not on their own date (see `spendingParts`).
 */
export function expenseTotals(expenses: Expense[], categories: Category[]) {
  const investment = new Set(categories.filter((c) => c.kind === "investment").map((c) => c.id));
  let spent = 0;
  let invested = 0;
  for (const e of expenses) {
    for (const p of spendingParts(e)) {
      if (p.categoryId && investment.has(p.categoryId)) invested += p.amountMinor;
      else spent += p.amountMinor;
    }
  }
  return { spent, invested };
}

/**
 * The month's spending by top-level category (subcategories roll up into their parent), largest first.
 * Investing is left out and refunds net against their original's category, in the original's month.
 */
export function spendingByCategory(expenses: Expense[], categories: Category[]): Slice[] {
  const byId = new Map(categories.map((c) => [c.id, c]));
  const sums = new Map<string, number>();
  const tones = new Map<string, Tone>();
  for (const e of expenses) {
    for (const p of spendingParts(e)) {
      const category = p.categoryId ? byId.get(p.categoryId) : undefined;
      if (category?.kind === "investment") continue;
      const top = category?.parentId ? byId.get(category.parentId) : category;
      const name = top?.name ?? "Uncategorized";
      sums.set(name, (sums.get(name) ?? 0) + p.amountMinor);
      if (!tones.has(name)) tones.set(name, categoryTone(categories, top?.id));
    }
  }
  return rankSlices(sums, tones);
}

type Editor = { mode: "closed" } | { mode: "add" } | { mode: "edit"; expense: Expense };

export function ExpensesPage() {
  const household = useHousehold();
  const { month, from, to, goTo } = useMonthParam();

  const expenses = useExpenses(household.id, from, to);
  const categories = useCategories(household.id).data ?? [];
  const accounts = useAccounts(household.id).data ?? [];
  const members = useMembers(household.id).data ?? [];
  const [editor, setEditor] = useState<Editor>({ mode: "closed" });

  const pendingDeletes = usePendingDeleteIds();
  const rows = (expenses.data ?? []).filter((e) => !pendingDeletes.has(e.id));
  const { spent, invested } = expenseTotals(rows, categories);
  const categoryName = (e: Expense) =>
    e.lines && e.lines.length > 0
      ? [...new Set(e.lines.map((l) => categories.find((c) => c.id === l.categoryId)?.name ?? ""))].join(", ")
      : (categories.find((c) => c.id === e.categoryId)?.name ?? "");
  const accountName = (e: Expense) => accounts.find((a) => a.id === e.accountId)?.name ?? "";
  const whoseName = (e: Expense) =>
    e.paidByUserId ? (members.find((m) => m.userId === e.paidByUserId)?.displayName ?? "") : "Joint";
  const { sort, onSort, clearFilters, shown, filterFor } = useTableSortAndFilter(
    rows,
    {
      day: (e) => e.date,
      merchant: (e) => e.merchant,
      category: categoryName,
      account: accountName,
      whose: whoseName,
      amount: (e) => e.amountMinor,
    },
    {
      day: (e) => formatDay(e.date),
      merchant: (e) => e.merchant || "Untitled expense",
      category: (e) => categoryName(e) || "Uncategorized",
      account: accountName,
      whose: whoseName,
      amount: (e) => formatAmount(Math.abs(e.amountMinor), household.currency),
    },
  );
  const showPaidBy = members.length > 1;
  // New expenses default to today in the current month, otherwise to the first of the month being viewed.
  const defaultDate = month === currentMonth() ? todayIso() : from;
  const openAdd = () => setEditor({ mode: "add" });
  // The row most recently added or saved, highlighted briefly so the change is easy to spot.
  const [flashId, setFlashId] = useState<string | null>(null);

  useMonthShortcuts(month, goTo, openAdd);

  return (
    <>
      <PageHeader title={<MonthTitle month={month} goTo={goTo} />}>
        <Button size="lg" onClick={openAdd} aria-keyshortcuts="N">
          <PlusIcon /> Add expense
          <Kbd className="hidden bg-primary-foreground/15 text-primary-foreground md:inline-flex">N</Kbd>
        </Button>
      </PageHeader>

      <div className="flex flex-col gap-5">
        <Summary
          pending={expenses.isPending}
          items={[
            { label: "Spent", value: <Money currency={household.currency}>{formatAmount(spent, household.currency)}</Money> },
            { label: "Invested", value: <Money currency={household.currency}>{formatAmount(invested, household.currency)}</Money> },
            { label: "Expenses", value: <span className="amount">{expenses.data ? rows.length : "–"}</span> },
          ]}
        />
        <ShareBar label="Spending by category" slices={spendingByCategory(rows, categories)} />
      </div>

      <Surface>
        <ErrorText error={expenses.error} className="p-4" />
        {expenses.isPending && <SkeletonRows />}
        {expenses.data && rows.length === 0 && (
          <EmptyState
            title={`No expenses in ${formatMonth(month)}`}
            action={
              <Button onClick={openAdd}>
                <PlusIcon /> Add expense
              </Button>
            }
          >
            Add an expense, or pick another month above.
          </EmptyState>
        )}
        {rows.length > 0 && (
          <Table className="table-fixed md:min-w-[46rem]">
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <SortableHead column="day" first="desc" sort={sort} onSort={onSort} filter={filterFor("day")} className="w-28 max-md:hidden">
                  Day
                </SortableHead>
                <SortableHead column="merchant" sort={sort} onSort={onSort} filter={filterFor("merchant")}>
                  Merchant
                </SortableHead>
                <SortableHead column="category" sort={sort} onSort={onSort} filter={filterFor("category")} className="w-[17%] max-md:hidden">
                  Category
                </SortableHead>
                <SortableHead column="account" sort={sort} onSort={onSort} filter={filterFor("account")} className="w-[17%] max-md:hidden">
                  Account
                </SortableHead>
                {showPaidBy && (
                  <SortableHead column="whose" sort={sort} onSort={onSort} filter={filterFor("whose")} className="w-[11%] max-md:hidden">
                    Whose
                  </SortableHead>
                )}
                <SortableHead column="amount" first="desc" sort={sort} onSort={onSort} filter={filterFor("amount")} className="w-36 text-right">
                  Amount ({household.currency})
                </SortableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {shown.length === 0 && <EmptyFilterRow colSpan={showPaidBy ? 6 : 5} onClear={clearFilters} />}
              {shown.map((e) => {
                const splitIds = e.lines && e.lines.length > 0 ? [...new Set(e.lines.map((l) => l.categoryId))] : null;
                const categoryCell = splitIds ? (
                  <span className="inline-flex min-w-0 max-w-full items-center gap-1.5">
                    <span className="flex -space-x-0.5">
                      {splitIds.map((id) => (
                        <Dot key={id ?? "none"} tone={categoryTone(categories, id)} className="ring-2 ring-background" />
                      ))}
                    </span>
                    <span className="truncate">
                      {splitIds.map((id) => categories.find((c) => c.id === id)?.name ?? "Uncategorized").join(", ")}
                    </span>
                  </span>
                ) : (
                  <CategoryLabel categories={categories} id={e.categoryId} />
                );
                return (
                  <TableRow
                    key={e.id}
                    className={cn("cursor-pointer", e.id === flashId && "animate-flash")}
                    onClick={() => setEditor({ mode: "edit", expense: e })}
                    onAnimationEnd={() => setFlashId(null)}
                  >
                    <TableCell className="text-muted-foreground max-md:hidden">{formatDay(e.date)}</TableCell>
                    <TableCell className="whitespace-normal">
                      {/* The button makes the row reachable by keyboard; the row click is a larger mouse target. */}
                      <button
                        className="text-left font-medium outline-none hover:underline focus-visible:underline"
                        onClick={(event) => {
                          event.stopPropagation();
                          setEditor({ mode: "edit", expense: e });
                        }}
                      >
                        {e.merchant || "Untitled expense"}
                      </button>
                      {e.amountMinor < 0 && <KindChip kind="refund" className="ml-2 align-middle" />}
                      {e.notes && <div className="text-xs text-muted-foreground">{e.notes}</div>}
                      {e.refundOfId && (
                        <div className="text-xs text-muted-foreground">
                          {(() => {
                            const original = rows.find((r) => r.id === e.refundOfId);
                            return original ? `Refund of ${formatDay(original.date)}` : "Refund of an earlier expense";
                          })()}
                        </div>
                      )}
                      {e.refundedMinor > 0 && (
                        <div className="text-xs text-muted-foreground">
                          Refunded <span className="amount">{formatAmount(e.refundedMinor, household.currency)}</span> · net{" "}
                          <span className="amount">{formatAmount(e.amountMinor - e.refundedMinor, household.currency)}</span>
                        </div>
                      )}
                      <div className="text-xs text-muted-foreground md:hidden">
                        <span className="inline-flex min-w-0 items-center gap-1.5">
                          {formatDay(e.date)} · {categoryCell}
                        </span>
                      </div>
                    </TableCell>
                    <TableCell className="overflow-hidden max-md:hidden">
                      {categoryCell}
                    </TableCell>
                    <TableCell className="overflow-hidden text-muted-foreground max-md:hidden">
                      <AccountLabel account={accounts.find((a) => a.id === e.accountId)} />
                    </TableCell>
                    {showPaidBy && (
                      <TableCell className="overflow-hidden text-muted-foreground max-md:hidden">
                        <ExpenseWhose members={members} expense={e} />
                      </TableCell>
                    )}
                    <TableCell className={cn("amount text-right", signedAmountClass(-e.amountMinor))}>
                      {e.amountMinor < 0 ? "+" : ""}
                      {formatAmount(Math.abs(e.amountMinor), household.currency)}
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        )}
      </Surface>

      <Sheet
        open={editor.mode !== "closed"}
        onClose={() => setEditor({ mode: "closed" })}
        title={editor.mode === "edit" ? "Edit expense" : "Add expense"}
      >
        <ExpenseForm
          key={editor.mode === "edit" ? editor.expense.id : "new"}
          expense={editor.mode === "edit" ? editor.expense : undefined}
          defaultDate={defaultDate}
          onSaved={(saved) => setFlashId(saved.id)}
          onOpenExpense={(e) => setEditor({ mode: "edit", expense: e })}
          onDone={() => setEditor({ mode: "closed" })}
        />
      </Sheet>
    </>
  );
}
