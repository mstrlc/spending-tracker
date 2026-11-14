import { useState } from "react";
import { useAccounts, useCategories, useExpenses, useIncome, useMembers } from "../api/hooks";
import type { Income } from "../api/types";
import { expenseTotals } from "../expenses/ExpensesPage";
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
import { IncomeForm } from "./IncomeForm";

/** The month's income by source, largest first; clawbacks net against their source. */
export function incomeBySource(income: Income[]): Slice[] {
  const sums = new Map<string, number>();
  for (const i of income) {
    const name = i.source || "No source";
    sums.set(name, (sums.get(name) ?? 0) + i.amountMinor);
  }
  return rankSlices(sums);
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

  const rows = income.data ?? [];
  const received = rows.reduce((sum, i) => sum + i.amountMinor, 0);
  const { spent } = expenseTotals(expenses.data ?? [], categories);
  // Investing counts as saved, not spent.
  const saved = received - spent;
  const savingsRate = received > 0 ? Math.round((saved / received) * 100) : null;
  const showRecipient = members.length > 1;
  const defaultDate = month === currentMonth() ? todayIso() : from;
  const openAdd = () => setEditor({ mode: "add" });
  const [flashId, setFlashId] = useState<string | null>(null);

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
          items={[
            { label: "Received", value: <Money currency={household.currency}>{formatAmount(received, household.currency)}</Money> },
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
        <ShareBar label="Income by source" slices={incomeBySource(rows)} />
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
            Add a salary or other payment, or pick another month above.
          </EmptyState>
        )}
        {rows.length > 0 && (
          <Table>
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <TableHead className="max-md:hidden">Day</TableHead>
                <TableHead>Source</TableHead>
                <TableHead className="max-md:hidden">Account</TableHead>
                {showRecipient && <TableHead className="max-md:hidden">Received by</TableHead>}
                <TableHead className="text-right">Amount ({household.currency})</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((i) => (
                <TableRow
                  key={i.id}
                  className={cn("cursor-pointer", i.id === flashId && "animate-flash")}
                  onClick={() => setEditor({ mode: "edit", income: i })}
                  onAnimationEnd={() => setFlashId(null)}
                >
                  <TableCell className="text-muted-foreground max-md:hidden">{formatDay(i.date)}</TableCell>
                  <TableCell className="whitespace-normal">
                    {/* The button makes the row reachable by keyboard; the row click is a larger mouse target. */}
                    <button
                      className="text-left font-medium outline-none hover:underline focus-visible:underline"
                      onClick={(event) => {
                        event.stopPropagation();
                        setEditor({ mode: "edit", income: i });
                      }}
                    >
                      {i.source || "Untitled income"}
                    </button>
                    {i.notes && <div className="text-xs text-muted-foreground">{i.notes}</div>}
                    <div className="text-xs text-muted-foreground md:hidden">{formatDay(i.date)}</div>
                  </TableCell>
                  <TableCell className="text-muted-foreground max-md:hidden">
                    {accounts.find((a) => a.id === i.accountId)?.name ?? ""}
                  </TableCell>
                  {showRecipient && (
                    <TableCell className="text-muted-foreground max-md:hidden">
                      {members.find((m) => m.userId === i.recipientUserId)?.displayName ?? "Shared"}
                    </TableCell>
                  )}
                  <TableCell className="amount text-right">
                    {i.amountMinor < 0 ? "−" : ""}
                    {formatAmount(Math.abs(i.amountMinor), household.currency)}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </Surface>

      <Sheet
        open={editor.mode !== "closed"}
        onClose={() => setEditor({ mode: "closed" })}
        title={editor.mode === "edit" ? "Edit income" : "Add income"}
      >
        <IncomeForm
          key={editor.mode === "edit" ? editor.income.id : "new"}
          income={editor.mode === "edit" ? editor.income : undefined}
          defaultDate={defaultDate}
          onSaved={(saved) => setFlashId(saved.id)}
          onDone={() => setEditor({ mode: "closed" })}
        />
      </Sheet>
    </>
  );
}
