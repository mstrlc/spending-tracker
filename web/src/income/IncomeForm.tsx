import { useRef, useState, type FormEvent } from "react";
import { useAccounts, useDeleteIncome, useMembers, useSaveIncome } from "../api/hooks";
import type { Income } from "../api/types";
import { todayIso } from "../lib/dates";
import { formatMoney, parseMoney, toInputValue } from "../lib/money";
import { useHousehold } from "../households/HouseholdPages";
import { useConfirm } from "../dialogs";
import { ErrorText, Field, FormError, SheetBody } from "../ui";
import { Button } from "@/components/ui/button";
import { FieldDescription } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";

const FIELDS = ["date", "earnedMonth", "amountMinor", "source", "accountId", "recipientUserId", "notes"];

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
  const confirm = useConfirm();

  const [date, setDate] = useState(income?.date ?? defaultDate ?? todayIso());
  const [amount, setAmount] = useState(income ? toInputValue(income.amountMinor, household.currency) : "");
  const [source, setSource] = useState(income?.source ?? "");
  const [accountId, setAccountId] = useState(income?.accountId ?? "");
  const [recipientUserId, setRecipientUserId] = useState(income?.recipientUserId ?? "");
  const [notes, setNotes] = useState(income?.notes ?? "");
  // The month it was earned; null follows the date (most income is earned the month it arrives).
  const [earnedMonth, setEarnedMonth] = useState<string | null>(
    income && income.earnedMonth !== income.date.slice(0, 7) ? income.earnedMonth : null,
  );
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
          earnedMonth: earnedMonth ?? date.slice(0, 7),
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

  async function confirmDelete() {
    if (!income) return;
    if (await confirm({ title: `Delete ${income.source || "this income"}?`, description: "This can't be undone." })) {
      remove.mutate({ id: income.id, version: income.version }, { onSuccess: onDone });
    }
  }

  return (
    <form onSubmit={submit} className="flex flex-1 flex-col" aria-label={income ? "Edit income" : "Add income"}>
      <SheetBody
        footer={
          <>
            {income && (
              <Button type="button" variant="ghost" className="text-destructive" onClick={confirmDelete} disabled={remove.isPending}>
                Delete
              </Button>
            )}
            <span className="flex-1" />
            <Button type="button" variant="outline" onClick={onDone}>
              {income ? "Cancel" : "Done"}
            </Button>
            <Button type="submit" disabled={save.isPending}>
              {income ? "Save" : "Add income"}
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
              aria-invalid={amountError !== null}
            />
          </Field>
          <Field label="Arrived" name="date" error={save.error}>
            <Input type="date" required className="h-11" value={date} onChange={(e) => setDate(e.target.value)} />
          </Field>
        </div>
        <div className="flex flex-col gap-1.5">
          <Field label="Earned in" name="earnedMonth" error={save.error}>
            <Input
              type="month"
              required
              value={earnedMonth ?? date.slice(0, 7)}
              onChange={(e) => setEarnedMonth(e.target.value && e.target.value !== date.slice(0, 7) ? e.target.value : null)}
            />
          </Field>
          <FieldDescription>The month this pay is for: October's salary paid on 2 November counts for October.</FieldDescription>
        </div>
        <Field label="Source" name="source" error={save.error}>
          <Input value={source} placeholder="Employer, client, who paid" onChange={(e) => setSource(e.target.value)} />
        </Field>
        <Field label="Account" name="accountId" error={save.error}>
          <NativeSelect className="w-full" value={accountId} onChange={(e) => setAccountId(e.target.value)}>
            <NativeSelectOption value="">None</NativeSelectOption>
            {accountOptions.map((a) => (
              <NativeSelectOption key={a.id} value={a.id}>
                {a.name}
              </NativeSelectOption>
            ))}
          </NativeSelect>
        </Field>
        {members.length > 1 && (
          <Field label="Received by" name="recipientUserId" error={save.error}>
            <NativeSelect className="w-full" value={recipientUserId} onChange={(e) => setRecipientUserId(e.target.value)}>
              <NativeSelectOption value="">Shared</NativeSelectOption>
              {members.map((m) => (
                <NativeSelectOption key={m.userId} value={m.userId}>
                  {m.displayName}
                </NativeSelectOption>
              ))}
            </NativeSelect>
          </Field>
        )}
        <Field label="Notes" name="notes" error={save.error}>
          <Input value={notes} onChange={(e) => setNotes(e.target.value)} />
        </Field>
        <FormError error={save.error} fields={FIELDS} />
        <ErrorText error={remove.error} />
        {lastAdded && !save.error && (
          <p className="rounded-md bg-muted px-3 py-2 text-sm" role="status">
            {lastAdded}
          </p>
        )}
      </SheetBody>
    </form>
  );
}
