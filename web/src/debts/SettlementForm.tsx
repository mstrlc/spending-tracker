import { useRef, useState, type FormEvent } from "react";
import { useDeleteSettlement, useMembers, useSaveSettlement } from "../api/hooks";
import type { Debt, Settlement } from "../api/types";
import { todayIso } from "../lib/dates";
import { parseMoney, toInputValue } from "../lib/money";
import { useHousehold } from "../households/HouseholdPages";
import { useConfirm } from "../dialogs";
import { ErrorText, Field, FormError, SheetBody } from "../ui";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";

const FIELDS = ["date", "fromUserId", "toUserId", "amountMinor", "notes"];

/** Records money one member paid another back, or edits [settlement]. [owed] fills in a new one to settle it in full. */
export function SettlementForm({
  settlement,
  owed,
  defaultDate,
  onDone,
}: {
  settlement?: Settlement;
  owed?: Debt;
  defaultDate?: string;
  onDone: () => void;
}) {
  const household = useHousehold();
  const members = useMembers(household.id).data ?? [];
  const save = useSaveSettlement(household.id);
  const remove = useDeleteSettlement(household.id);
  const confirm = useConfirm();
  const amountRef = useRef<HTMLInputElement>(null);

  const [date, setDate] = useState(settlement?.date ?? defaultDate ?? todayIso());
  const [fromUserId, setFromUserId] = useState(settlement?.fromUserId ?? owed?.fromUserId ?? members[0]?.userId ?? "");
  const [toUserId, setToUserId] = useState(
    settlement?.toUserId ?? owed?.toUserId ?? members.find((m) => m.userId !== fromUserId)?.userId ?? "",
  );
  const initialAmount = settlement?.amountMinor ?? owed?.amountMinor;
  const [amount, setAmount] = useState(initialAmount ? toInputValue(initialAmount, household.currency) : "");
  const [notes, setNotes] = useState(settlement?.notes ?? "");
  const [amountError, setAmountError] = useState<string | null>(null);

  function submit(e: FormEvent) {
    e.preventDefault();
    const amountMinor = parseMoney(amount, household.currency);
    if (amountMinor === null || amountMinor <= 0) {
      setAmountError("Enter what was paid back, like 1500.");
      amountRef.current?.focus();
      return;
    }
    setAmountError(null);
    save.mutate(
      { id: settlement?.id, input: { date, fromUserId, toUserId, amountMinor, notes, version: settlement?.version } },
      { onSuccess: onDone },
    );
  }

  async function confirmDelete() {
    if (!settlement) return;
    if (await confirm({ title: "Delete this settlement?", description: "The balance goes back to what it was before it." })) {
      remove.mutate({ id: settlement.id, version: settlement.version }, { onSuccess: onDone });
    }
  }

  return (
    <form onSubmit={submit} className="flex flex-1 flex-col" aria-label={settlement ? "Edit settlement" : "Record settlement"}>
      <SheetBody
        footer={
          <>
            {settlement && (
              <Button type="button" variant="ghost" className="text-destructive" onClick={confirmDelete} disabled={remove.isPending}>
                Delete
              </Button>
            )}
            <span className="flex-1" />
            <Button type="button" variant="outline" onClick={onDone}>
              Cancel
            </Button>
            <Button type="submit" disabled={save.isPending}>
              {settlement ? "Save" : "Record"}
            </Button>
          </>
        }
      >
        <div className="grid grid-cols-2 gap-3">
          <Field label={`Amount (${household.currency})`} name="amountMinor" error={save.error} hint={amountError}>
            <Input
              ref={amountRef}
              data-autofocus
              inputMode="decimal"
              required
              placeholder="0.00"
              className="amount h-11 text-lg md:text-lg"
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
            />
          </Field>
          <Field label="Date" name="date" error={save.error}>
            <Input type="date" required className="h-11" value={date} onChange={(e) => setDate(e.target.value)} />
          </Field>
        </div>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Paid by" name="fromUserId" error={save.error}>
            <NativeSelect className="w-full" value={fromUserId} onChange={(e) => setFromUserId(e.target.value)}>
              {members.map((m) => (
                <NativeSelectOption key={m.userId} value={m.userId}>
                  {m.displayName}
                </NativeSelectOption>
              ))}
            </NativeSelect>
          </Field>
          <Field label="To" name="toUserId" error={save.error}>
            <NativeSelect className="w-full" value={toUserId} onChange={(e) => setToUserId(e.target.value)}>
              {members.map((m) => (
                <NativeSelectOption key={m.userId} value={m.userId}>
                  {m.displayName}
                </NativeSelectOption>
              ))}
            </NativeSelect>
          </Field>
        </div>
        <Field label="Notes" name="notes" error={save.error}>
          <Input value={notes} placeholder="e.g. Revolut transfer" onChange={(e) => setNotes(e.target.value)} />
        </Field>
        <p className="text-xs text-muted-foreground">A settlement only moves the balance between you. It isn't income or spending.</p>
        <FormError error={save.error} fields={FIELDS} />
        <ErrorText error={remove.error} />
      </SheetBody>
    </form>
  );
}
