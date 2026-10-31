import { useRef, useState, type FormEvent } from "react";
import { useAccounts, useDeleteIncome, useMembers, useSaveIncome } from "../api/hooks";
import type { Income } from "../api/types";
import { todayIso } from "../lib/dates";
import { formatMoney, parseMoney, toInputValue } from "../lib/money";
import { useHousehold } from "../households/HouseholdPages";
import { ErrorText, Field, FormError } from "../ui";

const FIELDS = ["date", "amountMinor", "source", "accountId", "recipientUserId", "notes"];

/**
 * Adds income (staying open for the next line) or edits [income]. Lives in a side sheet;
 * [onSaved] reports each saved row, [onDone] closes the sheet.
 */
export function IncomeForm({
  income,
  defaultDate,
  onSaved,
  onDone,
}: {
  income?: Income;
  defaultDate?: string;
  onSaved?: (income: Income) => void;
  onDone: () => void;
}) {
  const household = useHousehold();
  const accounts = useAccounts(household.id).data ?? [];
  const members = useMembers(household.id).data ?? [];
  const save = useSaveIncome(household.id);
  const remove = useDeleteIncome(household.id);
  const amountRef = useRef<HTMLInputElement>(null);

  const [date, setDate] = useState(income?.date ?? defaultDate ?? todayIso());
  const [amount, setAmount] = useState(income ? toInputValue(income.amountMinor, household.currency) : "");
  const [source, setSource] = useState(income?.source ?? "");
  const [accountId, setAccountId] = useState(income?.accountId ?? "");
  const [recipientUserId, setRecipientUserId] = useState(income?.recipientUserId ?? "");
  const [notes, setNotes] = useState(income?.notes ?? "");
  const [amountError, setAmountError] = useState<string | null>(null);
  const [lastAdded, setLastAdded] = useState<string | null>(null);

  // Closed accounts stay selectable only for rows that already use them.
  const accountOptions = accounts.filter((a) => a.status === "active" || a.id === income?.accountId);

  function submit(e: FormEvent) {
    e.preventDefault();
    const amountMinor = parseMoney(amount, household.currency);
    if (amountMinor === null || amountMinor === 0) {
      setAmountError("Enter an amount like 45000. Use a minus sign for money paid back.");
      amountRef.current?.focus();
      return;
    }
    setAmountError(null);
    save.mutate(
      {
        id: income?.id,
        input: {
          date,
          amountMinor,
          source,
          accountId: accountId || null,
          recipientUserId: recipientUserId || null,
          notes,
          version: income?.version,
        },
      },
      {
        onSuccess: (saved) => {
          onSaved?.(saved);
          if (income) {
            onDone();
            return;
          }
          // Stay open for the next line, keeping date, account and recipient.
          setLastAdded(`Added ${saved.source || "income"} · ${formatMoney(saved.amountMinor, household.currency)}`);
          setAmount("");
          setSource("");
          setNotes("");
          amountRef.current?.focus();
        },
      },
    );
  }

  function confirmDelete() {
    if (!income) return;
    if (window.confirm(`Delete ${income.source || "this income"}?`)) {
      remove.mutate({ id: income.id, version: income.version }, { onSuccess: onDone });
    }
  }

  return (
    <form onSubmit={submit} className="sheet-form" aria-label={income ? "Edit income" : "Add income"}>
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
        <Field label="Source" name="source" error={save.error}>
          <input value={source} placeholder="Employer, client, who paid" onChange={(e) => setSource(e.target.value)} />
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
          <Field label="Received by" name="recipientUserId" error={save.error}>
            <select value={recipientUserId} onChange={(e) => setRecipientUserId(e.target.value)}>
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
        {income && (
          <button type="button" className="ghost danger" onClick={confirmDelete} disabled={remove.isPending}>
            Delete
          </button>
        )}
        <span className="spacer" />
        <button type="button" className="secondary" onClick={onDone}>
          {income ? "Cancel" : "Done"}
        </button>
        <button type="submit" disabled={save.isPending}>
          {income ? "Save" : "Add income"}
        </button>
      </div>
    </form>
  );
}
