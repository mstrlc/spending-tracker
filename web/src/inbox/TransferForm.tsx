import { useRef, useState, type FormEvent } from "react";
import { useAccounts, useDecideBankRows, useDeleteTransfer, useSaveTransfer } from "../api/hooks";
import type { Account, BankRow, Transfer } from "../api/types";
import { useConfirm } from "../dialogs";
import { formatDay, todayIso } from "../lib/dates";
import { formatMoney, parseMoney, toInputValue } from "../lib/money";
import { useHousehold } from "../households/HouseholdPages";
import { ErrorText, Field, FormError, SheetBody } from "../ui";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";

const ROW_FIELDS = ["otherAccountId", "notes"];
const MANUAL_FIELDS = ["amountMinor", "date", "fromAccountId", "toAccountId", "notes"];

type TransferFormProps =
  | {
      row: BankRow;
      /** The account the row's counterparty looks like, if any. */
      otherAccountId?: string | null;
      transfer?: never;
      defaultDate?: never;
      onSaved: () => void;
      onDone: () => void;
    }
  | {
      row?: never;
      otherAccountId?: never;
      transfer?: Transfer;
      defaultDate?: string;
      onSaved?: () => void;
      onDone: () => void;
    };

/**
 * Files one bank row as a transfer to or from an unread account, or records/edits a transfer by hand.
 */
export function TransferForm(props: TransferFormProps) {
  if (props.row) {
    return <BankRowTransferForm row={props.row} otherAccountId={props.otherAccountId} onSaved={props.onSaved} onDone={props.onDone} />;
  }
  return <ManualTransferForm transfer={props.transfer} defaultDate={props.defaultDate} onSaved={props.onSaved} onDone={props.onDone} />;
}

function BankRowTransferForm({
  row,
  otherAccountId,
  onSaved,
  onDone,
}: {
  row: BankRow;
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
        <FormError error={decide.error} fields={ROW_FIELDS} />
      </SheetBody>
    </form>
  );
}

function ManualTransferForm({
  transfer,
  defaultDate,
  onSaved,
  onDone,
}: {
  transfer?: Transfer;
  defaultDate?: string;
  onSaved?: () => void;
  onDone: () => void;
}) {
  const household = useHousehold();
  const accounts = useAccounts(household.id).data ?? [];
  const save = useSaveTransfer(household.id);
  const remove = useDeleteTransfer(household.id);
  const confirm = useConfirm();
  const amountRef = useRef<HTMLInputElement>(null);

  const active = accounts.filter((a) => a.status === "active");
  const withCurrent = (currentId?: string): Account[] => {
    if (!currentId || active.some((a) => a.id === currentId)) return active;
    const closed = accounts.find((a) => a.id === currentId);
    return closed ? [...active, closed] : active;
  };

  const [date, setDate] = useState(transfer?.date ?? defaultDate ?? todayIso());
  const [fromAccountId, setFromAccountId] = useState(transfer?.fromAccountId ?? active[0]?.id ?? "");
  const [toAccountId, setToAccountId] = useState(
    transfer?.toAccountId ?? active.find((a) => a.id !== (transfer?.fromAccountId ?? active[0]?.id))?.id ?? "",
  );
  const [amount, setAmount] = useState(transfer ? toInputValue(transfer.amountMinor, household.currency) : "");
  const [notes, setNotes] = useState(transfer?.notes ?? "");
  const [amountError, setAmountError] = useState<string | null>(null);

  const rowCount = transfer?.rowIds.length ?? 0;
  const fromBankRows = rowCount > 0;
  const fromLocked = rowCount >= 2 || (rowCount === 1 && transfer!.bankAccountIds.includes(transfer!.fromAccountId));
  const toLocked = rowCount >= 2 || (rowCount === 1 && transfer!.bankAccountIds.includes(transfer!.toAccountId));

  function submit(e: FormEvent) {
    e.preventDefault();
    const amountMinor = fromBankRows && transfer ? transfer.amountMinor : parseMoney(amount, household.currency);
    if (amountMinor === null || amountMinor <= 0) {
      setAmountError("Enter how much moved, like 1500.");
      amountRef.current?.focus();
      return;
    }
    setAmountError(null);
    save.mutate(
      {
        id: transfer?.id,
        input: {
          date: fromBankRows && transfer ? transfer.date : date,
          amountMinor,
          fromAccountId,
          toAccountId,
          notes,
          version: transfer?.version,
        },
      },
      {
        onSuccess: () => {
          onSaved?.();
          onDone();
        },
      },
    );
  }

  async function confirmDelete() {
    if (!transfer) return;
    const description =
      rowCount > 0
        ? "Its bank rows go back to the inbox."
        : "This transfer will be removed.";
    if (await confirm({ title: "Delete this transfer?", description })) {
      remove.mutate({ id: transfer.id, version: transfer.version }, { onSuccess: onDone });
    }
  }

  return (
    <form onSubmit={submit} className="flex flex-1 flex-col" aria-label={transfer ? "Edit transfer" : "Add transfer"}>
      <SheetBody
        footer={
          <>
            {transfer && (
              <Button type="button" variant="ghost" className="text-destructive" onClick={confirmDelete} disabled={remove.isPending}>
                Delete
              </Button>
            )}
            <span className="flex-1" />
            <Button type="button" variant="outline" onClick={onDone}>
              Cancel
            </Button>
            <Button type="submit" disabled={save.isPending || !fromAccountId || !toAccountId}>
              {transfer ? "Save" : "Add"}
            </Button>
          </>
        }
      >
        <div className="grid grid-cols-2 gap-3">
          <Field label={`Amount (${household.currency})`} name="amountMinor" error={save.error} hint={amountError}>
            <Input
              ref={amountRef}
              data-autofocus={!fromBankRows || undefined}
              inputMode="decimal"
              required
              disabled={fromBankRows}
              placeholder="0.00"
              className="amount h-11 text-lg md:text-lg"
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
            />
          </Field>
          <Field label="Date" name="date" error={save.error}>
            <Input type="date" required disabled={fromBankRows} className="h-11" value={date} onChange={(e) => setDate(e.target.value)} />
          </Field>
        </div>
        <div className="grid grid-cols-2 gap-3">
          <Field label="From" name="fromAccountId" error={save.error}>
            <NativeSelect
              className="w-full"
              required
              disabled={fromLocked}
              value={fromAccountId}
              onChange={(e) => setFromAccountId(e.target.value)}
            >
              <NativeSelectOption value="">Choose an account</NativeSelectOption>
              {withCurrent(fromAccountId).map((a) => (
                <NativeSelectOption key={a.id} value={a.id}>
                  {a.name}
                </NativeSelectOption>
              ))}
            </NativeSelect>
          </Field>
          <Field label="To" name="toAccountId" error={save.error}>
            <NativeSelect
              className="w-full"
              required
              disabled={toLocked}
              value={toAccountId}
              onChange={(e) => setToAccountId(e.target.value)}
            >
              <NativeSelectOption value="">Choose an account</NativeSelectOption>
              {withCurrent(toAccountId).map((a) => (
                <NativeSelectOption key={a.id} value={a.id}>
                  {a.name}
                </NativeSelectOption>
              ))}
            </NativeSelect>
          </Field>
        </div>
        <Field label="Notes" name="notes" error={save.error}>
          <Input
            data-autofocus={fromBankRows || undefined}
            value={notes}
            placeholder="e.g. Monthly savings"
            onChange={(e) => setNotes(e.target.value)}
          />
        </Field>
        <p className="text-xs text-muted-foreground">
          {rowCount >= 2
            ? "Date, amount and accounts come from the paired bank rows; only notes can change."
            : rowCount === 1
              ? "Date, amount and the bank row's side come from the bank row."
              : "Money between your own accounts isn't spending or income."}
        </p>
        <FormError error={save.error} fields={MANUAL_FIELDS} />
        <ErrorText error={remove.error} />
      </SheetBody>
    </form>
  );
}
