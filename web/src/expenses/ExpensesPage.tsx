import { useState } from "react";
import { useAccounts, useCategories, useExpenses, useMembers } from "../api/hooks";
import type { Category, Expense } from "../api/types";
import { PlusIcon } from "lucide-react";
import { currentMonth, formatDay, formatMonth, todayIso } from "../lib/dates";
import { formatAmount } from "../lib/money";
import { useHousehold } from "../households/HouseholdPages";
import { MonthTitle, useMonthParam, useMonthShortcuts } from "../month";
import { rankSlices, ShareBar, type Slice } from "../shares";
import { EmptyState, ErrorText, Money, PageHeader, Sheet, SkeletonRows, Summary, Surface } from "../ui";
import { Button } from "@/components/ui/button";
import { Kbd } from "@/components/ui/kbd";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { cn } from "@/lib/utils";
import { ExpenseForm } from "./ExpenseForm";

/** Splits a month's total into spending and investing (investment categories are not consumption). */
export function expenseTotals(expenses: Expense[], categories: Category[]) {
  const investment = new Set(categories.filter((c) => c.kind === "investment").map((c) => c.id));
  let spent = 0;
  let invested = 0;
  for (const e of expenses) {
    if (e.categoryId && investment.has(e.categoryId)) invested += e.amountMinor;
    else spent += e.amountMinor;
  }
  return { spent, invested };
}

/**
 * The month's spending by top-level category (subcategories roll up into their parent), largest first.
 * Investing is left out and refunds net against their category.
 */
export function spendingByCategory(expenses: Expense[], categories: Category[]): Slice[] {
  const byId = new Map(categories.map((c) => [c.id, c]));
  const sums = new Map<string, number>();
  for (const e of expenses) {
    const category = e.categoryId ? byId.get(e.categoryId) : undefined;
    if (category?.kind === "investment") continue;
    const top = category?.parentId ? byId.get(category.parentId) : category;
    const name = top?.name ?? "Uncategorized";
    sums.set(name, (sums.get(name) ?? 0) + e.amountMinor);
  }
  return rankSlices(sums);
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

  const rows = expenses.data ?? [];
  const { spent, invested } = expenseTotals(rows, categories);
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
          <Table>
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <TableHead className="max-md:hidden">Day</TableHead>
                <TableHead>Merchant</TableHead>
                <TableHead className="max-md:hidden">Category</TableHead>
                <TableHead className="max-md:hidden">Account</TableHead>
                {showPaidBy && <TableHead className="max-md:hidden">Paid by</TableHead>}
                <TableHead className="text-right">Amount ({household.currency})</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((e) => {
                const category = categories.find((c) => c.id === e.categoryId);
                const parent = category?.parentId ? categories.find((c) => c.id === category.parentId) : undefined;
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
                      {e.notes && <div className="text-xs text-muted-foreground">{e.notes}</div>}
                      <div className="text-xs text-muted-foreground md:hidden">
                        {formatDay(e.date)} · {category?.name ?? "Uncategorized"}
                      </div>
                    </TableCell>
                    <TableCell className="max-md:hidden">
                      {category ? (
                        <>
                          {parent && <span className="text-muted-foreground">{parent.name} › </span>}
                          {category.name}
                        </>
                      ) : (
                        <span className="text-muted-foreground">Uncategorized</span>
                      )}
                    </TableCell>
                    <TableCell className="text-muted-foreground max-md:hidden">
                      {accounts.find((a) => a.id === e.accountId)?.name ?? ""}
                    </TableCell>
                    {showPaidBy && (
                      <TableCell className="text-muted-foreground max-md:hidden">
                        {members.find((m) => m.userId === e.paidByUserId)?.displayName ?? "Shared"}
                      </TableCell>
                    )}
                    <TableCell className="amount text-right">
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
          onDone={() => setEditor({ mode: "closed" })}
        />
      </Sheet>
    </>
  );
}
