import { useState, type FormEvent } from "react";
import { useAccounts, useCategories, useMembers, useSaveExpense } from "../api/hooks";
import type { Expense } from "../api/types";
import { categoryTree } from "../categories/tree";
import { todayIso } from "../lib/dates";
import { parseMoney, toInputValue } from "../lib/money";
import { useHousehold } from "../households/HouseholdPages";
import { Field, FormError } from "../ui";

const FIELDS = ["date", "amountMinor", "merchant", "categoryId", "accountId", "paidByUserId", "notes"];

/** Adds a new expense, or edits [expense] when given. */
export function ExpenseForm({ expense, onDone }: { expense?: Expense; onDone: () => void }) {
  const household = useHousehold();
  const categories = useCategories(household.id).data ?? [];
  const accounts = useAccounts(household.id).data ?? [];
  const members = useMembers(household.id).data ?? [];
  const save = useSaveExpense(household.id);

  const [date, setDate] = useState(expense?.date ?? todayIso());
  const [amount, setAmount] = useState(expense ? toInputValue(expense.amountMinor, household.currency) : "");
  const [merchant, setMerchant] = useState(expense?.merchant ?? "");
  const [categoryId, setCategoryId] = useState(expense?.categoryId ?? "");
  const [accountId, setAccountId] = useState(expense?.accountId ?? "");
  const [paidByUserId, setPaidByUserId] = useState(expense?.paidByUserId ?? "");
  const [notes, setNotes] = useState(expense?.notes ?? "");
  const [amountError, setAmountError] = useState<string | null>(null);

  // Archived categories and closed accounts stay selectable only for rows that already use them.
  const tree = categoryTree(categories.filter((c) => !c.archived || c.id === expense?.categoryId));
  const accountOptions = accounts.filter((a) => a.status === "active" || a.id === expense?.accountId);

  function submit(e: FormEvent) {
    e.preventDefault();
    const amountMinor = parseMoney(amount, household.currency);
    if (amountMinor === null || amountMinor === 0) {
      setAmountError("Enter a non-zero amount, e.g. 129.90 (negative for a refund)");
      return;
    }
    setAmountError(null);
    save.mutate(
      {
        id: expense?.id,
        input: {
          date,
          amountMinor,
          merchant,
          categoryId: categoryId || null,
          accountId: accountId || null,
          paidByUserId: paidByUserId || null,
          notes,
          version: expense?.version,
        },
      },
      {
        onSuccess: () => {
          if (expense) {
            onDone();
          } else {
            // Keep date, account and payer for quick entry of several expenses in a row.
            setAmount("");
            setMerchant("");
            setNotes("");
          }
        },
      },
    );
  }

  return (
    <form onSubmit={submit} className="expense-form" aria-label={expense ? "Edit expense" : "Add expense"}>
      <Field label="Date" name="date" error={save.error}>
        <input type="date" required value={date} onChange={(e) => setDate(e.target.value)} />
      </Field>
      <Field label={`Amount (${household.currency})`} name="amountMinor" error={save.error}>
        <input
          inputMode="decimal"
          required
          placeholder="0.00"
          value={amount}
          onChange={(e) => setAmount(e.target.value)}
          aria-invalid={amountError !== null}
        />
        {amountError && <span className="field-error">{amountError}</span>}
      </Field>
      <Field label="Merchant" name="merchant" error={save.error}>
        <input value={merchant} onChange={(e) => setMerchant(e.target.value)} />
      </Field>
      <Field label="Category" name="categoryId" error={save.error}>
        <select value={categoryId} onChange={(e) => setCategoryId(e.target.value)}>
          <option value="">Uncategorized</option>
          {tree.map((parent) => (
            <optgroup key={parent.id} label={parent.name}>
              <option value={parent.id}>{parent.name}</option>
              {parent.children.map((child) => (
                <option key={child.id} value={child.id}>
                  {parent.name} › {child.name}
                </option>
              ))}
            </optgroup>
          ))}
        </select>
      </Field>
      <Field label="Account" name="accountId" error={save.error}>
        <select value={accountId} onChange={(e) => setAccountId(e.target.value)}>
          <option value="">None</option>
          {accountOptions.map((a) => (
            <option key={a.id} value={a.id}>
              {a.name}
            </option>
          ))}
        </select>
      </Field>
      <Field label="Paid by" name="paidByUserId" error={save.error}>
        <select value={paidByUserId} onChange={(e) => setPaidByUserId(e.target.value)}>
          <option value="">Shared</option>
          {members.map((m) => (
            <option key={m.userId} value={m.userId}>
              {m.displayName}
            </option>
          ))}
        </select>
      </Field>
      <Field label="Notes" name="notes" error={save.error}>
        <input value={notes} onChange={(e) => setNotes(e.target.value)} />
      </Field>
      <div className="form-actions">
        <button type="submit" disabled={save.isPending}>
          {expense ? "Save" : "Add expense"}
        </button>
        {expense && (
          <button type="button" className="secondary" onClick={onDone}>
            Cancel
          </button>
        )}
      </div>
      <FormError error={save.error} fields={FIELDS} />
    </form>
  );
}
