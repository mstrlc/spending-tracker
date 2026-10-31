import { useState } from "react";
import { useAccounts, useCategories, useExpenses, useIncome, useMembers } from "../api/hooks";
import type { Income } from "../api/types";
import { expenseTotals } from "../expenses/ExpensesPage";
import { Plus } from "../icons";
import { currentMonth, formatDay, formatMonth, todayIso } from "../lib/dates";
import { formatAmount } from "../lib/money";
import { useHousehold } from "../households/HouseholdPages";
import { MonthTitle, useMonthParam, useMonthShortcuts } from "../month";
import { rankSlices, ShareBar, type Slice } from "../shares";
import { ErrorText, PageHeader, Sheet, SkeletonRows } from "../ui";
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
        <button onClick={openAdd} aria-keyshortcuts="N">
          <Plus /> Add income <kbd>N</kbd>
        </button>
      </PageHeader>

      <div className="overview">
        <dl className="summary">
          <div>
            <dt>Received</dt>
            <dd className="summary-primary">
              {formatAmount(received, household.currency)}
              <span className="currency">{household.currency}</span>
            </dd>
          </div>
          <div>
            <dt>Spent</dt>
            <dd>
              {expenses.data ? formatAmount(spent, household.currency) : "–"}
              <span className="currency">{household.currency}</span>
            </dd>
          </div>
          <div>
            <dt>
              Saved
              {expenses.data && savingsRate !== null && ` · ${savingsRate}%`}
            </dt>
            <dd>
              {expenses.data ? formatAmount(saved, household.currency) : "–"}
              <span className="currency">{household.currency}</span>
            </dd>
          </div>
        </dl>
        <ShareBar label="Income by source" slices={incomeBySource(rows)} />
      </div>

      <section className="surface">
        <ErrorText error={income.error} />
        {income.isPending && <SkeletonRows />}
        {income.data && rows.length === 0 && (
          <div className="empty">
            <p className="empty-title">No income in {formatMonth(month)}</p>
            <p className="muted">Add a salary or other payment, or pick another month above.</p>
            <button onClick={openAdd}>
              <Plus /> Add income
            </button>
          </div>
        )}
        {rows.length > 0 && (
          <div className="table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th className="hide-narrow">Day</th>
                  <th>Source</th>
                  <th className="hide-narrow">Account</th>
                  {showRecipient && <th className="hide-narrow">Received by</th>}
                  <th className="num">Amount ({household.currency})</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((i) => (
                  <tr
                    key={i.id}
                    className={i.id === flashId ? "clickable flash" : "clickable"}
                    onClick={() => setEditor({ mode: "edit", income: i })}
                    onAnimationEnd={() => setFlashId(null)}
                  >
                    <td className="nowrap muted hide-narrow">{formatDay(i.date)}</td>
                    <td>
                      {/* The button makes the row reachable by keyboard; the row click is a larger mouse target. */}
                      <button
                        className="row-link"
                        onClick={(event) => {
                          event.stopPropagation();
                          setEditor({ mode: "edit", income: i });
                        }}
                      >
                        {i.source || "Untitled income"}
                      </button>
                      {i.notes && <div className="muted small">{i.notes}</div>}
                      <div className="muted small show-narrow">{formatDay(i.date)}</div>
                    </td>
                    <td className="hide-narrow muted">{accounts.find((a) => a.id === i.accountId)?.name ?? ""}</td>
                    {showRecipient && (
                      <td className="hide-narrow muted">
                        {members.find((m) => m.userId === i.recipientUserId)?.displayName ?? "Shared"}
                      </td>
                    )}
                    <td className="num">
                      {i.amountMinor < 0 ? "−" : ""}
                      {formatAmount(Math.abs(i.amountMinor), household.currency)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

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
