import { useState } from "react";
import { useSearchParams } from "react-router";
import { useAccounts, useCategories, useDeleteExpense, useExpenses, useMembers } from "../api/hooks";
import type { Category, Expense } from "../api/types";
import { categoryLabel } from "../categories/tree";
import { currentMonth, formatMonth, isMonth, monthRange, shiftMonth } from "../lib/dates";
import { formatMoney } from "../lib/money";
import { useHousehold } from "../households/HouseholdPages";
import { ErrorText, Loading } from "../ui";
import { ExpenseForm } from "./ExpenseForm";

/** Splits a month's total into spending and investing (investment categories are not consumption). */
function totals(expenses: Expense[], categories: Category[]) {
  const investment = new Set(categories.filter((c) => c.kind === "investment").map((c) => c.id));
  let spent = 0;
  let invested = 0;
  for (const e of expenses) {
    if (e.categoryId && investment.has(e.categoryId)) invested += e.amountMinor;
    else spent += e.amountMinor;
  }
  return { spent, invested };
}

export function ExpensesPage() {
  const household = useHousehold();
  const [params, setParams] = useSearchParams();
  const monthParam = params.get("month");
  const month = isMonth(monthParam) ? monthParam : currentMonth();
  const { from, to } = monthRange(month);

  const expenses = useExpenses(household.id, from, to);
  const categories = useCategories(household.id).data ?? [];
  const accounts = useAccounts(household.id).data ?? [];
  const members = useMembers(household.id).data ?? [];
  const remove = useDeleteExpense(household.id);
  const [editing, setEditing] = useState<Expense | null>(null);

  const money = (minor: number) => formatMoney(minor, household.currency);
  const goTo = (m: string) => setParams(m === currentMonth() ? {} : { month: m });
  const { spent, invested } = totals(expenses.data ?? [], categories);

  function confirmDelete(expense: Expense) {
    const label = expense.merchant || "this expense";
    if (window.confirm(`Delete ${label} (${money(expense.amountMinor)})?`)) {
      remove.mutate({ id: expense.id, version: expense.version });
      if (editing?.id === expense.id) setEditing(null);
    }
  }

  return (
    <>
      <section className="card">
        <h2>{editing ? "Edit expense" : "Add expense"}</h2>
        {/* key: remount with fresh state when switching between rows */}
        <ExpenseForm key={editing?.id ?? "new"} expense={editing ?? undefined} onDone={() => setEditing(null)} />
      </section>

      <section className="card">
        <div className="month-bar">
          <button className="secondary" aria-label="Previous month" onClick={() => goTo(shiftMonth(month, -1))}>
            ‹
          </button>
          <h2>{formatMonth(month)}</h2>
          <button className="secondary" aria-label="Next month" onClick={() => goTo(shiftMonth(month, 1))}>
            ›
          </button>
          <div className="totals">
            <span>
              Spent <strong>{money(spent)}</strong>
            </span>
            {invested !== 0 && (
              <span>
                Invested <strong>{money(invested)}</strong>
              </span>
            )}
          </div>
        </div>

        <ErrorText error={remove.error} />
        {expenses.isPending && <Loading />}
        <ErrorText error={expenses.error} />
        {expenses.data?.length === 0 && <p className="muted">No expenses in {formatMonth(month)} yet.</p>}
        {expenses.data && expenses.data.length > 0 && (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Date</th>
                  <th>Merchant</th>
                  <th>Category</th>
                  <th>Account</th>
                  <th>Paid by</th>
                  <th className="num">Amount</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {expenses.data.map((e) => (
                  <tr key={e.id} className={editing?.id === e.id ? "selected" : undefined}>
                    <td>{e.date}</td>
                    <td>
                      {e.merchant || <span className="muted">—</span>}
                      {e.notes && <div className="muted small">{e.notes}</div>}
                    </td>
                    <td>{categoryLabel(categories, e.categoryId) || <span className="muted">Uncategorized</span>}</td>
                    <td>{accounts.find((a) => a.id === e.accountId)?.name ?? ""}</td>
                    <td>{members.find((m) => m.userId === e.paidByUserId)?.displayName ?? "Shared"}</td>
                    <td className={e.amountMinor < 0 ? "num refund" : "num"}>{money(e.amountMinor)}</td>
                    <td className="row-actions">
                      <button className="link" onClick={() => setEditing(e)}>
                        Edit
                      </button>
                      <button className="link danger" onClick={() => confirmDelete(e)}>
                        Delete
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </>
  );
}
