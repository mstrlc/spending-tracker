import { useState } from "react";
import { AccountLabel, Avatar, KindIcon, PersonLabel } from "../chips";
import { PlusIcon } from "lucide-react";
import { useAccounts, useBalances, useCategories, useExpenses, useMembers, useSettlements, useSplitRules } from "../api/hooks";
import type { BalanceEntry, Debt, Expense, Settlement, SplitRule } from "../api/types";
import { categoryLabel } from "../categories/tree";
import { ExpenseForm } from "../expenses/ExpenseForm";
import { useHousehold } from "../households/HouseholdPages";
import { currentMonth, formatDate, formatDay, formatMonth, todayIso } from "../lib/dates";
import { formatAmount } from "../lib/money";
import { MonthTitle, useMonthParam, useMonthShortcuts } from "../month";
import { EmptyState, ErrorText, Money, PageHeader, PageIntro, Sheet, SkeletonRows, Summary, Surface } from "../ui";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { SettlementForm } from "./SettlementForm";
import { SplitRuleForm } from "./SplitRuleForm";
import { describeShares, memberName, owesLine } from "./split";

type Editor =
  | { mode: "closed" }
  | { mode: "settle"; owed?: Debt }
  | { mode: "settlement"; settlement: Settlement }
  | { mode: "expense"; expense: Expense }
  | { mode: "rule"; rule?: SplitRule };

const TITLES: Record<Editor["mode"], string> = {
  closed: "",
  settle: "Record settlement",
  settlement: "Edit settlement",
  expense: "Edit expense",
  rule: "Split",
};

export function BalancesPage() {
  const household = useHousehold();
  const { month, from, to, goTo } = useMonthParam();
  const members = useMembers(household.id);
  const balances = useBalances(household.id, from, to);
  // What the month's entries open: the expense or settlement itself.
  const expenses = useExpenses(household.id, from, to).data ?? [];
  const settlements = useSettlements(household.id, from, to).data ?? [];
  const rules = useSplitRules(household.id);
  const accounts = useAccounts(household.id).data ?? [];
  const categories = useCategories(household.id).data ?? [];
  const [editor, setEditor] = useState<Editor>({ mode: "closed" });
  const close = () => setEditor({ mode: "closed" });

  const people = members.data ?? [];
  const owed = balances.data?.balances ?? [];
  const entries = balances.data?.entries ?? [];
  const contributions = balances.data?.jointContributions ?? [];
  const settle = () => setEditor({ mode: "settle", owed: owed[0] });
  useMonthShortcuts(month, goTo, settle);

  function open(entry: BalanceEntry) {
    if (entry.kind === "settlement") {
      const settlement = settlements.find((s) => s.id === entry.id);
      if (settlement) setEditor({ mode: "settlement", settlement });
    } else {
      const expense = expenses.find((e) => e.id === entry.id);
      if (expense) setEditor({ mode: "expense", expense });
    }
  }

  if (members.data && people.length < 2) {
    return (
      <>
        <PageHeader title="Balances" />
        <Surface>
          <EmptyState title="Nobody to owe">
            Balances are between the people in a household. Add someone on the Members page, even without a login.
          </EmptyState>
        </Surface>
      </>
    );
  }

  return (
    <>
      <PageHeader title={<MonthTitle month={month} goTo={goTo} />}>
        <Button size="lg" onClick={settle} disabled={!members.data}>
          <PlusIcon /> Record settlement
        </Button>
      </PageHeader>
      <PageIntro>
        Paying for joint spending from your own account means the others owe you their share; spending your own money from a joint
        account means you owe the others theirs. Settlements pay it back.
      </PageIntro>

      <ErrorText error={balances.error} />
      {balances.data && (
        <Summary
          items={
            owed.length === 0
              ? [{ label: "Balance", value: "All square" }]
              : owed.map((debt) => ({
                  label: owesLine(debt, people),
                  value: <Money currency={household.currency}>{formatAmount(debt.amountMinor, household.currency)}</Money>,
                }))
          }
        />
      )}

      <Surface>
        {balances.isPending && <SkeletonRows rows={4} />}
        {balances.data && entries.length === 0 && (
          <EmptyState title={`Nothing owed from ${formatMonth(month)}`}>
            Expenses show up here when who paid and whose they are don't match. Pick another month above.
          </EmptyState>
        )}
        {entries.length > 0 && (
          <Table>
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <TableHead className="max-md:hidden">Day</TableHead>
                <TableHead>What</TableHead>
                <TableHead>Effect</TableHead>
                <TableHead className="text-right">Amount ({household.currency})</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {entries.map((entry) => (
                <TableRow key={`${entry.kind}-${entry.id}`} className="cursor-pointer" onClick={() => open(entry)}>
                  <TableCell className="align-top text-muted-foreground max-md:hidden">{formatDay(entry.date)}</TableCell>
                  <TableCell className="whitespace-normal align-top">
                    <div className="flex items-center gap-2">
                      <KindIcon kind={entry.kind === "settlement" ? "settlement" : "expense"} className="size-5 [&>svg]:size-3" />
                      <button
                        className="text-left font-medium outline-none hover:underline focus-visible:underline"
                        onClick={(event) => {
                          event.stopPropagation();
                          open(entry);
                        }}
                      >
                        {entry.kind === "settlement" ? "Settlement" : entry.label || "Untitled expense"}
                      </button>
                    </div>
                    <div className="text-xs text-muted-foreground">
                      {entry.kind === "settlement"
                        ? entry.label
                        : `Expense of ${formatAmount(entry.amountMinor, household.currency)}`}
                    </div>
                    <div className="text-xs text-muted-foreground md:hidden">{formatDay(entry.date)}</div>
                  </TableCell>
                  <TableCell className="whitespace-normal align-top text-muted-foreground">
                    {entry.debts.map((debt, i) => (
                      <div key={i} className="flex items-center gap-1.5">
                        <span className="flex -space-x-1">
                          <Avatar members={people} userId={debt.fromUserId} className="ring-2 ring-background" />
                          <Avatar members={people} userId={debt.toUserId} className="ring-2 ring-background" />
                        </span>
                        {entry.kind === "settlement"
                          ? `${memberName(people, debt.toUserId)} paid ${memberName(people, debt.fromUserId)} back`
                          : owesLine(debt, people)}
                      </div>
                    ))}
                  </TableCell>
                  <TableCell className="amount text-right align-top">
                    {entry.debts.map((debt, i) => (
                      <div key={i}>
                        {debt.amountMinor < 0 ? "−" : ""}
                        {formatAmount(Math.abs(debt.amountMinor), household.currency)}
                      </div>
                    ))}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </Surface>

      {contributions.length > 0 && (
        <section className="flex flex-col gap-3" aria-labelledby="joint-heading">
          <div>
            <h2 id="joint-heading" className="text-lg font-medium">
              Put into joint accounts
            </h2>
            <p className="text-sm text-muted-foreground">
              Money each of you moved into joint accounts in {formatMonth(month)}, less what you took out. It isn't a debt.
            </p>
          </div>
          <Surface>
            <Table>
              <TableHeader>
                <TableRow className="hover:bg-transparent">
                  <TableHead>Who</TableHead>
                  <TableHead>Joint account</TableHead>
                  <TableHead className="text-right">Net ({household.currency})</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {contributions.map((c) => (
                  <TableRow key={`${c.userId}-${c.accountId}`}>
                    <TableCell className="font-medium">
                      <PersonLabel members={people} userId={c.userId} />
                    </TableCell>
                    <TableCell className="text-muted-foreground">
                      <AccountLabel account={accounts.find((a) => a.id === c.accountId)} />
                    </TableCell>
                    <TableCell className="amount text-right">
                      {c.amountMinor < 0 ? "−" : ""}
                      {formatAmount(Math.abs(c.amountMinor), household.currency)}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Surface>
        </section>
      )}

      <section className="flex flex-col gap-3" aria-labelledby="splits-heading">
        <div className="flex items-center justify-between gap-3">
          <h2 id="splits-heading" className="text-lg font-medium">
            How joint spending is split
          </h2>
          <Button variant="outline" onClick={() => setEditor({ mode: "rule" })} disabled={!members.data}>
            <PlusIcon /> Add split
          </Button>
        </div>
        <Surface>
          <ErrorText error={rules.error} className="p-4" />
          {rules.isPending && <SkeletonRows rows={2} />}
          {rules.data?.length === 0 && (
            <EmptyState title="Split equally">
              Until you add a split, everyone has an equal share. Add one for the household (by income, say), and others for categories
              that split differently.
            </EmptyState>
          )}
          {rules.data && rules.data.length > 0 && (
            <Table>
              <TableHeader>
                <TableRow className="hover:bg-transparent">
                  <TableHead>Applies to</TableHead>
                  <TableHead>From</TableHead>
                  <TableHead>Shares</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {rules.data.map((rule) => (
                  <TableRow key={rule.id} className="cursor-pointer" onClick={() => setEditor({ mode: "rule", rule })}>
                    <TableCell className="whitespace-normal">
                      <button
                        className="text-left font-medium outline-none hover:underline focus-visible:underline"
                        onClick={(event) => {
                          event.stopPropagation();
                          setEditor({ mode: "rule", rule });
                        }}
                      >
                        {rule.categoryId ? categoryLabel(categories, rule.categoryId) || "Deleted category" : "All joint spending"}
                      </button>
                    </TableCell>
                    <TableCell className="text-muted-foreground">{formatDate(rule.validFrom)}</TableCell>
                    <TableCell className="whitespace-normal text-muted-foreground">{describeShares(rule.shares, people)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </Surface>
      </section>

      <Sheet open={editor.mode !== "closed"} onClose={close} title={TITLES[editor.mode]}>
        {editor.mode === "settle" && (
          <SettlementForm owed={editor.owed} defaultDate={month === currentMonth() ? todayIso() : to} onDone={close} />
        )}
        {editor.mode === "settlement" && <SettlementForm key={editor.settlement.id} settlement={editor.settlement} onDone={close} />}
        {editor.mode === "expense" && <ExpenseForm key={editor.expense.id} expense={editor.expense} onDone={close} />}
        {editor.mode === "rule" && <SplitRuleForm key={editor.rule?.id ?? "new"} rule={editor.rule} onDone={close} />}
      </Sheet>
    </>
  );
}
