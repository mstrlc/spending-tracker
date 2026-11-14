import { useRef, useState, type FormEvent } from "react";
import { useAccounts, useCategories, useDeleteRecurring, useMembers, useMerchants, useSaveRecurring } from "../api/hooks";
import type { RecurringPayment, RecurringPaymentInput, RecurringStatus, RecurringSuggestion } from "../api/types";
import { categoryTree, spendingCategories } from "../categories/tree";
import { useHousehold } from "../households/HouseholdPages";
import { todayIso } from "../lib/dates";
import { parseMoney, toInputValue } from "../lib/money";
import { ErrorText, Field, FormError, SheetBody } from "../ui";
import { useUndoableDelete, useUndoableSave } from "../undo";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { NativeSelect, NativeSelectOptGroup, NativeSelectOption } from "@/components/ui/native-select";

const FIELDS = ["merchantId", "expectedAmountMinor", "intervalMonths", "anchorDate", "categoryId", "accountId", "paidByUserId", "notes"];

export const INTERVALS: { months: number; label: string }[] = [
  { months: 1, label: "Every month" },
  { months: 2, label: "Every 2 months" },
  { months: 3, label: "Every quarter" },
  { months: 6, label: "Every 6 months" },
  { months: 12, label: "Every year" },
];

export function paymentToInput(p: RecurringPayment, changes: Partial<RecurringPaymentInput> = {}): RecurringPaymentInput {
  return {
    merchantId: p.merchantId,
    expectedAmountMinor: p.expectedAmountMinor,
    intervalMonths: p.intervalMonths,
    anchorDate: p.anchorDate,
    status: p.status,
    categoryId: p.categoryId,
    accountId: p.accountId,
    paidByUserId: p.paidByUserId,
    notes: p.notes,
    version: p.version,
    ...changes,
  };
}

/** Adds a recurring payment, confirms [suggestion] as one, or edits [payment]. */
export function RecurringForm({
  payment,
  suggestion,
  onDone,
}: {
  payment?: RecurringPayment;
  suggestion?: RecurringSuggestion;
  onDone: () => void;
}) {
  const household = useHousehold();
  const merchants = useMerchants(household.id).data ?? [];
  const categories = useCategories(household.id).data ?? [];
  const accounts = useAccounts(household.id).data ?? [];
  const members = useMembers(household.id).data ?? [];
  const saveRecurring = useSaveRecurring(household.id);
  const deleteRecurring = useDeleteRecurring(household.id);
  const save = useUndoableSave("Recurring payment", saveRecurring, deleteRecurring);
  const remove = useUndoableDelete("Recurring payment", deleteRecurring);
  const amountRef = useRef<HTMLInputElement>(null);

  const [merchantId, setMerchantId] = useState(payment?.merchantId ?? suggestion?.merchantId ?? "");
  const initialAmount = payment?.expectedAmountMinor ?? suggestion?.amountMinor;
  const [amount, setAmount] = useState(initialAmount !== undefined ? toInputValue(initialAmount, household.currency) : "");
  const [intervalMonths, setIntervalMonths] = useState(payment?.intervalMonths ?? suggestion?.intervalMonths ?? 1);
  const [anchorDate, setAnchorDate] = useState(payment?.anchorDate ?? suggestion?.anchorDate ?? todayIso());
  const [status, setStatus] = useState<RecurringStatus>(payment?.status === "ended" ? "ended" : "active");
  const [categoryId, setCategoryId] = useState(payment?.categoryId ?? suggestion?.categoryId ?? "");
  const [accountId, setAccountId] = useState(payment?.accountId ?? suggestion?.accountId ?? "");
  const [paidByUserId, setPaidByUserId] = useState(payment?.paidByUserId ?? suggestion?.paidByUserId ?? "");
  const [notes, setNotes] = useState(payment?.notes ?? "");
  const [amountError, setAmountError] = useState<string | null>(null);

  const tree = categoryTree(spendingCategories(categories).filter((c) => !c.archived || c.id === categoryId));
  const shownAccounts = accounts.filter((a) => a.status !== "closed" || a.id === accountId);
  const sortedMerchants = [...merchants].sort((a, b) => a.name.localeCompare(b.name));

  function submit(e: FormEvent) {
    e.preventDefault();
    const expectedAmountMinor = parseMoney(amount, household.currency);
    if (expectedAmountMinor === null || expectedAmountMinor < 0) {
      setAmountError("Enter what it usually costs, like 299.");
      amountRef.current?.focus();
      return;
    }
    setAmountError(null);
    save.mutate(
      {
        id: payment?.id,
        input: {
          merchantId,
          expectedAmountMinor,
          intervalMonths,
          anchorDate,
          status,
          categoryId: categoryId || null,
          accountId: accountId || null,
          paidByUserId: paidByUserId || null,
          notes,
          version: payment?.version,
        },
        previous: payment ? paymentToInput(payment) : undefined,
      },
      { onSuccess: onDone },
    );
  }

  function handleDelete() {
    if (!payment) return;
    remove.schedule({ id: payment.id, version: payment.version });
    onDone();
  }

  return (
    <form onSubmit={submit} className="flex flex-1 flex-col" aria-label={payment ? "Edit recurring payment" : "Add recurring payment"}>
      <SheetBody
        footer={
          <>
            {payment && (
              <Button type="button" variant="ghost" className="text-destructive" onClick={handleDelete} disabled={remove.isPending}>
                Delete
              </Button>
            )}
            <span className="flex-1" />
            <Button type="button" variant="outline" onClick={onDone}>
              Cancel
            </Button>
            <Button type="submit" disabled={save.isPending}>
              {payment ? "Save" : suggestion ? "Confirm" : "Add"}
            </Button>
          </>
        }
      >
        <Field label="Merchant" name="merchantId" error={save.error}>
          <NativeSelect className="w-full" required value={merchantId} onChange={(e) => setMerchantId(e.target.value)}>
            <NativeSelectOption value="" disabled>
              Pick a merchant
            </NativeSelectOption>
            {sortedMerchants.map((m) => (
              <NativeSelectOption key={m.id} value={m.id}>
                {m.name}
              </NativeSelectOption>
            ))}
          </NativeSelect>
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label={`Usually (${household.currency})`} name="expectedAmountMinor" error={save.error} hint={amountError}>
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
          <Field label="How often" name="intervalMonths" error={save.error}>
            <NativeSelect className="h-11 w-full" value={intervalMonths} onChange={(e) => setIntervalMonths(Number(e.target.value))}>
              {INTERVALS.map((i) => (
                <NativeSelectOption key={i.months} value={i.months}>
                  {i.label}
                </NativeSelectOption>
              ))}
            </NativeSelect>
          </Field>
        </div>
        <Field label="Charged on" name="anchorDate" error={save.error}>
          <Input type="date" required value={anchorDate} onChange={(e) => setAnchorDate(e.target.value)} />
        </Field>
        <p className="-mt-2 text-xs text-muted-foreground">
          A date it was or will be charged. It's due again on the same day {intervalMonths === 1 ? "every month" : "each time"} from
          then, and a charge up to {intervalMonths === 1 ? 10 : 20} days either side counts.
        </p>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Category" name="categoryId" error={save.error}>
            <NativeSelect className="w-full" value={categoryId} onChange={(e) => setCategoryId(e.target.value)}>
              <NativeSelectOption value="">Uncategorized</NativeSelectOption>
              {tree.map((parent) => (
                <NativeSelectOptGroup key={parent.id} label={parent.name}>
                  <NativeSelectOption value={parent.id}>{parent.name}</NativeSelectOption>
                  {parent.children.map((child) => (
                    <NativeSelectOption key={child.id} value={child.id}>
                      {parent.name} › {child.name}
                    </NativeSelectOption>
                  ))}
                </NativeSelectOptGroup>
              ))}
            </NativeSelect>
          </Field>
          <Field label="Whose" name="paidByUserId" error={save.error}>
            <NativeSelect className="w-full" value={paidByUserId} onChange={(e) => setPaidByUserId(e.target.value)}>
              <NativeSelectOption value="">Joint</NativeSelectOption>
              {members.map((m) => (
                <NativeSelectOption key={m.userId} value={m.userId}>
                  {m.displayName}
                </NativeSelectOption>
              ))}
            </NativeSelect>
          </Field>
        </div>
        <Field label="Paid from" name="accountId" error={save.error}>
          <NativeSelect className="w-full" value={accountId} onChange={(e) => setAccountId(e.target.value)}>
            <NativeSelectOption value="">Any account</NativeSelectOption>
            {shownAccounts.map((a) => (
              <NativeSelectOption key={a.id} value={a.id}>
                {a.name}
              </NativeSelectOption>
            ))}
          </NativeSelect>
        </Field>
        {payment && (
          <Field label="Status" name="status" error={save.error}>
            <NativeSelect className="w-full" value={status} onChange={(e) => setStatus(e.target.value as RecurringStatus)}>
              <NativeSelectOption value="active">Active</NativeSelectOption>
              <NativeSelectOption value="ended">Ended: no longer charged</NativeSelectOption>
            </NativeSelect>
          </Field>
        )}
        <Field label="Notes" name="notes" error={save.error}>
          <Input value={notes} placeholder="e.g. family plan, renews in March" onChange={(e) => setNotes(e.target.value)} />
        </Field>
        <FormError error={save.error} fields={FIELDS} />
        <ErrorText error={remove.error} />
      </SheetBody>
    </form>
  );
}
