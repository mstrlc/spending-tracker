import { useState, type FormEvent } from "react";
import { useAccounts, useDecideBankRows } from "../api/hooks";
import type { BankRow } from "../api/types";
import { formatDay } from "../lib/dates";
import { formatMoney } from "../lib/money";
import { useHousehold } from "../households/HouseholdPages";
import { Field, FormError, SheetBody } from "../ui";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";

const FIELDS = ["otherAccountId", "notes"];

/** Files one bank row as a transfer to or from another of the household's accounts, one that has no feed here. */
export function TransferForm({
  row,
  otherAccountId,
  onSaved,
  onDone,
}: {
  row: BankRow;
  /** The account the row's counterparty looks like, if any. */
  otherAccountId?: string | null;
  onSaved: () => void;
  onDone: () => void;
}) {
  const household = useHousehold();
  const accounts = useAccounts(household.id).data ?? [];
  const decide = useDecideBankRows(household.id);
  const options = accounts.filter((a) => a.status === "active" && a.id !== row.accountId);
  const [accountId, setAccountId] = useState(options.some((a) => a.id === otherAccountId) ? (otherAccountId ?? "") : "");
  const [notes, setNotes] = useState("");
  const outgoing = row.amountMinor < 0;
  const own = accounts.find((a) => a.id === row.accountId)?.name ?? "this account";

  function submit(e: FormEvent) {
    e.preventDefault();
    decide.mutate(
      { action: "transfer", rows: [{ id: row.id, version: row.version }], otherAccountId: accountId, notes },
      { onSuccess: () => { onSaved(); onDone(); } },
    );
  }

  return (
    <form onSubmit={submit} className="flex flex-1 flex-col" aria-label="Transfer">
      <SheetBody
        footer={
          <>
            <span className="flex-1" />
            <Button type="button" variant="outline" onClick={onDone}>
              Cancel
            </Button>
            <Button type="submit" disabled={decide.isPending || !accountId}>
              Transfer
            </Button>
          </>
        }
      >
        <div className="rounded-lg border bg-muted/40 px-3 py-2 text-sm">
          <div className="font-medium">{row.text || row.counterpartyAccount || "Bank row"}</div>
          <div className="text-xs text-muted-foreground">
            {formatDay(row.date)} · {own} ·{" "}
            <span className="amount">
              {row.amountMinor > 0 ? "+" : ""}
              {formatMoney(row.amountMinor, row.currency)}
            </span>
          </div>
        </div>
        <Field label={outgoing ? "Moved to" : "Moved from"} name="otherAccountId" error={decide.error}>
          <NativeSelect className="w-full" required value={accountId} onChange={(e) => setAccountId(e.target.value)}>
            <NativeSelectOption value="">Choose an account</NativeSelectOption>
            {options.map((a) => (
              <NativeSelectOption key={a.id} value={a.id}>
                {a.name}
              </NativeSelectOption>
            ))}
          </NativeSelect>
        </Field>
        <Field label="Notes" name="notes" error={decide.error}>
          <Input value={notes} placeholder="e.g. Monthly savings" onChange={(e) => setNotes(e.target.value)} />
        </Field>
        <p className="text-xs text-muted-foreground">
          Money between your own accounts isn't spending or income. If the other account has its own feed, pair the two
          rows instead.
        </p>
        <FormError error={decide.error} fields={FIELDS} />
      </SheetBody>
    </form>
  );
}
