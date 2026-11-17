import { useRef, useState, type FormEvent } from "react";
import {
  useAccounts,
  useDeleteContribution,
  useDeleteFee,
  useDeleteValuation,
  useFxRate,
  useSaveContribution,
  useSaveFee,
  useSaveValuation,
} from "../api/hooks";
import type { Contribution, Fee, SecurityQuote, Valuation } from "../api/types";
import { useHousehold } from "../households/HouseholdPages";
import { todayIso } from "../lib/dates";
import { fractionDigits, parseMoney, toInputValue } from "../lib/money";
import { formatAsset } from "./holdings";
import { useConfirm } from "../dialogs";
import { ErrorText, Field, FormError } from "../ui";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";

export type EntryTarget =
  | { type: "contribution"; contribution?: Contribution }
  | { type: "valuation"; valuation?: Valuation }
  | { type: "fee"; fee?: Fee };

// Per kind of entry: its wording, and what an amount may be.
function entryKind(type: EntryTarget["type"], isLiability: boolean) {
  switch (type) {
    case "contribution":
      return {
        noun: "contribution",
        add: "Add contribution",
        amountLabel: "Amount",
        amountField: "amountMinor" as const,
        invalid: (minor: number) => minor === 0,
        hint: isLiability
          ? "Positive for a repayment, minus for money borrowed."
          : "Enter an amount like 5000. Use a minus sign for money taken out.",
      };
    case "valuation":
      return {
        noun: isLiability ? "balance" : "value",
        add: isLiability ? "Update balance" : "Update value",
        amountLabel: isLiability ? "Owed" : "Worth",
        amountField: "valueMinor" as const,
        invalid: (minor: number) => minor < 0,
        hint: isLiability ? "Enter what's owed, like 250000." : "Enter what it's worth, like 125000.",
      };
    case "fee":
      return {
        noun: "fee",
        add: "Add fee",
        amountLabel: "Fee",
        amountField: "amountMinor" as const,
        invalid: (minor: number) => minor === 0,
        hint: "Enter the fee, like 85.50. Use a minus sign for a discount.",
      };
  }
}

/**
 * Records money put into (or taken out of) a holding, what it's worth on a day, or a fee charged on it; with an
 * existing entry in [target], edits or deletes it. Sits at the top of the holding's sheet; [onDone] closes it.
 */
export function EntryForm({
  assetId,
  security,
  isLiability = false,
  target,
  onDone,
}: {
  assetId: string;
  /** The security the holding follows; contributions then ask for shares. */
  security: SecurityQuote | null;
  isLiability?: boolean;
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
  const kind = entryKind(target.type, isLiability);
  const existing = target.type === "valuation" ? target.valuation : target.type === "fee" ? target.fee : target.contribution;
  const save = { contribution: saveContribution, valuation: saveValuation, fee: saveFee }[target.type];
  const remove = { contribution: deleteContribution, valuation: deleteValuation, fee: deleteFee }[target.type];
  const isContribution = target.type === "contribution";
  const amountRef = useRef<HTMLInputElement>(null);
  const confirm = useConfirm();

  const foreignCurrency =
    isContribution && security?.currency && security.currency !== household.currency ? security.currency : null;
  const existingContribution = target.type === "contribution" ? target.contribution : undefined;
  const initialCurrency = existingContribution
    ? (existingContribution.originalCurrency ?? household.currency)
    : (foreignCurrency ?? household.currency);
  const [entryCurrency, setEntryCurrency] = useState(initialCurrency);
  const isForeign = isContribution && entryCurrency !== household.currency;

  const initialAmount =
    target.type === "valuation"
      ? target.valuation?.valueMinor
      : target.type === "fee"
        ? target.fee?.amountMinor
        : isForeign
          ? existingContribution?.originalAmountMinor ?? undefined
          : existingContribution?.amountMinor;
  const [amount, setAmount] = useState(initialAmount === undefined ? "" : toInputValue(initialAmount, entryCurrency));
  const [date, setDate] = useState(existing?.date ?? todayIso());
  const [accountId, setAccountId] = useState(target.type === "contribution" ? (target.contribution?.accountId ?? "") : "");
  const [notes, setNotes] = useState(existing?.notes ?? "");
  const initialShares = target.type === "contribution" ? target.contribution?.quantity : undefined;
  const [shares, setShares] = useState(initialShares == null ? "" : String(Math.abs(initialShares)));
  const [sharesError, setSharesError] = useState<string | null>(null);
  const [amountError, setAmountError] = useState<string | null>(null);
  const accountOptions = accounts.filter((a) => a.status === "active" || a.id === accountId);
  const fx = useFxRate(isForeign ? entryCurrency : undefined, household.currency, date);
  const parsedOriginal = isForeign ? parseMoney(amount, entryCurrency) : null;
  const liveConvertedMinor =
    parsedOriginal !== null && fx.data?.rate != null
      ? Math.round(
          (parsedOriginal / 10 ** fractionDigits(entryCurrency)) *
            fx.data.rate *
            10 ** fractionDigits(household.currency),
        )
      : null;

  function submit(e: FormEvent) {
    e.preventDefault();
    const minor = parseMoney(amount, entryCurrency);
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
    } else if (isForeign) {
      saveContribution.mutate(
        {
          id,
          input: {
            date,
            amountMinor: null,
            originalAmountMinor: minor,
            originalCurrency: entryCurrency,
            quantity,
            accountId: accountId || null,
            notes,
            version,
          },
        },
        { onSuccess: onDone },
      );
    } else {
      saveContribution.mutate(
        { id, input: { date, amountMinor: minor, quantity, accountId: accountId || null, notes, version } },
        { onSuccess: onDone },
      );
    }
  }

  async function confirmDelete() {
    if (!existing) return;
    if (await confirm({ title: `Delete this ${kind.noun}?`, description: "This can't be undone." })) {
      remove.mutate({ id: existing.id, version: existing.version }, { onSuccess: onDone });
    }
  }

  const title = existing ? `Edit ${kind.noun}` : kind.add;
  return (
    <form onSubmit={submit} className="flex flex-col gap-4 rounded-lg border p-4" aria-label={title}>
      <h3 className="font-medium">{title}</h3>
      <div className="grid grid-cols-2 gap-3">
        <Field label={`${kind.amountLabel} (${entryCurrency})`} name={kind.amountField} error={save.error} hint={amountError}>
          <Input
            ref={amountRef}
            autoFocus
            inputMode="decimal"
            required
            placeholder="0.00"
            className="amount h-11 text-lg md:text-lg"
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
            aria-invalid={amountError !== null}
          />
        </Field>
        <Field label={target.type === "valuation" ? "On" : "Date"} name="date" error={save.error}>
          <Input type="date" required className="h-11" value={date} onChange={(e) => setDate(e.target.value)} />
        </Field>
      </div>
      {foreignCurrency && (
        <div className="flex items-center justify-between gap-3 text-xs text-muted-foreground">
          <NativeSelect
            size="sm"
            aria-label="Currency"
            value={entryCurrency}
            onChange={(e) => setEntryCurrency(e.target.value)}
          >
            <NativeSelectOption value={foreignCurrency}>{foreignCurrency}</NativeSelectOption>
            <NativeSelectOption value={household.currency}>{household.currency}</NativeSelectOption>
          </NativeSelect>
          {isForeign && (
            <span className="amount">
              {liveConvertedMinor !== null
                ? `≈ ${formatAsset(liveConvertedMinor, household.currency)} ${household.currency} (ČNB ${fx.data?.rate?.toFixed(2)})`
                : "Converted at ČNB fixing on save"}
            </span>
          )}
        </div>
      )}
      {isContribution && security && (
        <Field label={`Shares of ${security.symbol}`} name="quantity" error={save.error} hint={sharesError}>
          <Input
            inputMode="decimal"
            placeholder="5"
            className="amount"
            value={shares}
            onChange={(e) => setShares(e.target.value)}
            aria-invalid={sharesError !== null}
          />
        </Field>
      )}
      {isContribution && (
        <Field label="From account" name="accountId" error={save.error}>
          <NativeSelect className="w-full" value={accountId} onChange={(e) => setAccountId(e.target.value)}>
            <NativeSelectOption value="">None</NativeSelectOption>
            {accountOptions.map((a) => (
              <NativeSelectOption key={a.id} value={a.id}>
                {a.name}
              </NativeSelectOption>
            ))}
          </NativeSelect>
        </Field>
      )}
      <Field label="Notes" name="notes" error={save.error}>
        <Input value={notes} onChange={(e) => setNotes(e.target.value)} />
      </Field>
      <FormError error={save.error} fields={[kind.amountField, "date", "quantity", "accountId", "notes"]} />
      <ErrorText error={remove.error} />
      <div className="flex items-center gap-2">
        {existing && (
          <Button type="button" variant="ghost" className="text-destructive" onClick={confirmDelete} disabled={remove.isPending}>
            Delete
          </Button>
        )}
        <span className="flex-1" />
        <Button type="button" variant="outline" onClick={onDone}>
          Cancel
        </Button>
        <Button type="submit" disabled={save.isPending}>
          {existing ? "Save" : target.type === "valuation" ? `Save ${kind.noun}` : kind.add}
        </Button>
      </div>
    </form>
  );
}
