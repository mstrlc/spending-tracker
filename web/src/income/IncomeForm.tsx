import { useRef, useState, type FormEvent } from "react";
import { PlusIcon, XIcon } from "lucide-react";
import { useAccounts, useCategories, useDeleteIncome, useIncome, useMembers, useSaveIncome } from "../api/hooks";
import type { BankRowRef, Income, IncomeInput, PayslipDeduction } from "../api/types";
import { categoryTree, incomeCategories } from "../categories/tree";
import { monthRange, todayIso } from "../lib/dates";
import { formatMoney, parseMoney, toInputValue } from "../lib/money";
import { useHousehold } from "../households/HouseholdPages";
import { useConfirm } from "../dialogs";
import { useUndoableDelete, useUndoableSave } from "../undo";
import { ErrorText, Field, FormError, SheetBody } from "../ui";
import { Button } from "@/components/ui/button";
import { SubmitButton } from "@/ui";
import { FieldDescription } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { payslipSummary, sortEarningsForPicker } from "./net";

type Kind = Income["kind"];

const FIELDS = ["kind", "date", "earnedMonth", "amountMinor", "source", "categoryId", "accountId", "recipientUserId", "notes", "deductedFromId", "deductions"];

/** What a new income line starts with, such as a bank row's suggestion. */
export type IncomeDraft = Partial<
  Pick<Income, "date" | "amountMinor" | "source" | "categoryId" | "accountId" | "recipientUserId" | "notes" | "kind" | "earnedMonth" | "deductedFromId">
> & {
  /** Deductions to fill in on a new earning, such as tax withheld from interest. */
  deductions?: { source: string; amountMinor: number }[];
  /** What the bank row says came in, when [amountMinor] is the gross before [deductions]. */
  expectedNetMinor?: number;
};

type PayslipRow = { source: string; amount: string };

function incomeToInput(i: Income): IncomeInput {
  return {
    kind: i.kind,
    date: i.date,
    amountMinor: i.amountMinor,
    source: i.source,
    categoryId: i.categoryId,
    accountId: i.accountId,
    recipientUserId: i.recipientUserId,
    earnedMonth: i.earnedMonth,
    notes: i.notes,
    deductedFromId: i.deductedFromId,
    deductions: [],
    version: i.version,
    bankRows: [],
  };
}

/**
 * Adds income or a deduction (staying open for the next line) or edits [income]. Lives in a side sheet;
 * [onSaved] reports each saved row, [onDone] closes the sheet. With [bankRows], it adds the one line those bank
 * rows become, starting from [draft], and closes once saved.
 */
export function IncomeForm({
  income,
  draft,
  bankRows,
  defaultDate,
  onEditIncome,
  onSaved,
  onDone,
}: {
  income?: Income;
  draft?: IncomeDraft;
  bankRows?: BankRowRef[];
  defaultDate?: string;
  onEditIncome?: (income: Income) => void;
  onSaved?: (income: Income) => void;
  onDone: () => void;
}) {
  const start: IncomeDraft = income ?? draft ?? {};
  const household = useHousehold();
  const accounts = useAccounts(household.id).data ?? [];
  const categories = useCategories(household.id).data ?? [];
  const members = useMembers(household.id).data ?? [];
  const saveIncome = useSaveIncome(household.id);
  const deleteIncome = useDeleteIncome(household.id);
  const save = useUndoableSave("Income", saveIncome, deleteIncome);
  const remove = useUndoableDelete("Income", deleteIncome);
  const amountRef = useRef<HTMLInputElement>(null);
  const confirm = useConfirm();

  const [kind, setKind] = useState<Kind>(start.kind ?? "earning");
  const deduction = kind === "deduction";
  const [date, setDate] = useState(start.date ?? defaultDate ?? todayIso());
  const [amount, setAmount] = useState(start.amountMinor != null ? toInputValue(start.amountMinor, household.currency) : "");
  const [source, setSource] = useState(start.source ?? "");
  const [categoryId, setCategoryId] = useState(start.categoryId ?? "");
  const [accountId, setAccountId] = useState(start.accountId ?? "");
  const [recipientUserId, setRecipientUserId] = useState(start.recipientUserId ?? "");
  const [notes, setNotes] = useState(start.notes ?? "");
  // The month it was earned; null follows the date (most income is earned the month it arrives).
  const [earnedMonth, setEarnedMonth] = useState<string | null>(
    start.earnedMonth && start.earnedMonth !== date.slice(0, 7) ? start.earnedMonth : null,
  );
  const [deductedFromId, setDeductedFromId] = useState(start.deductedFromId ?? "");
  const [payslipRows, setPayslipRows] = useState<PayslipRow[]>(
    () => start.deductions?.map((d) => ({ source: d.source, amount: toInputValue(d.amountMinor, household.currency) })) ?? [],
  );
  const [amountError, setAmountError] = useState<string | null>(null);
  const [deductionsError, setDeductionsError] = useState<string | null>(null);
  const [lastAdded, setLastAdded] = useState<string | null>(null);

  const activeMonth = earnedMonth ?? date.slice(0, 7);
  const { from, to } = monthRange(activeMonth);
  const monthIncome = useIncome(household.id, from, to).data ?? [];
  const earningOptions = sortEarningsForPicker(
    monthIncome.filter((i) => i.id !== income?.id),
    recipientUserId || null,
  );
  const linkedDeductions = income && !deduction ? monthIncome.filter((i) => i.deductedFromId === income.id) : [];

  // Closed accounts and archived categories stay selectable only for rows that already use them.
  const accountOptions = accounts.filter((a) => a.status === "active" || a.id === income?.accountId);
  const categoryOptions = categoryTree(incomeCategories(categories).filter((c) => !c.archived || c.id === categoryId));

  const expectedNetMinor = !income && !deduction ? (draft?.expectedNetMinor ?? draft?.amountMinor ?? null) : null;
  const parsedDeductionMinors = payslipRows.map((r) => parseMoney(r.amount, household.currency) ?? 0);
  const summary = payslipSummary(parseMoney(amount, household.currency), parsedDeductionMinors, expectedNetMinor);

  function submit(e: FormEvent) {
    e.preventDefault();
    const amountMinor = parseMoney(amount, household.currency);
    if (amountMinor === null || amountMinor === 0) {
      setAmountError(
        deduction
          ? "Enter what was taken off, like 3143. Use a minus sign for an overpayment refunded."
          : "Enter an amount like 45000. Use a minus sign for money paid back.",
      );
      amountRef.current?.focus();
      return;
    }
    setAmountError(null);

    let deductions: PayslipDeduction[] = [];
    if (!income && !deduction && payslipRows.length > 0) {
      const nonEmpty = payslipRows.filter((r) => r.source.trim() !== "" || r.amount.trim() !== "");
      for (const r of nonEmpty) {
        const dAmount = parseMoney(r.amount, household.currency);
        if (dAmount === null || dAmount === 0) {
          setDeductionsError("Enter a non-zero amount for each deduction.");
          return;
        }
        deductions.push({ source: r.source, amountMinor: dAmount, notes: "" });
      }
    }
    setDeductionsError(null);

    save.mutate(
      {
        id: income?.id,
        input: {
          kind,
          date,
          amountMinor,
          source,
          categoryId: deduction ? null : categoryId || null,
          accountId: accountId || null,
          recipientUserId: recipientUserId || null,
          earnedMonth: activeMonth,
          notes,
          deductedFromId: deduction ? deductedFromId || null : null,
          deductions,
          version: income?.version,
          bankRows: income ? [] : (bankRows ?? []),
        },
        previous: income ? incomeToInput(income) : undefined,
      },
      {
        onSuccess: (saved) => {
          onSaved?.(saved);
          if (income || bankRows) {
            onDone();
            return;
          }
          // Stay open for the next line, keeping date, account and recipient.
          const what = saved.source || (saved.kind === "deduction" ? "deduction" : "income");
          setLastAdded(`Added ${what} · ${formatMoney(saved.amountMinor, household.currency)}`);
          setAmount("");
          setSource("");
          setCategoryId("");
          setNotes("");
          setDeductedFromId("");
          setPayslipRows([]);
          amountRef.current?.focus();
        },
      },
    );
  }

  async function confirmDelete() {
    if (!income) return;
    const what = income.source || (income.kind === "deduction" ? "this deduction" : "this income");
    // Only this month's linked deductions are loaded; deductedMinor also counts any from other months.
    const linked = income.kind === "deduction" ? 0 : linkedDeductions.length;
    const hasLinked = income.kind !== "deduction" && (linked > 0 || income.deductedMinor !== 0);
    if (hasLinked) {
      const description =
        linked === 1 && income.deductedMinor === linkedDeductions[0].amountMinor
          ? "Its deduction goes with it."
          : "Its deductions go with it.";
      if (!(await confirm({ title: `Delete ${what}?`, description }))) return;
    }
    remove.schedule({ id: income.id, version: income.version });
    onDone();
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
              {income || bankRows ? "Cancel" : "Done"}
            </Button>
            <SubmitButton disabled={save.isPending}>
              {income ? "Save" : deduction ? "Add deduction" : "Add income"}
            </SubmitButton>
          </>
        }
      >
        <div className="flex flex-col gap-1.5">
          <Tabs value={kind} onValueChange={(v) => setKind(v as Kind)}>
            <TabsList aria-label="Kind" className="w-full">
              <TabsTrigger value="earning">Income</TabsTrigger>
              <TabsTrigger value="deduction">Deduction</TabsTrigger>
            </TabsList>
          </Tabs>
          {deduction && (
            <FieldDescription>
              Taken off income, such as health and social insurance advances or income tax. Not spending.
            </FieldDescription>
          )}
        </div>
        <div className="grid grid-cols-2 gap-3">
          <Field label={`${deduction ? "Deducted" : "Amount"} (${household.currency})`} name="amountMinor" error={save.error} hint={amountError}>
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
          <Field label={deduction ? "Paid" : "Arrived"} name="date" error={save.error}>
            <Input type="date" required className="h-11" value={date} onChange={(e) => setDate(e.target.value)} />
          </Field>
        </div>
        <div className="flex flex-col gap-1.5">
          <Field label={deduction ? "For" : "Earned in"} name="earnedMonth" error={save.error}>
            <Input
              type="month"
              required
              value={earnedMonth ?? date.slice(0, 7)}
              onChange={(e) => setEarnedMonth(e.target.value && e.target.value !== date.slice(0, 7) ? e.target.value : null)}
            />
          </Field>
          <FieldDescription>
            {deduction
              ? "The month it's taken off: October's advance paid on 8 November counts for October."
              : "The month this pay is for: October's salary paid on 2 November counts for October."}
          </FieldDescription>
        </div>
        <Field label={deduction ? "Paid to" : "Source"} name="source" error={save.error}>
          <Input
            value={source}
            placeholder={deduction ? "Health insurer, social security, tax office" : "Employer, client, who paid"}
            onChange={(e) => setSource(e.target.value)}
          />
        </Field>
        {!deduction && (
          <Field label="Category" name="categoryId" error={save.error}>
            <NativeSelect className="w-full" value={categoryId} onChange={(e) => setCategoryId(e.target.value)}>
              <NativeSelectOption value="">Uncategorized</NativeSelectOption>
              {categoryOptions.map((parent) => [
                <NativeSelectOption key={parent.id} value={parent.id}>
                  {parent.name}
                </NativeSelectOption>,
                ...parent.children.map((c) => (
                  <NativeSelectOption key={c.id} value={c.id}>
                    {parent.name} › {c.name}
                  </NativeSelectOption>
                )),
              ])}
            </NativeSelect>
          </Field>
        )}
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
          <Field label={deduction ? "Whose" : "Received by"} name="recipientUserId" error={save.error}>
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
        {deduction && (
          <Field label="Taken off" name="deductedFromId" error={save.error}>
            <NativeSelect className="w-full" value={deductedFromId} onChange={(e) => setDeductedFromId(e.target.value)}>
              <NativeSelectOption value="">None</NativeSelectOption>
              {deductedFromId && !earningOptions.some((o) => o.id === deductedFromId) && (
                <NativeSelectOption value={deductedFromId}>An earlier earning</NativeSelectOption>
              )}
              {earningOptions.map((o) => (
                <NativeSelectOption key={o.id} value={o.id}>
                  {(o.source || "Untitled income") + " · " + formatMoney(o.amountMinor, household.currency)}
                </NativeSelectOption>
              ))}
            </NativeSelect>
          </Field>
        )}
        {!income && !deduction && (
          <div className="flex flex-col gap-2">
            <div className="flex items-center justify-between">
              <span className="text-sm font-medium">Deductions</span>
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => setPayslipRows((rows) => [...rows, { source: "", amount: "" }])}
              >
                <PlusIcon /> Add deduction
              </Button>
            </div>
            {payslipRows.map((row, idx) => (
              <div key={idx} className="grid grid-cols-[1fr_7.5rem_auto] items-center gap-2">
                <Input
                  aria-label={`Deduction ${idx + 1} source`}
                  placeholder="Tax, health insurance"
                  value={row.source}
                  onChange={(e) =>
                    setPayslipRows((rows) => rows.map((r, i) => (i === idx ? { ...r, source: e.target.value } : r)))
                  }
                />
                <Input
                  aria-label={`Deduction ${idx + 1} amount`}
                  inputMode="decimal"
                  placeholder="0.00"
                  className="amount"
                  value={row.amount}
                  onChange={(e) =>
                    setPayslipRows((rows) => rows.map((r, i) => (i === idx ? { ...r, amount: e.target.value } : r)))
                  }
                />
                <Button
                  type="button"
                  variant="ghost"
                  size="icon-sm"
                  aria-label={`Remove deduction ${idx + 1}`}
                  onClick={() => setPayslipRows((rows) => rows.filter((_, i) => i !== idx))}
                >
                  <XIcon />
                </Button>
              </div>
            ))}
            {payslipRows.length > 0 && (
              <div className="text-xs text-muted-foreground">
                Gross <span className="amount">{summary.grossMinor !== null ? formatMoney(summary.grossMinor, household.currency) : "–"}</span>
                {" · "}Deducted <span className="amount">{formatMoney(summary.deductedMinor, household.currency)}</span>
                {" · "}Net <span className="amount">{summary.netMinor !== null ? formatMoney(summary.netMinor, household.currency) : "–"}</span>
              </div>
            )}
            {summary.mismatch && summary.netMinor !== null && expectedNetMinor !== null && (
              <p className="text-xs text-amber-600 dark:text-amber-400">
                Net ({formatMoney(summary.netMinor, household.currency)}) doesn&apos;t match the bank amount (
                {formatMoney(expectedNetMinor, household.currency)}).
              </p>
            )}
            {deductionsError && <p className="text-xs text-destructive">{deductionsError}</p>}
          </div>
        )}
        {income && !deduction && (income.deductedMinor !== 0 || linkedDeductions.length > 0) && (
          <div className="flex flex-col gap-1.5 rounded-md border p-3">
            <div className="text-xs font-medium text-muted-foreground">Linked deductions</div>
            <div className="text-xs text-muted-foreground">
              Gross <span className="amount">{formatMoney(income.amountMinor, household.currency)}</span>
              {" · "}Deducted <span className="amount">{formatMoney(income.deductedMinor, household.currency)}</span>
              {" · "}Net <span className="amount">{formatMoney(income.amountMinor - income.deductedMinor, household.currency)}</span>
            </div>
            {linkedDeductions.length > 0 && (
              <div className="mt-1 flex flex-col gap-1">
                {linkedDeductions.map((d) => (
                  <button
                    key={d.id}
                    type="button"
                    disabled={!onEditIncome}
                    onClick={() => onEditIncome?.(d)}
                    className="flex items-center justify-between rounded px-2 py-1 text-left text-sm hover:bg-muted disabled:pointer-events-none"
                  >
                    <span>{d.source || "Untitled deduction"}</span>
                    <span className="amount text-muted-foreground">{formatMoney(d.amountMinor, household.currency)}</span>
                  </button>
                ))}
              </div>
            )}
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
