import { useState, type FormEvent } from "react";
import { useAccounts, useAssets, useDecideBankRows } from "../api/hooks";
import type { BankRow } from "../api/types";
import { formatDay } from "../lib/dates";
import { formatMoney } from "../lib/money";
import { useHousehold } from "../households/HouseholdPages";
import { Field, FormError, SheetBody } from "../ui";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";

const FIELDS = ["assetId", "notes"];

/**
 * Files one bank row as money put into an asset (money out of the account) or taken out of it (money in), starting
 * from [assetId] when the suggestion knows it.
 */
export function ContributeForm({
  row,
  assetId: suggested,
  onSaved,
  onDone,
}: {
  row: BankRow;
  assetId: string | null;
  onSaved: () => void;
  onDone: () => void;
}) {
  const household = useHousehold();
  const accounts = useAccounts(household.id).data ?? [];
  const assets = useAssets(household.id).data ?? [];
  const decide = useDecideBankRows(household.id);
  const [assetId, setAssetId] = useState(suggested ?? "");
  const [notes, setNotes] = useState("");
  const putIn = row.amountMinor < 0;
  // The suggestion's household-currency amount, positive for money put in.
  const amount = row.suggestion?.amountMinor ?? (row.currency === household.currency ? -row.amountMinor : null);

  function submit(e: FormEvent) {
    e.preventDefault();
    decide.mutate(
      { action: "contribute", rows: [{ id: row.id, version: row.version }], assetId, notes },
      { onSuccess: () => { onSaved(); onDone(); } },
    );
  }

  return (
    <form onSubmit={submit} className="flex flex-1 flex-col" aria-label="Investment">
      <SheetBody
        footer={
          <>
            <span className="flex-1" />
            <Button type="button" variant="outline" onClick={onDone}>
              Cancel
            </Button>
            <Button type="submit" disabled={decide.isPending || !assetId}>
              {putIn ? "Put in" : "Take out"}
            </Button>
          </>
        }
      >
        <div className="rounded-lg border bg-muted/40 px-3 py-2 text-sm">
          <div className="font-medium">{row.text || row.counterpartyAccount || "Bank row"}</div>
          <div className="text-xs text-muted-foreground">
            {formatDay(row.date)} · {accounts.find((a) => a.id === row.accountId)?.name} ·{" "}
            <span className="amount">
              {row.amountMinor > 0 ? "+" : ""}
              {formatMoney(row.amountMinor, row.currency)}
            </span>
          </div>
        </div>
        <Field label={putIn ? "Put into" : "Taken out of"} name="assetId" error={decide.error}>
          <NativeSelect className="w-full" required data-autofocus value={assetId} onChange={(e) => setAssetId(e.target.value)}>
            <NativeSelectOption value="">Choose an asset</NativeSelectOption>
            {assets.map((a) => (
              <NativeSelectOption key={a.id} value={a.id}>
                {a.name}
              </NativeSelectOption>
            ))}
          </NativeSelect>
        </Field>
        <div className="flex flex-col gap-1.5 text-sm">
          <span className="text-muted-foreground">Amount ({household.currency})</span>
          <span className="amount">
            {amount === null ? "No exchange rate for this day yet" : formatMoney(Math.abs(amount), household.currency)}
          </span>
        </div>
        <Field label="Notes" name="notes" error={decide.error}>
          <Input value={notes} placeholder="e.g. Monthly deposit" onChange={(e) => setNotes(e.target.value)} />
        </Field>
        <p className="text-xs text-muted-foreground">
          It's recorded on the asset as {putIn ? "money put in" : "money taken out"}, and counts as invested, not spent.
          Putting the row back in the inbox deletes it again.
        </p>
        <FormError error={decide.error} fields={FIELDS} />
      </SheetBody>
    </form>
  );
}
