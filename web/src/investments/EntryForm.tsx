import { useRef, useState, type FormEvent } from "react";
import {
  useAccounts,
  useDeleteContribution,
  useDeleteValuation,
  useSaveContribution,
  useSaveValuation,
} from "../api/hooks";
import type { Contribution, SecurityQuote, Valuation } from "../api/types";
import { useHousehold } from "../households/HouseholdPages";
import { todayIso } from "../lib/dates";
import { parseMoney, toInputValue } from "../lib/money";
import { ErrorText, Field, FormError } from "../ui";

export type EntryTarget =
  | { type: "contribution"; contribution?: Contribution }
  | { type: "valuation"; valuation?: Valuation };

/**
 * Records money put into (or taken out of) a holding, or what it's worth on a day; with an existing entry in
 * [target], edits or deletes it. Sits at the top of the holding's sheet; [onDone] closes it.
 */
export function EntryForm({
  investmentId,
  security,
  target,
  onDone,
}: {
  investmentId: string;
  /** The security the holding follows; contributions then ask for shares. */
  security: SecurityQuote | null;
  target: EntryTarget;
  onDone: () => void;
}) {
  const household = useHousehold();
  const accounts = useAccounts(household.id).data ?? [];
  const saveContribution = useSaveContribution(household.id, investmentId);
  const saveValuation = useSaveValuation(household.id, investmentId);
  const deleteContribution = useDeleteContribution(household.id, investmentId);
  const deleteValuation = useDeleteValuation(household.id, investmentId);
  const isValuation = target.type === "valuation";
  const existing = target.type === "valuation" ? target.valuation : target.contribution;
  const save = isValuation ? saveValuation : saveContribution;
  const remove = isValuation ? deleteValuation : deleteContribution;
  const amountRef = useRef<HTMLInputElement>(null);

  const initialAmount =
    target.type === "valuation" ? target.valuation?.valueMinor : target.contribution?.amountMinor;
  const [amount, setAmount] = useState(initialAmount === undefined ? "" : toInputValue(initialAmount, household.currency));
  const [date, setDate] = useState(existing?.date ?? todayIso());
  const [accountId, setAccountId] = useState(target.type === "contribution" ? (target.contribution?.accountId ?? "") : "");
  const [notes, setNotes] = useState(existing?.notes ?? "");
  const initialShares = target.type === "contribution" ? target.contribution?.quantity : undefined;
  const [shares, setShares] = useState(initialShares == null ? "" : String(Math.abs(initialShares)));
  const [sharesError, setSharesError] = useState<string | null>(null);
  const [amountError, setAmountError] = useState<string | null>(null);
  const accountOptions = accounts.filter((a) => a.status === "active" || a.id === accountId);
  const amountField = isValuation ? "valueMinor" : "amountMinor";

  function submit(e: FormEvent) {
    e.preventDefault();
    const minor = parseMoney(amount, household.currency);
    const invalid = minor === null || (isValuation ? minor < 0 : minor === 0);
    if (invalid) {
      setAmountError(isValuation ? "Enter what it's worth, like 125000." : "Enter an amount like 5000. Use a minus sign for money taken out.");
      amountRef.current?.focus();
      return;
    }
    setAmountError(null);
    let quantity: number | null = null;
    if (!isValuation && shares.trim()) {
      const parsed = Number(shares.trim().replace(",", "."));
      if (!Number.isFinite(parsed) || parsed <= 0) {
        setSharesError("Enter how many shares, like 5 or 0.25.");
        return;
      }
      // Shares follow the money: sold when money was taken out.
      quantity = minor < 0 ? -parsed : parsed;
    }
    setSharesError(null);
    const version = existing?.version;
    if (isValuation) {
      saveValuation.mutate({ id: existing?.id, input: { date, valueMinor: minor, notes, version } }, { onSuccess: onDone });
    } else {
      saveContribution.mutate(
        { id: existing?.id, input: { date, amountMinor: minor, quantity, accountId: accountId || null, notes, version } },
        { onSuccess: onDone },
      );
    }
  }

  function confirmDelete() {
    if (!existing) return;
    if (window.confirm(isValuation ? "Delete this value?" : "Delete this contribution?")) {
      remove.mutate({ id: existing.id, version: existing.version }, { onSuccess: onDone });
    }
  }

  const title = isValuation ? (existing ? "Edit value" : "Update value") : existing ? "Edit contribution" : "Add contribution";
  return (
    <form onSubmit={submit} className="entry-form" aria-label={title}>
      <h3 className="entry-form-title">{title}</h3>
      <div className="field-pair">
        <Field
          label={`${isValuation ? "Worth" : "Amount"} (${household.currency})`}
          name={amountField}
          error={save.error}
          hint={amountError}
        >
          <input
            ref={amountRef}
            autoFocus
            inputMode="decimal"
            required
            placeholder="0.00"
            className="amount-input"
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
            aria-invalid={amountError !== null}
          />
        </Field>
        <Field label={isValuation ? "On" : "Date"} name="date" error={save.error}>
          <input type="date" required value={date} onChange={(e) => setDate(e.target.value)} />
        </Field>
      </div>
      {!isValuation && security && (
        <Field label={`Shares of ${security.symbol}`} name="quantity" error={save.error} hint={sharesError}>
          <input inputMode="decimal" placeholder="5" value={shares} onChange={(e) => setShares(e.target.value)} aria-invalid={sharesError !== null} />
        </Field>
      )}
      {!isValuation && (
        <Field label="From account" name="accountId" error={save.error}>
          <select value={accountId} onChange={(e) => setAccountId(e.target.value)}>
            <option value="">None</option>
            {accountOptions.map((a) => (
              <option key={a.id} value={a.id}>
                {a.name}
              </option>
            ))}
          </select>
        </Field>
      )}
      <Field label="Notes" name="notes" error={save.error}>
        <input value={notes} onChange={(e) => setNotes(e.target.value)} />
      </Field>
      <FormError error={save.error} fields={[amountField, "date", "quantity", "accountId", "notes"]} />
      <ErrorText error={remove.error} />
      <div className="entry-form-actions">
        {existing && (
          <button type="button" className="ghost danger" onClick={confirmDelete} disabled={remove.isPending}>
            Delete
          </button>
        )}
        <span className="spacer" />
        <button type="button" className="secondary" onClick={onDone}>
          Cancel
        </button>
        <button type="submit" disabled={save.isPending}>
          {existing ? "Save" : isValuation ? "Save value" : "Add contribution"}
        </button>
      </div>
    </form>
  );
}
