import { useRef, useState, type FormEvent } from "react";
import {
  useAccounts,
  useDeleteContribution,
  useDeleteFee,
  useDeleteValuation,
  useSaveContribution,
  useSaveFee,
  useSaveValuation,
} from "../api/hooks";
import type { Contribution, Fee, SecurityQuote, Valuation } from "../api/types";
import { useHousehold } from "../households/HouseholdPages";
import { todayIso } from "../lib/dates";
import { parseMoney, toInputValue } from "../lib/money";
import { ErrorText, Field, FormError } from "../ui";

export type EntryTarget =
  | { type: "contribution"; contribution?: Contribution }
  | { type: "valuation"; valuation?: Valuation }
  | { type: "fee"; fee?: Fee };

// Per kind of entry: its wording, and what an amount may be.
const KINDS = {
  contribution: {
    noun: "contribution",
    add: "Add contribution",
    amountLabel: "Amount",
    amountField: "amountMinor",
    invalid: (minor: number) => minor === 0,
    hint: "Enter an amount like 5000. Use a minus sign for money taken out.",
  },
  valuation: {
    noun: "value",
    add: "Update value",
    amountLabel: "Worth",
    amountField: "valueMinor",
    invalid: (minor: number) => minor < 0,
    hint: "Enter what it's worth, like 125000.",
  },
  fee: {
    noun: "fee",
    add: "Add fee",
    amountLabel: "Fee",
    amountField: "amountMinor",
    invalid: (minor: number) => minor === 0,
    hint: "Enter the fee, like 85.50. Use a minus sign for a discount.",
  },
} as const;

/**
 * Records money put into (or taken out of) a holding, what it's worth on a day, or a fee charged on it; with an
 * existing entry in [target], edits or deletes it. Sits at the top of the holding's sheet; [onDone] closes it.
 */
export function EntryForm({
  assetId,
  security,
  target,
  onDone,
}: {
  assetId: string;
  /** The security the holding follows; contributions then ask for shares. */
  security: SecurityQuote | null;
  target: EntryTarget;
  onDone: () => void;
}) {
  const household = useHousehold();
  const accounts = useAccounts(household.id).data ?? [];
  const saveContribution = useSaveContribution(household.id, assetId);
  const saveValuation = useSaveValuation(household.id, assetId);
  const saveFee = useSaveFee(household.id, assetId);
  const deleteContribution = useDeleteContribution(household.id, assetId);
  const deleteValuation = useDeleteValuation(household.id, assetId);
  const deleteFee = useDeleteFee(household.id, assetId);
  const kind = KINDS[target.type];
  const existing = target.type === "valuation" ? target.valuation : target.type === "fee" ? target.fee : target.contribution;
  const save = { contribution: saveContribution, valuation: saveValuation, fee: saveFee }[target.type];
  const remove = { contribution: deleteContribution, valuation: deleteValuation, fee: deleteFee }[target.type];
  const isContribution = target.type === "contribution";
  const amountRef = useRef<HTMLInputElement>(null);

  const initialAmount =
    target.type === "valuation" ? target.valuation?.valueMinor : target.type === "fee" ? target.fee?.amountMinor : target.contribution?.amountMinor;
  const [amount, setAmount] = useState(initialAmount === undefined ? "" : toInputValue(initialAmount, household.currency));
  const [date, setDate] = useState(existing?.date ?? todayIso());
  const [accountId, setAccountId] = useState(target.type === "contribution" ? (target.contribution?.accountId ?? "") : "");
  const [notes, setNotes] = useState(existing?.notes ?? "");
  const initialShares = target.type === "contribution" ? target.contribution?.quantity : undefined;
  const [shares, setShares] = useState(initialShares == null ? "" : String(Math.abs(initialShares)));
  const [sharesError, setSharesError] = useState<string | null>(null);
  const [amountError, setAmountError] = useState<string | null>(null);
  const accountOptions = accounts.filter((a) => a.status === "active" || a.id === accountId);

  function submit(e: FormEvent) {
    e.preventDefault();
    const minor = parseMoney(amount, household.currency);
    if (minor === null || kind.invalid(minor)) {
      setAmountError(kind.hint);
      amountRef.current?.focus();
      return;
    }
    setAmountError(null);
    let quantity: number | null = null;
    if (isContribution && shares.trim()) {
      const parsed = Number(shares.trim().replace(",", "."));
      if (!Number.isFinite(parsed) || parsed <= 0) {
        setSharesError("Enter how many shares, like 5 or 0.25.");
        return;
      }
      // Shares follow the money: sold when money was taken out.
      quantity = minor < 0 ? -parsed : parsed;
    }
    setSharesError(null);
    const id = existing?.id;
    const version = existing?.version;
    if (target.type === "valuation") {
      saveValuation.mutate({ id, input: { date, valueMinor: minor, notes, version } }, { onSuccess: onDone });
    } else if (target.type === "fee") {
      saveFee.mutate({ id, input: { date, amountMinor: minor, notes, version } }, { onSuccess: onDone });
    } else {
      saveContribution.mutate(
        { id, input: { date, amountMinor: minor, quantity, accountId: accountId || null, notes, version } },
        { onSuccess: onDone },
      );
    }
  }

  function confirmDelete() {
    if (!existing) return;
    if (window.confirm(`Delete this ${kind.noun}?`)) {
      remove.mutate({ id: existing.id, version: existing.version }, { onSuccess: onDone });
    }
  }

  const title = existing ? `Edit ${kind.noun}` : kind.add;
  return (
    <form onSubmit={submit} className="entry-form" aria-label={title}>
      <h3 className="entry-form-title">{title}</h3>
      <div className="field-pair">
        <Field label={`${kind.amountLabel} (${household.currency})`} name={kind.amountField} error={save.error} hint={amountError}>
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
        <Field label={target.type === "valuation" ? "On" : "Date"} name="date" error={save.error}>
          <input type="date" required value={date} onChange={(e) => setDate(e.target.value)} />
        </Field>
      </div>
      {isContribution && security && (
        <Field label={`Shares of ${security.symbol}`} name="quantity" error={save.error} hint={sharesError}>
          <input inputMode="decimal" placeholder="5" value={shares} onChange={(e) => setShares(e.target.value)} aria-invalid={sharesError !== null} />
        </Field>
      )}
      {isContribution && (
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
      <FormError error={save.error} fields={[kind.amountField, "date", "quantity", "accountId", "notes"]} />
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
          {existing ? "Save" : target.type === "valuation" ? "Save value" : kind.add}
        </button>
      </div>
    </form>
  );
}
