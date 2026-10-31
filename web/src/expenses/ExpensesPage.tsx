import { useState } from "react";
import { useAccounts, useCategories, useExpenses, useMembers } from "../api/hooks";
import type { Category, Expense } from "../api/types";
import { Plus } from "../icons";
import { currentMonth, formatDay, formatMonth, todayIso } from "../lib/dates";
import { formatAmount } from "../lib/money";
import { useHousehold } from "../households/HouseholdPages";
import { MonthTitle, useMonthParam, useMonthShortcuts } from "../month";
import { rankSlices, ShareBar, type Slice } from "../shares";
import { ErrorText, PageHeader, Sheet, SkeletonRows } from "../ui";
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
        <button onClick={openAdd} aria-keyshortcuts="N">
          <Plus /> Add expense <kbd>N</kbd>
        </button>
      </PageHeader>

      <div className="overview">
        <dl className="summary">
          <div>
            <dt>Spent</dt>
            <dd className="summary-primary">
              {formatAmount(spent, household.currency)}
              <span className="currency">{household.currency}</span>
            </dd>
          </div>
          <div>
            <dt>Invested</dt>
            <dd>
              {formatAmount(invested, household.currency)}
              <span className="currency">{household.currency}</span>
            </dd>
          </div>
          <div>
            <dt>Expenses</dt>
            <dd>{expenses.data ? rows.length : "–"}</dd>
          </div>
        </dl>
        <ShareBar label="Spending by category" slices={spendingByCategory(rows, categories)} />
      </div>

      <section className="surface">
        <ErrorText error={expenses.error} />
        {expenses.isPending && <SkeletonRows />}
        {expenses.data && rows.length === 0 && (
          <div className="empty">
            <p className="empty-title">No expenses in {formatMonth(month)}</p>
            <p className="muted">Add an expense, or pick another month above.</p>
            <button onClick={openAdd}>
              <Plus /> Add expense
            </button>
          </div>
        )}
        {rows.length > 0 && (
          <div className="table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th className="hide-narrow">Day</th>
                  <th>Merchant</th>
                  <th className="hide-narrow">Category</th>
                  <th className="hide-narrow">Account</th>
                  {showPaidBy && <th className="hide-narrow">Paid by</th>}
                  <th className="num">Amount ({household.currency})</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((e) => {
                  const category = categories.find((c) => c.id === e.categoryId);
                  const parent = category?.parentId ? categories.find((c) => c.id === category.parentId) : undefined;
                  return (
                    <tr
                      key={e.id}
                      className={e.id === flashId ? "clickable flash" : "clickable"}
                      onClick={() => setEditor({ mode: "edit", expense: e })}
                      onAnimationEnd={() => setFlashId(null)}
                    >
                      <td className="nowrap muted hide-narrow">{formatDay(e.date)}</td>
                      <td>
                        {/* The button makes the row reachable by keyboard; the row click is a larger mouse target. */}
                        <button
                          className="row-link"
                          onClick={(event) => {
                            event.stopPropagation();
                            setEditor({ mode: "edit", expense: e });
                          }}
                        >
                          {e.merchant || "Untitled expense"}
                        </button>
                        {e.notes && <div className="muted small">{e.notes}</div>}
                        <div className="muted small show-narrow">
                          {formatDay(e.date)} · {category?.name ?? "Uncategorized"}
                        </div>
                      </td>
                      <td className="hide-narrow">
                        {category ? (
                          <>
                            {parent && <span className="muted">{parent.name} › </span>}
                            {category.name}
                          </>
                        ) : (
                          <span className="muted">Uncategorized</span>
                        )}
                      </td>
                      <td className="hide-narrow muted">{accounts.find((a) => a.id === e.accountId)?.name ?? ""}</td>
                      {showPaidBy && (
                        <td className="hide-narrow muted">
                          {members.find((m) => m.userId === e.paidByUserId)?.displayName ?? "Shared"}
                        </td>
                      )}
                      <td className="num">
                        {e.amountMinor < 0 ? "+" : ""}
                        {formatAmount(Math.abs(e.amountMinor), household.currency)}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </section>

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
