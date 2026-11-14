import { useEffect, useRef, useState, type FormEvent } from "react";
import { Plus, X } from "lucide-react";
import {
  useAccounts,
  useCategories,
  useDeleteExpense,
  useMembers,
  useMerchantExpenses,
  useMerchants,
  useSaveExpense,
  useTrips,
} from "../api/hooks";
import type { BankRowRef, Expense } from "../api/types";
import { categoryTree } from "../categories/tree";
import { formatDay, todayIso } from "../lib/dates";
import { formatMoney, parseMoney, toInputValue } from "../lib/money";
import { useHousehold } from "../households/HouseholdPages";
import { useConfirm } from "../dialogs";
import { ErrorText, Field, FormError, SheetBody } from "../ui";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect, NativeSelectOptGroup, NativeSelectOption } from "@/components/ui/native-select";
import { SplitEditor } from "../debts/SplitEditor";
import { draftFrom, owesLine, sharesFrom } from "../debts/split";
import { refundLines, refundOriginals } from "./refunds";

const FIELDS = [
  "date",
  "amountMinor",
  "merchant",
  "categoryId",
  "lines",
  "accountId",
  "paidByUserId",
  "tripId",
  "originalAmountMinor",
  "originalCurrency",
  "notes",
  "split",
  "refundOfId",
];

type LineDraft = {
  categoryId: string;
  amount: string;
  notes: string;
};

/** What a new expense starts with, such as a bank row's suggestion. */
export type ExpenseDraft = Partial<
  Pick<
    Expense,
    | "date"
    | "amountMinor"
    | "merchant"
    | "categoryId"
    | "accountId"
    | "paidByUserId"
    | "notes"
    | "originalAmountMinor"
    | "originalCurrency"
    | "refundOfId"
  >
>;

/**
 * Adds expenses (staying open for the next one) or edits [expense]. Lives in a side sheet;
 * [onSaved] reports each saved row, [onDone] closes the sheet. With [bankRows], it adds the one expense those bank
 * rows become, starting from [draft], and closes once saved. A negative amount can be a refund of an earlier expense at
 * the same merchant: picking it fills in its category (or lines) and whose; [onOpenExpense] jumps between a refund and
 * its original.
 */
export function ExpenseForm({
  expense,
  draft,
  bankRows,
  defaultDate,
  onSaved,
  onOpenExpense,
  onDone,
}: {
  expense?: Expense;
  draft?: ExpenseDraft;
  bankRows?: BankRowRef[];
  defaultDate?: string;
  onSaved?: (expense: Expense) => void;
  onOpenExpense?: (expense: Expense) => void;
  onDone: () => void;
}) {
  const start: ExpenseDraft = expense ?? draft ?? {};
  const household = useHousehold();
  const categories = useCategories(household.id).data ?? [];
  const accounts = useAccounts(household.id).data ?? [];
  const members = useMembers(household.id).data ?? [];
  const trips = useTrips(household.id).data ?? [];
  const merchants = useMerchants(household.id).data ?? [];
  const save = useSaveExpense(household.id);
  const remove = useDeleteExpense(household.id);
  const amountRef = useRef<HTMLInputElement>(null);
  const confirm = useConfirm();

  const [date, setDate] = useState(start.date ?? defaultDate ?? todayIso());
  const [amount, setAmount] = useState(start.amountMinor != null ? toInputValue(start.amountMinor, household.currency) : "");
  const [merchant, setMerchant] = useState(start.merchant ?? "");
  const [categoryId, setCategoryId] = useState(start.categoryId ?? "");
  const [hasLines, setHasLines] = useState((expense?.lines.length ?? 0) > 0);
  const [lines, setLines] = useState<LineDraft[]>(() =>
    expense && expense.lines.length > 0
      ? expense.lines.map((l) => ({
          categoryId: l.categoryId ?? "",
          amount: toInputValue(l.amountMinor, household.currency),
          notes: l.notes,
        }))
      : [
          { categoryId: expense?.categoryId ?? "", amount: "", notes: "" },
          { categoryId: "", amount: "", notes: "" },
        ],
  );
  const [accountId, setAccountId] = useState(start.accountId ?? "");
  const [paidByUserId, setPaidByUserId] = useState(start.paidByUserId ?? "");
  // Whose it is follows the account until someone picks it (a draft's suggestion counts as picked).
  const [ownerPicked, setOwnerPicked] = useState(expense !== undefined || draft?.paidByUserId !== undefined);
  const [createsDebt, setCreatesDebt] = useState(expense?.createsDebt ?? true);
  const [ownSplit, setOwnSplit] = useState(expense?.split != null);
  const [splitDraft, setSplitDraft] = useState(() =>
    draftFrom(expense?.split ?? members.map((m) => ({ userId: m.userId, weight: 1 })), members),
  );
  const [notes, setNotes] = useState(start.notes ?? "");
  const [tripId, setTripId] = useState(expense?.tripId ?? "");
  const [foreign, setForeign] = useState(start.originalCurrency != null);
  const [originalCurrency, setOriginalCurrency] = useState(start.originalCurrency ?? "EUR");
  const [originalAmount, setOriginalAmount] = useState(
    start.originalAmountMinor != null && start.originalCurrency
      ? toInputValue(start.originalAmountMinor, start.originalCurrency)
      : "",
  );
  const [refundOfId, setRefundOfId] = useState(start.refundOfId ?? "");
  const [amountError, setAmountError] = useState<string | null>(null);
  const [lastAdded, setLastAdded] = useState<string | null>(null);

  // Archived categories and closed accounts stay selectable only for rows that already use them.
  const usedCategoryIds = new Set([expense?.categoryId, ...(expense?.lines.map((l) => l.categoryId) ?? [])]);
  const tree = categoryTree(categories.filter((c) => !c.archived || usedCategoryIds.has(c.id)));
  const accountOptions = accounts.filter((a) => a.status === "active" || a.id === expense?.accountId);
  const knownMerchant = merchants.find((m) => m.name.toLowerCase() === merchant.trim().toLowerCase());
  const merchantHint = merchant.trim() && !knownMerchant && merchants.length > 0 ? "New merchant: it'll be added to your list." : null;
  const totalMinor = parseMoney(amount, household.currency);
  const linesTotalMinor = lines.reduce((sum, l) => sum + (parseMoney(l.amount, household.currency) ?? 0), 0);
  const remainingMinor = totalMinor !== null ? totalMinor - linesTotalMinor : null;

  // A refund's original is picked from the expenses at its merchant; the original itself lists its refunds there too.
  const showRefund = (totalMinor ?? 0) < 0 || refundOfId !== "";
  const hasRefunds = (expense?.refundedMinor ?? 0) > 0;
  const atMerchant = useMerchantExpenses(household.id, showRefund || hasRefunds ? knownMerchant?.id : undefined).data ?? [];
  const originals = refundOriginals(atMerchant.filter((e) => e.id !== expense?.id), refundOfId || null);
  const original = atMerchant.find((e) => e.id === refundOfId);
  const refunds = expense ? atMerchant.filter((e) => e.refundOfId === expense.id) : [];

  /** Fills in what a refund takes from its original: category (or lines), whose, trip, and the account when none is set. */
  function applyOriginal(o: Expense) {
    setPaidByUserId(o.paidByUserId ?? "");
    setOwnerPicked(true);
    setTripId(o.tripId ?? "");
    if (!accountId && o.accountId) setAccountId(o.accountId);
    if (o.lines.length > 0) {
      const refund = totalMinor !== null && totalMinor < 0 ? totalMinor : null;
      const parts = refund !== null ? refundLines(o.lines, refund) : o.lines.map((l) => ({ categoryId: l.categoryId, amountMinor: 0, notes: "" }));
      setHasLines(true);
      setLines(
        parts.map((l) => ({
          categoryId: l.categoryId ?? "",
          amount: refund !== null ? toInputValue(l.amountMinor, household.currency) : "",
          notes: "",
        })),
      );
    } else {
      setHasLines(false);
      setCategoryId(o.categoryId ?? "");
    }
  }

  function pickOriginal(id: string) {
    setRefundOfId(id);
    const picked = atMerchant.find((e) => e.id === id);
    if (picked) applyOriginal(picked);
  }

  // A bank row's refund arrives with its original chosen: fill in from it once it has loaded, once.
  const applied = useRef(false);
  useEffect(() => {
    if (expense || applied.current || !draft?.refundOfId || !original) return;
    applied.current = true;
    applyOriginal(original);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [original]);

  function updateLine(index: number, patch: Partial<LineDraft>) {
    setLines((prev) => prev.map((l, i) => (i === index ? { ...l, ...patch } : l)));
  }

  function addLine() {
    const nextAmount = remainingMinor !== null && remainingMinor !== 0 ? toInputValue(remainingMinor, household.currency) : "";
    setLines((prev) => [...prev, { categoryId: "", amount: nextAmount, notes: "" }]);
  }

  function removeLine(index: number) {
    setLines((prev) => (prev.length <= 2 ? prev : prev.filter((_, i) => i !== index)));
  }

  // Typing a known merchant on a new expense files it under the merchant's category, unless one was picked.
  function changeMerchant(value: string) {
    setMerchant(value);
    if (expense || categoryId) return;
    const known = merchants.find((m) => m.name.toLowerCase() === value.trim().toLowerCase());
    if (known?.defaultCategoryId && categories.some((c) => c.id === known.defaultCategoryId && !c.archived)) {
      setCategoryId(known.defaultCategoryId);
    }
  }

  function changeAccount(value: string) {
    setAccountId(value);
    if (ownerPicked) return;
    setPaidByUserId(accounts.find((a) => a.id === value)?.ownerUserId ?? "");
  }

  // Who paid: the account's owner, null for a joint account, undefined when there's no account.
  const account = accounts.find((a) => a.id === accountId);
  const payer = account ? (account.ownerUserId ?? null) : undefined;
  const mismatch = payer !== undefined && payer !== (paidByUserId || null);
  const name = (userId: string | null) => members.find((m) => m.userId === userId)?.displayName ?? "Someone";
  const debtLabel = !paidByUserId
    ? `The others owe ${name(payer ?? null)} their share`
    : payer === null
      ? `${name(paidByUserId)} owes the others their share of the joint account`
      : `${name(paidByUserId)} owes ${name(payer ?? null)} for it`;

  function submit(e: FormEvent) {
    e.preventDefault();
    const split = ownSplit ? sharesFrom(splitDraft) : null;
    if (ownSplit && !split) {
      setAmountError('Give at least one person a share of this expense, in whole numbers, or untick "Split this one differently".');
      return;
    }
    const amountMinor = parseMoney(amount, household.currency);
    if (amountMinor === null || amountMinor === 0) {
      setAmountError("Enter an amount like 129.90. Use a minus sign for a refund.");
      amountRef.current?.focus();
      return;
    }
    const parsedLines = hasLines
      ? lines.map((l) => ({
          categoryId: l.categoryId || null,
          amountMinor: parseMoney(l.amount, household.currency),
          notes: l.notes,
        }))
      : [];
    if (hasLines) {
      if (
        parsedLines.length < 2 ||
        parsedLines.some((l) => l.amountMinor === null || l.amountMinor === 0 || Math.sign(l.amountMinor) !== Math.sign(amountMinor))
      ) {
        setAmountError("Give each split line an amount with the same sign as the total.");
        return;
      }
      const lineSum = parsedLines.reduce((sum, l) => sum + (l.amountMinor ?? 0), 0);
      if (lineSum !== amountMinor) {
        setAmountError(
          `Split lines add up to ${formatMoney(lineSum, household.currency)}, which doesn't match ${formatMoney(amountMinor, household.currency)}.`,
        );
        return;
      }
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
          categoryId: hasLines ? null : categoryId || null,
          accountId: accountId || null,
          paidByUserId: paidByUserId || null,
          tripId: tripId || null,
          originalAmountMinor: foreign ? originalAmountMinor : null,
          originalCurrency: foreign ? currency : null,
          notes,
          split,
          createsDebt,
          lines: hasLines
            ? parsedLines.map((l) => ({ categoryId: l.categoryId, amountMinor: l.amountMinor!, notes: l.notes }))
            : [],
          refundOfId: refundOfId && amountMinor < 0 ? refundOfId : null,
          version: expense?.version,
          bankRows: expense ? [] : (bankRows ?? []),
        },
      },
      {
        onSuccess: (saved) => {
          onSaved?.(saved);
          if (expense || bankRows) {
            onDone();
            return;
          }
          // Stay open for the next expense, keeping date, category, account and payer.
          setLastAdded(`Added ${saved.merchant || "expense"} · ${formatMoney(saved.amountMinor, household.currency)}`);
          setAmount("");
          setOriginalAmount("");
          setMerchant("");
          setNotes("");
          setRefundOfId("");
          setHasLines(false);
          setLines([
            { categoryId: "", amount: "", notes: "" },
            { categoryId: "", amount: "", notes: "" },
          ]);
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
              {expense || bankRows ? "Cancel" : "Done"}
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
          <Input
            list="expense-merchants"
            autoComplete="off"
            value={merchant}
            placeholder="Where you paid"
            onChange={(e) => changeMerchant(e.target.value)}
          />
        </Field>
        <datalist id="expense-merchants">
          {merchants.map((m) => (
            <option key={m.id} value={m.name} />
          ))}
        </datalist>
        {merchantHint && <p className="-mt-2 text-xs text-muted-foreground">{merchantHint}</p>}
        {showRefund && (
          <div className="flex flex-col gap-1.5">
            <Field label="Refund of" name="refundOfId" error={save.error}>
              <NativeSelect className="w-full" value={refundOfId} onChange={(e) => pickOriginal(e.target.value)}>
                <NativeSelectOption value="">Not a refund of an expense</NativeSelectOption>
                {refundOfId && !originals.some((e) => e.id === refundOfId) && (
                  <NativeSelectOption value={refundOfId}>An earlier expense</NativeSelectOption>
                )}
                {originals.map((e) => (
                  <NativeSelectOption key={e.id} value={e.id}>
                    {e.merchant || "Expense"} · {formatDay(e.date)} · {formatMoney(e.amountMinor, household.currency)}
                    {e.refundedMinor > 0 && ` (refunded ${formatMoney(e.refundedMinor, household.currency)})`}
                  </NativeSelectOption>
                ))}
              </NativeSelect>
            </Field>
            {!knownMerchant && <p className="text-xs text-muted-foreground">Pick the merchant first: the list holds its expenses.</p>}
            {original && onOpenExpense && (
              <Button type="button" variant="link" size="sm" className="h-auto self-start p-0" onClick={() => onOpenExpense(original)}>
                Open the original
              </Button>
            )}
          </div>
        )}
        {hasRefunds && expense && (
          <div className="flex flex-col gap-1 rounded-md bg-muted px-3 py-2 text-sm">
            <span>
              Refunded <span className="amount">{formatMoney(expense.refundedMinor, household.currency)}</span> · net{" "}
              <span className="amount">{formatMoney(expense.amountMinor - expense.refundedMinor, household.currency)}</span>
            </span>
            {refunds.map((r) => (
              <span key={r.id} className="flex items-center justify-between gap-2 text-xs text-muted-foreground">
                {formatDay(r.date)} · <span className="amount">{formatMoney(r.amountMinor, household.currency)}</span>
                {onOpenExpense && (
                  <Button type="button" variant="link" size="xs" className="h-auto p-0" onClick={() => onOpenExpense(r)}>
                    Open
                  </Button>
                )}
              </span>
            ))}
          </div>
        )}
        {!hasLines && (
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
        )}
        <div className="flex items-center gap-2">
          <Checkbox
            id="expense-lines"
            checked={hasLines}
            onCheckedChange={(checked) => {
              const next = checked === true;
              setHasLines(next);
              if (next && !lines[0]?.categoryId && categoryId) {
                updateLine(0, { categoryId });
              }
            }}
          />
          <Label htmlFor="expense-lines" className="font-normal">
            Split across categories
          </Label>
        </div>
        {hasLines && (
          <Field label="Category lines" name="lines" error={save.error}>
            <div className="flex flex-col gap-2 rounded-md border p-3">
              {lines.map((line, i) => (
                <div key={i} className="flex flex-col gap-1.5">
                  <div className="grid grid-cols-[1fr_7rem_auto] items-center gap-2">
                    <NativeSelect
                      aria-label={`Line ${i + 1} category`}
                      className="w-full"
                      value={line.categoryId}
                      onChange={(e) => updateLine(i, { categoryId: e.target.value })}
                    >
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
                    <Input
                      aria-label={`Line ${i + 1} amount`}
                      inputMode="decimal"
                      placeholder="0.00"
                      className="amount"
                      value={line.amount}
                      onChange={(e) => updateLine(i, { amount: e.target.value })}
                    />
                    <Button
                      type="button"
                      variant="ghost"
                      size="icon-sm"
                      aria-label={`Remove line ${i + 1}`}
                      disabled={lines.length <= 2}
                      onClick={() => removeLine(i)}
                    >
                      <X />
                    </Button>
                  </div>
                  <Input
                    aria-label={`Line ${i + 1} note`}
                    placeholder="Optional note for this line"
                    value={line.notes}
                    onChange={(e) => updateLine(i, { notes: e.target.value })}
                  />
                </div>
              ))}
              <div className="flex items-center justify-between pt-1">
                <Button type="button" variant="outline" size="sm" onClick={addLine}>
                  <Plus /> Add line
                </Button>
                <span className="amount text-xs text-muted-foreground">
                  {remainingMinor === null
                    ? `${formatMoney(linesTotalMinor, household.currency)} allocated`
                    : remainingMinor === 0
                      ? `Matches ${formatMoney(totalMinor!, household.currency)}`
                      : `${formatMoney(remainingMinor, household.currency)} left`}
                </span>
              </div>
            </div>
          </Field>
        )}
        <Field label="Account" name="accountId" error={save.error}>
          <NativeSelect className="w-full" value={accountId} onChange={(e) => changeAccount(e.target.value)}>
            <NativeSelectOption value="">None</NativeSelectOption>
            {accountOptions.map((a) => (
              <NativeSelectOption key={a.id} value={a.id}>
                {a.name}
              </NativeSelectOption>
            ))}
          </NativeSelect>
        </Field>
        {members.length > 1 && (
          <Field label="Whose" name="paidByUserId" error={save.error}>
            <NativeSelect
              className="w-full"
              value={paidByUserId}
              onChange={(e) => {
                setPaidByUserId(e.target.value);
                setOwnerPicked(true);
              }}
            >
              <NativeSelectOption value="">Joint</NativeSelectOption>
              {members.map((m) => (
                <NativeSelectOption key={m.userId} value={m.userId}>
                  {m.displayName}
                </NativeSelectOption>
              ))}
            </NativeSelect>
          </Field>
        )}
        {members.length > 1 && mismatch && (
          <div className="flex items-center gap-2">
            <Checkbox id="expense-debt" checked={createsDebt} onCheckedChange={(checked) => setCreatesDebt(checked === true)} />
            <Label htmlFor="expense-debt" className="font-normal">
              {debtLabel}
            </Label>
          </div>
        )}
        {members.length > 1 && (
          <div className="flex items-center gap-2">
            <Checkbox id="expense-split" checked={ownSplit} onCheckedChange={(checked) => setOwnSplit(checked === true)} />
            <Label htmlFor="expense-split" className="font-normal">
              Split this one differently
            </Label>
          </div>
        )}
        {ownSplit && <SplitEditor label="Shares of this expense" members={members} draft={splitDraft} onChange={setSplitDraft} />}
        {expense && expense.debts.length > 0 && (
          <p className="rounded-md bg-muted px-3 py-2 text-sm">
            {expense.debts.map((d) => `${owesLine(d, members)} ${formatMoney(d.amountMinor, household.currency)}`).join(" · ")}
          </p>
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
