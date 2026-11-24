import { useState, type FormEvent } from "react";
import { useAccounts, useDecideBankRows, useMembers } from "../api/hooks";
import type { BankRow } from "../api/types";
import { formatDay } from "../lib/dates";
import { formatMoney } from "../lib/money";
import { useHousehold } from "../households/HouseholdPages";
import { Field, FormError, SheetBody } from "../ui";
import { Button } from "@/components/ui/button";
import { SubmitButton } from "@/ui";
import { Input } from "@/components/ui/input";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { draftSettle } from "./drafts";

const FIELDS = ["fromUserId", "toUserId", "notes"];

/** Files one or two bank rows as a member paying another back. */
export function SettleForm({ rows, onSaved, onDone }: { rows: BankRow[]; onSaved: () => void; onDone: () => void }) {
  const household = useHousehold();
  const accounts = useAccounts(household.id).data ?? [];
  const members = useMembers(household.id).data ?? [];
  const decide = useDecideBankRows(household.id);
  const draft = draftSettle(rows, (id) => accounts.find((a) => a.id === id)?.ownerUserId ?? null, members);
  const [fromUserId, setFromUserId] = useState(draft.fromUserId ?? "");
  const [toUserId, setToUserId] = useState(draft.toUserId ?? "");
  const [notes, setNotes] = useState("");

  function submit(e: FormEvent) {
    e.preventDefault();
    decide.mutate(
      { action: "settle", rows: rows.map((r) => ({ id: r.id, version: r.version })), fromUserId, toUserId, notes },
      { onSuccess: () => { onSaved(); onDone(); } },
    );
  }

  const picker = (value: string, set: (v: string) => void) => (
    <NativeSelect className="w-full" required value={value} onChange={(e) => set(e.target.value)}>
      <NativeSelectOption value="">Choose a member</NativeSelectOption>
      {members.map((m) => (
        <NativeSelectOption key={m.userId} value={m.userId}>
          {m.displayName}
        </NativeSelectOption>
      ))}
    </NativeSelect>
  );

  return (
    <form onSubmit={submit} className="flex flex-1 flex-col" aria-label="Settle between members">
      <SheetBody
        footer={
          <>
            <span className="flex-1" />
            <Button type="button" variant="outline" onClick={onDone}>
              Cancel
            </Button>
            <SubmitButton disabled={decide.isPending || !fromUserId || !toUserId}>
              Settle
            </SubmitButton>
          </>
        }
      >
        <div className="rounded-lg border bg-muted/40 px-3 py-2 text-sm">
          {rows.map((r) => (
            <div key={r.id}>
              <div className="font-medium">{r.text || r.counterpartyAccount || "Bank row"}</div>
              <div className="text-xs text-muted-foreground">
                {formatDay(r.date)} · {accounts.find((a) => a.id === r.accountId)?.name} ·{" "}
                <span className="amount">
                  {r.amountMinor > 0 ? "+" : ""}
                  {formatMoney(r.amountMinor, r.currency)}
                </span>
              </div>
            </div>
          ))}
        </div>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Paid by" name="fromUserId" error={decide.error}>
            {picker(fromUserId, setFromUserId)}
          </Field>
          <Field label="To" name="toUserId" error={decide.error}>
            {picker(toUserId, setToUserId)}
          </Field>
        </div>
        <div className="flex flex-col gap-1.5 text-sm">
          <span className="text-muted-foreground">Amount ({household.currency})</span>
          <span className="amount">
            {draft.amountMinor === null ? "No exchange rate for this day yet" : formatMoney(draft.amountMinor, household.currency)}
          </span>
        </div>
        <Field label="Notes" name="notes" error={decide.error}>
          <Input value={notes} placeholder="e.g. Rent share" onChange={(e) => setNotes(e.target.value)} />
        </Field>
        <p className="text-xs text-muted-foreground">A settlement only moves the balance between you. It isn't income or spending.</p>
        <FormError error={decide.error} fields={FIELDS} />
      </SheetBody>
    </form>
  );
}
