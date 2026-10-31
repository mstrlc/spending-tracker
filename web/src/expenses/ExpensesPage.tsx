import { useEffect, useState } from "react";
import { useSearchParams } from "react-router";
import { useAccounts, useCategories, useExpenses, useMembers } from "../api/hooks";
import type { Category, Expense } from "../api/types";
import { ChevronLeft, ChevronRight, Plus } from "../icons";
import { currentMonth, formatDay, formatMonth, isMonth, monthRange, shiftMonth, todayIso } from "../lib/dates";
import { formatAmount, formatMoney } from "../lib/money";
import { useHousehold } from "../households/HouseholdPages";
import { ErrorText, PageHeader, Sheet, SkeletonRows } from "../ui";
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

type Editor = { mode: "closed" } | { mode: "add" } | { mode: "edit"; expense: Expense };

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
  const [editor, setEditor] = useState<Editor>({ mode: "closed" });

  const goTo = (m: string) => setParams(m === currentMonth() ? {} : { month: m });
  const rows = expenses.data ?? [];
  const { spent, invested } = totals(rows, categories);
  const showPaidBy = members.length > 1;
  // New expenses default to today in the current month, otherwise to the first of the month being viewed.
  const defaultDate = month === currentMonth() ? todayIso() : from;
  const openAdd = () => setEditor({ mode: "add" });
  // The row most recently added or saved, highlighted briefly so the change is easy to spot.
  const [flashId, setFlashId] = useState<string | null>(null);

  // N adds an expense, ← / → change month. Ignored while typing, with modifiers, or with the sheet open.
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.metaKey || e.ctrlKey || e.altKey || e.defaultPrevented) return;
      const target = e.target as HTMLElement;
      if (target.closest("input, select, textarea, dialog[open]") || document.querySelector("dialog[open]")) return;
      if (e.key === "n" || e.key === "N") {
        e.preventDefault();
        setEditor({ mode: "add" });
      } else if (e.key === "ArrowLeft") {
        goTo(shiftMonth(month, -1));
      } else if (e.key === "ArrowRight") {
        goTo(shiftMonth(month, 1));
      }
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  });

  return (
    <>
      <PageHeader
        title={
          <span className="month-title">
            <span>{formatMonth(month)}</span>
            <span className="month-nav">
              <button
                className="icon-button"
                aria-label="Previous month"
                title="Previous month (←)"
                onClick={() => goTo(shiftMonth(month, -1))}
              >
                <ChevronLeft />
              </button>
              <button
                className="icon-button"
                aria-label="Next month"
                title="Next month (→)"
                onClick={() => goTo(shiftMonth(month, 1))}
              >
                <ChevronRight />
              </button>
            </span>
            {month !== currentMonth() && (
              <button className="ghost this-month" onClick={() => goTo(currentMonth())}>
                This month
              </button>
            )}
          </span>
        }
      >
        <button onClick={openAdd} aria-keyshortcuts="N">
          <Plus /> Add expense <kbd>N</kbd>
        </button>
      </PageHeader>

      <dl className="summary">
        <div>
          <dt>Spent</dt>
          <dd className="summary-primary">{formatMoney(spent, household.currency)}</dd>
        </div>
        <div>
          <dt>Invested</dt>
          <dd>{formatMoney(invested, household.currency)}</dd>
        </div>
        <div>
          <dt>Expenses</dt>
          <dd>{expenses.data ? rows.length : "–"}</dd>
        </div>
      </dl>

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
