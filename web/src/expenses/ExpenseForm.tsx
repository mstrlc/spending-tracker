import { useRef, useState, type FormEvent } from "react";
import { useAccounts, useCategories, useDeleteExpense, useMembers, useSaveExpense } from "../api/hooks";
import type { Expense } from "../api/types";
import { categoryTree } from "../categories/tree";
import { todayIso } from "../lib/dates";
import { formatMoney, parseMoney, toInputValue } from "../lib/money";
import { useHousehold } from "../households/HouseholdPages";
import { ErrorText, Field, FormError } from "../ui";

const FIELDS = ["date", "amountMinor", "merchant", "categoryId", "accountId", "paidByUserId", "notes"];

/**
 * Adds expenses (staying open for the next one) or edits [expense]. Lives in a side sheet;
 * [onSaved] reports each saved row, [onDone] closes the sheet.
 */
export function ExpenseForm({
  expense,
  defaultDate,
  onSaved,
  onDone,
}: {
  expense?: Expense;
  defaultDate?: string;
  onSaved?: (expense: Expense) => void;
  onDone: () => void;
}) {
  const household = useHousehold();
  const categories = useCategories(household.id).data ?? [];
  const accounts = useAccounts(household.id).data ?? [];
  const members = useMembers(household.id).data ?? [];
  const save = useSaveExpense(household.id);
  const remove = useDeleteExpense(household.id);
  const amountRef = useRef<HTMLInputElement>(null);

  const [date, setDate] = useState(expense?.date ?? defaultDate ?? todayIso());
  const [amount, setAmount] = useState(expense ? toInputValue(expense.amountMinor, household.currency) : "");
  const [merchant, setMerchant] = useState(expense?.merchant ?? "");
  const [categoryId, setCategoryId] = useState(expense?.categoryId ?? "");
  const [accountId, setAccountId] = useState(expense?.accountId ?? "");
  const [paidByUserId, setPaidByUserId] = useState(expense?.paidByUserId ?? "");
  const [notes, setNotes] = useState(expense?.notes ?? "");
  const [amountError, setAmountError] = useState<string | null>(null);
  const [lastAdded, setLastAdded] = useState<string | null>(null);

  // Archived categories and closed accounts stay selectable only for rows that already use them.
  const tree = categoryTree(categories.filter((c) => !c.archived || c.id === expense?.categoryId));
  const accountOptions = accounts.filter((a) => a.status === "active" || a.id === expense?.accountId);

  function submit(e: FormEvent) {
    e.preventDefault();
    const amountMinor = parseMoney(amount, household.currency);
    if (amountMinor === null || amountMinor === 0) {
      setAmountError("Enter an amount like 129.90. Use a minus sign for a refund.");
      amountRef.current?.focus();
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
        onSuccess: (saved) => {
          onSaved?.(saved);
          if (expense) {
            onDone();
            return;
          }
          // Stay open for the next expense, keeping date, category, account and payer.
          setLastAdded(`Added ${saved.merchant || "expense"} · ${formatMoney(saved.amountMinor, household.currency)}`);
          setAmount("");
          setMerchant("");
          setNotes("");
          amountRef.current?.focus();
        },
      },
    );
  }

  function confirmDelete() {
    if (!expense) return;
    if (window.confirm(`Delete ${expense.merchant || "this expense"}?`)) {
      remove.mutate({ id: expense.id, version: expense.version }, { onSuccess: onDone });
    }
  }

  return (
    <form onSubmit={submit} className="sheet-form" aria-label={expense ? "Edit expense" : "Add expense"}>
      <div className="sheet-fields">
        <div className="field-pair">
          <Field label={`Amount (${household.currency})`} name="amountMinor" error={save.error} hint={amountError}>
            <input
              ref={amountRef}
              data-autofocus
              inputMode="decimal"
              required
              placeholder="0.00"
              className="amount-input"
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
              aria-invalid={amountError !== null}
            />
          </Field>
          <Field label="Date" name="date" error={save.error}>
            <input type="date" required value={date} onChange={(e) => setDate(e.target.value)} />
          </Field>
        </div>
        <Field label="Merchant" name="merchant" error={save.error}>
          <input value={merchant} placeholder="Where you paid" onChange={(e) => setMerchant(e.target.value)} />
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
        {members.length > 1 && (
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
        )}
        <Field label="Notes" name="notes" error={save.error}>
          <input value={notes} onChange={(e) => setNotes(e.target.value)} />
        </Field>
        <FormError error={save.error} fields={FIELDS} />
        <ErrorText error={remove.error} />
        {lastAdded && !save.error && (
          <p className="confirmation" role="status">
            {lastAdded}
          </p>
        )}
      </div>
      <div className="sheet-footer">
        {expense && (
          <button type="button" className="ghost danger" onClick={confirmDelete} disabled={remove.isPending}>
            Delete
          </button>
        )}
        <span className="spacer" />
        <button type="button" className="secondary" onClick={onDone}>
          {expense ? "Cancel" : "Done"}
        </button>
        <button type="submit" disabled={save.isPending}>
          {expense ? "Save" : "Add expense"}
        </button>
      </div>
    </form>
  );
}
