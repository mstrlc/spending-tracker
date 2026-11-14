import { Fragment, useState, type FormEvent } from "react";
import { AccountKindIcon, PersonLabel } from "../chips";
import { useAccountBalances, useAccounts, useAccountValues, useAddAccountBalance, useDeleteAccountBalance, useMembers } from "../api/hooks";
import type { Account, AccountKind, AccountValue } from "../api/types";
import { useHousehold } from "../households/HouseholdPages";
import { formatDay, todayIso } from "../lib/dates";
import { parseMoney } from "../lib/money";
import { ErrorText, Field, FormError, Money, Sheet, SheetBody, Surface } from "../ui";
import { useUndoableDelete, usePendingDeleteIds } from "../undo";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { formatAsset } from "./holdings";

type Row = { account: Account; value: AccountValue };
type Group = { key: string; rows: Row[]; header: { userId: string | null } | { kind: AccountKind }; totalMinor: number };

/** Kinds in the order they're listed, named for a group of them. */
const KIND_GROUPS: { kind: AccountKind; label: string }[] = [
  { kind: "bank", label: "Current accounts" },
  { kind: "savings", label: "Savings accounts" },
  { kind: "cash", label: "Cash" },
  { kind: "credit_card", label: "Credit cards" },
  { kind: "other", label: "Other" },
];

/** The line under an account's name: where its balance comes from. */
export function balanceSource(value: AccountValue): string {
  if (value.source === "bank" && value.asOf) return `bank balance on ${formatDay(value.asOf)}`;
  if (value.source === "computed" && value.asOf) return `worked out from the balance on ${formatDay(value.asOf)}`;
  return "no balance yet";
}

/**
 * The household's accounts no asset follows, with what each holds today: the bank's balance, or one worked out from
 * a balance check and the transfers, income and spending recorded since. Accounts with neither can get a check.
 * Grouped like the assets above: by kind (current, savings, card, cash) or under each member and then shared,
 * each group with its total.
 */
export function AccountsSection({ groupBy = "kind" }: { groupBy?: "kind" | "person" }) {
  const household = useHousehold();
  const { currency } = household;
  const accounts = useAccounts(household.id).data ?? [];
  const values = useAccountValues(household.id);
  const members = useMembers(household.id).data ?? [];
  const [openId, setOpenId] = useState<string | null>(null);

  const rows: Row[] = (values.data ?? [])
    .filter((v) => v.followedByAssetId === null || v.followedByAssetId === undefined)
    .flatMap((value) => {
      const account = accounts.find((a) => a.id === value.accountId);
      // A closed account stays listed only while it still holds something.
      if (!account || (account.status === "closed" && !value.valueMinor)) return [];
      return [{ account, value }];
    })
    // Biggest first; accounts without a balance last, by name.
    .sort((a, b) =>
      a.value.valueMinor === null || b.value.valueMinor === null
        ? Number(a.value.valueMinor === null) - Number(b.value.valueMinor === null) || a.account.name.localeCompare(b.account.name)
        : (b.value.valueMinor ?? 0) - (a.value.valueMinor ?? 0),
    );
  const open = rows.find((r) => r.account.id === openId);
  const byPerson = groupBy === "person";
  // Per kind in a fixed order, or per member in the household's order and then the shared accounts.
  const owner = (r: Row) => (r.account.ownerUserId && members.some((m) => m.userId === r.account.ownerUserId) ? r.account.ownerUserId : null);
  const group = (key: string, own: Row[], header: { userId: string | null } | { kind: AccountKind }): Group => ({
    key,
    rows: own,
    header,
    totalMinor: own.reduce((sum, r) => sum + (r.value.valueMinor ?? 0), 0),
  });
  const groups: Group[] = (
    byPerson
      ? [...members.map((m) => m.userId), null].map((userId) => group(userId ?? "shared", rows.filter((r) => owner(r) === userId), { userId }))
      : KIND_GROUPS.map((k) => group(k.kind, rows.filter((r) => (r.account.kind ?? "other") === k.kind), { kind: k.kind }))
  ).filter((g) => g.rows.length > 0);
  const showOwnerCol = members.length > 1 && !byPerson;
  if (values.isPending || rows.length === 0) return <ErrorText error={values.error} />;

  return (
    <div className="flex flex-col gap-2">
      <h2 className="text-sm font-medium text-muted-foreground">Accounts</h2>
      <Surface>
        <Table>
          <TableHeader>
            <TableRow className="hover:bg-transparent">
              <TableHead>Account</TableHead>
              {showOwnerCol && <TableHead className="max-md:hidden">Held by</TableHead>}
              <TableHead className="text-right">Balance ({currency})</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {groups.map((group) => (
              <Fragment key={group.key}>
                <TableRow className="bg-muted/40 hover:bg-muted/40">
                  <TableCell className="py-1.5">
                    {"kind" in group.header ? (
                      <span className="flex items-center gap-2 text-sm font-medium">
                        <AccountKindIcon kind={group.header.kind} className="size-6 [&>svg]:size-3.5" />
                        {KIND_GROUPS.find((k) => "kind" in group.header && k.kind === group.header.kind)?.label}
                      </span>
                    ) : (
                      <div className="text-sm font-medium">
                        <PersonLabel members={members} userId={group.header.userId} jointLabel="Shared" />
                      </div>
                    )}
                  </TableCell>
                  {showOwnerCol && <TableCell className="py-1.5 max-md:hidden" />}
                  <TableCell className="amount py-1.5 text-right font-medium">{formatAsset(group.totalMinor, currency)}</TableCell>
                </TableRow>
            {group.rows.map(({ account, value }) => (
              <TableRow key={account.id} className="cursor-pointer" onClick={() => setOpenId(account.id)}>
                <TableCell className="whitespace-normal">
                  <div className="flex items-center gap-3">
                    <AccountKindIcon kind={account.kind} />
                    <div className="min-w-0">
                      <button
                        className="text-left font-medium outline-none hover:underline focus-visible:underline"
                        onClick={(event) => {
                          event.stopPropagation();
                          setOpenId(account.id);
                        }}
                      >
                        {account.name}
                      </button>
                      <div className="text-xs text-muted-foreground">{balanceSource(value)}</div>
                    </div>
                  </div>
                </TableCell>
                {showOwnerCol && (
                  <TableCell className="text-muted-foreground max-md:hidden">
                    <PersonLabel members={members} userId={account.ownerUserId} jointLabel="Shared" />
                  </TableCell>
                )}
                <TableCell className="amount text-right">
                  {value.valueMinor === null ? (
                    <span className="text-muted-foreground">Set balance</span>
                  ) : (
                    formatAsset(value.valueMinor, currency)
                  )}
                </TableCell>
              </TableRow>
            ))}
              </Fragment>
            ))}
          </TableBody>
        </Table>
      </Surface>

      <Sheet open={open !== undefined} onClose={() => setOpenId(null)} title={open?.account.name ?? ""}>
        {open && <AccountDetail key={open.account.id} account={open.account} value={open.value} />}
      </Sheet>
    </div>
  );
}

/** One account: its balance, how it's worked out, and its balance checks. */
function AccountDetail({ account, value }: { account: Account; value: AccountValue }) {
  const household = useHousehold();
  const { currency } = household;
  const checks = useAccountBalances(household.id, account.id);
  const pendingDeletes = usePendingDeleteIds();
  const visible = (checks.data ?? []).filter((c) => !pendingDeletes.has(c.id));
  const remove = useUndoableDelete("Balance check", useDeleteAccountBalance(household.id, account.id));
  const fromBank = value.source === "bank";

  return (
    <SheetBody>
      <dl className="flex flex-col gap-1">
        <dt className="text-sm text-muted-foreground">Balance</dt>
        <dd className="text-2xl font-medium">
          {value.valueMinor === null ? "–" : <Money currency={currency}>{formatAsset(value.valueMinor, currency)}</Money>}
        </dd>
      </dl>
      <p className="text-xs text-muted-foreground">
        {fromBank
          ? `The bank reported this balance on ${value.asOf ? formatDay(value.asOf) : "its last sync"}.`
          : value.source === "computed"
            ? "Worked out from the nearest balance check, plus transfers and income into the account and less spending and contributions paid from it since. Add a check now and then to correct for anything not recorded, like interest."
            : "The bank doesn't report this account. Enter what it holds on a day, and the transfers, income and spending recorded on it will keep it up to date."}
      </p>

      {!fromBank && (
        <>
          <BalanceCheckForm accountId={account.id} />
          <div className="flex flex-col gap-3">
            <h3 className="text-sm font-medium">Balance checks</h3>
            <ErrorText error={checks.error ?? remove.error} />
            {visible.length === 0 && !checks.isPending && <p className="text-sm text-muted-foreground">None yet.</p>}
            <ul className="-mx-2 flex flex-col">
              {visible.map((c) => (
                <li key={c.id} className="flex items-center justify-between gap-3 rounded-md px-2 py-2 text-sm">
                  <span className="min-w-0">
                    <span className="block">{formatDay(c.date)}</span>
                    {c.notes && <span className="block text-xs text-muted-foreground">{c.notes}</span>}
                  </span>
                  <span className="flex items-center gap-2">
                    <span className="amount">{formatAsset(c.amountMinor, currency)}</span>
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      className="text-destructive"
                      disabled={remove.isPending}
                      onClick={() => remove.schedule({ id: c.id, version: c.version })}
                    >
                      Delete
                    </Button>
                  </span>
                </li>
              ))}
            </ul>
          </div>
        </>
      )}
    </SheetBody>
  );
}

function BalanceCheckForm({ accountId }: { accountId: string }) {
  const household = useHousehold();
  const { currency } = household;
  const add = useAddAccountBalance(household.id, accountId);
  const [amount, setAmount] = useState("");
  const [date, setDate] = useState(todayIso());
  const [notes, setNotes] = useState("");
  const parsed = amount.trim() === "" ? null : parseMoney(amount, currency);
  const amountError = amount.trim() !== "" && parsed === null ? "Enter an amount like 12 500.50" : null;

  function submit(event: FormEvent) {
    event.preventDefault();
    if (parsed === null) return;
    add.mutate(
      { date, amountMinor: parsed, notes },
      {
        onSuccess: () => {
          setAmount("");
          setNotes("");
        },
      },
    );
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-4 rounded-lg border p-4" aria-label="Set balance">
      <h3 className="text-sm font-medium">Set balance</h3>
      <div className="grid grid-cols-2 gap-3">
        <Field label={`Balance (${currency})`} name="amountMinor" error={add.error} hint={amountError}>
          <Input
            inputMode="decimal"
            required
            placeholder="0.00"
            className="amount h-11 text-lg md:text-lg"
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
            aria-invalid={amountError !== null}
          />
        </Field>
        <Field label="At the end of" name="date" error={add.error}>
          <Input type="date" required className="h-11" value={date} onChange={(e) => setDate(e.target.value)} />
        </Field>
      </div>
      <Field label="Notes" name="notes" error={add.error}>
        <Input value={notes} onChange={(e) => setNotes(e.target.value)} />
      </Field>
      <FormError error={add.error} fields={["amountMinor", "date", "notes"]} />
      <div className="flex justify-end">
        <Button type="submit" disabled={add.isPending || parsed === null}>
          Save balance
        </Button>
      </div>
    </form>
  );
}
