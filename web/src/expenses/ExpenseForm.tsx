import { useRef, useState, type FormEvent } from "react";
import { useAccounts, useCategories, useDeleteExpense, useMembers, useSaveExpense, useTrips } from "../api/hooks";
import type { Expense } from "../api/types";
import { categoryTree } from "../categories/tree";
import { todayIso } from "../lib/dates";
import { formatMoney, parseMoney, toInputValue } from "../lib/money";
import { useHousehold } from "../households/HouseholdPages";
import { useConfirm } from "../dialogs";
import { ErrorText, Field, FormError, SheetBody } from "../ui";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect, NativeSelectOptGroup, NativeSelectOption } from "@/components/ui/native-select";

const FIELDS = [
  "date",
  "amountMinor",
  "merchant",
  "categoryId",
  "accountId",
  "paidByUserId",
  "tripId",
  "originalAmountMinor",
  "originalCurrency",
  "notes",
];

/**
 * Adds expenses (staying open for the next one) or edits [expense]. Lives in a side sheet;
 * [onSaved] reports each saved row, [onDone] closes the sheet.
 */
export function ExpenseForm({
  expense,
  defaultDate,
  onSaved,
  onDone,
}: {
  expense?: Expense;
  defaultDate?: string;
  onSaved?: (expense: Expense) => void;
  onDone: () => void;
}) {
  const household = useHousehold();
  const categories = useCategories(household.id).data ?? [];
  const accounts = useAccounts(household.id).data ?? [];
  const members = useMembers(household.id).data ?? [];
  const trips = useTrips(household.id).data ?? [];
  const save = useSaveExpense(household.id);
  const remove = useDeleteExpense(household.id);
  const amountRef = useRef<HTMLInputElement>(null);
  const confirm = useConfirm();

  const [date, setDate] = useState(expense?.date ?? defaultDate ?? todayIso());
  const [amount, setAmount] = useState(expense ? toInputValue(expense.amountMinor, household.currency) : "");
  const [merchant, setMerchant] = useState(expense?.merchant ?? "");
  const [categoryId, setCategoryId] = useState(expense?.categoryId ?? "");
  const [accountId, setAccountId] = useState(expense?.accountId ?? "");
  const [paidByUserId, setPaidByUserId] = useState(expense?.paidByUserId ?? "");
  const [notes, setNotes] = useState(expense?.notes ?? "");
  const [tripId, setTripId] = useState(expense?.tripId ?? "");
  const [foreign, setForeign] = useState(expense?.originalCurrency != null);
  const [originalCurrency, setOriginalCurrency] = useState(expense?.originalCurrency ?? "EUR");
  const [originalAmount, setOriginalAmount] = useState(
    expense?.originalAmountMinor != null && expense.originalCurrency
      ? toInputValue(expense.originalAmountMinor, expense.originalCurrency)
      : "",
  );
  const [amountError, setAmountError] = useState<string | null>(null);
  const [lastAdded, setLastAdded] = useState<string | null>(null);

  // Archived categories and closed accounts stay selectable only for rows that already use them.
  const tree = categoryTree(categories.filter((c) => !c.archived || c.id === expense?.categoryId));
  const accountOptions = accounts.filter((a) => a.status === "active" || a.id === expense?.accountId);

  function submit(e: FormEvent) {
    e.preventDefault();
    const amountMinor = parseMoney(amount, household.currency);
    if (amountMinor === null || amountMinor === 0) {
      setAmountError("Enter an amount like 129.90. Use a minus sign for a refund.");
      amountRef.current?.focus();
      return;
    }
    const currency = originalCurrency.trim().toUpperCase();
    const originalAmountMinor = foreign ? parseMoney(originalAmount, /^[A-Z]{3}$/.test(currency) ? currency : "EUR") : null;
    if (foreign && (originalAmountMinor === null || originalAmountMinor === 0)) {
      setAmountError(`Enter what it cost in ${currency || "the other currency"}, or untick "Paid in another currency".`);
      return;
    }
    setAmountError(null);
    save.mutate(
      {
        id: expense?.id,
        input: {
          date,
          amountMinor,
          merchant,
          categoryId: categoryId || null,
          accountId: accountId || null,
          paidByUserId: paidByUserId || null,
          tripId: tripId || null,
          originalAmountMinor: foreign ? originalAmountMinor : null,
          originalCurrency: foreign ? currency : null,
          notes,
          version: expense?.version,
        },
      },
      {
        onSuccess: (saved) => {
          onSaved?.(saved);
          if (expense) {
            onDone();
            return;
          }
          // Stay open for the next expense, keeping date, category, account and payer.
          setLastAdded(`Added ${saved.merchant || "expense"} · ${formatMoney(saved.amountMinor, household.currency)}`);
          setAmount("");
          setOriginalAmount("");
          setMerchant("");
          setNotes("");
          amountRef.current?.focus();
        },
      },
    );
  }

  async function confirmDelete() {
    if (!expense) return;
    if (await confirm({ title: `Delete ${expense.merchant || "this expense"}?`, description: "This can't be undone." })) {
      remove.mutate({ id: expense.id, version: expense.version }, { onSuccess: onDone });
    }
  }

  return (
    <form onSubmit={submit} className="flex flex-1 flex-col" aria-label={expense ? "Edit expense" : "Add expense"}>
      <SheetBody
        footer={
          <>
            {expense && (
              <Button type="button" variant="ghost" className="text-destructive" onClick={confirmDelete} disabled={remove.isPending}>
                Delete
              </Button>
            )}
            <span className="flex-1" />
            <Button type="button" variant="outline" onClick={onDone}>
              {expense ? "Cancel" : "Done"}
            </Button>
            <Button type="submit" disabled={save.isPending}>
              {expense ? "Save" : "Add expense"}
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
          <Field label="Date" name="date" error={save.error}>
            <Input type="date" required className="h-11" value={date} onChange={(e) => setDate(e.target.value)} />
          </Field>
        </div>
        <Field label="Merchant" name="merchant" error={save.error}>
          <Input value={merchant} placeholder="Where you paid" onChange={(e) => setMerchant(e.target.value)} />
        </Field>
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
          <Field label="Paid by" name="paidByUserId" error={save.error}>
            <NativeSelect className="w-full" value={paidByUserId} onChange={(e) => setPaidByUserId(e.target.value)}>
              <NativeSelectOption value="">Shared</NativeSelectOption>
              {members.map((m) => (
                <NativeSelectOption key={m.userId} value={m.userId}>
                  {m.displayName}
                </NativeSelectOption>
              ))}
            </NativeSelect>
          </Field>
        )}
        {(trips.length > 0 || tripId) && (
          <Field label="Trip" name="tripId" error={save.error}>
            <NativeSelect className="w-full" value={tripId} onChange={(e) => setTripId(e.target.value)}>
              <NativeSelectOption value="">None</NativeSelectOption>
              {trips.map((t) => (
                <NativeSelectOption key={t.id} value={t.id}>
                  {t.name}
                </NativeSelectOption>
              ))}
            </NativeSelect>
          </Field>
        )}
        <div className="flex items-center gap-2">
          <Checkbox id="expense-foreign" checked={foreign} onCheckedChange={(checked) => setForeign(checked === true)} />
          <Label htmlFor="expense-foreign" className="font-normal">
            Paid in another currency
          </Label>
        </div>
        {foreign && (
          <div className="grid grid-cols-2 gap-3">
            <Field label="Amount paid" name="originalAmountMinor" error={save.error}>
              <Input
                inputMode="decimal"
                placeholder="0.00"
                className="amount"
                value={originalAmount}
                onChange={(e) => setOriginalAmount(e.target.value)}
              />
            </Field>
            <Field label="Currency" name="originalCurrency" error={save.error}>
              <Input
                maxLength={3}
                placeholder="EUR"
                value={originalCurrency}
                onChange={(e) => setOriginalCurrency(e.target.value.toUpperCase())}
              />
            </Field>
          </div>
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
