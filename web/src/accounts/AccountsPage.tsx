import { Fragment, useState, type FormEvent } from "react";
import { AccountKindIcon, Chip, PersonLabel } from "../chips";
import { useAccounts, useAccountValues, useAssets, useDeleteAccount, useMembers, useSaveAccount } from "../api/hooks";
import type { Account, AccountInput, AccountKind, AccountValue } from "../api/types";
import { balanceSource } from "../assets/AccountsSection";
import { formatAsset } from "../assets/holdings";
import { useHousehold } from "../households/HouseholdPages";
import { InlineRename } from "../InlineRename";
import { usePrompt } from "../dialogs";
import { EmptyState, ErrorText, Field, FormError, Money, PageHeader, PageIntro, RowMenu, SkeletonRows, Summary, Surface } from "../ui";
import { usePendingDeleteIds, useUndoableDelete, useUndoableSave } from "../undo";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { cn } from "@/lib/utils";

const KINDS: { value: AccountKind; label: string }[] = [
  { value: "bank", label: "Current account" },
  { value: "savings", label: "Savings account" },
  { value: "credit_card", label: "Credit card" },
  { value: "cash", label: "Cash" },
  { value: "other", label: "Other" },
];

const kindLabel = (kind: AccountKind) => KINDS.find((k) => k.value === kind)?.label ?? kind;

/** A group of accounts' heading, in the order groups are listed. */
const KIND_GROUPS: { kind: AccountKind; label: string }[] = [
  { kind: "bank", label: "Current accounts" },
  { kind: "savings", label: "Savings accounts" },
  { kind: "cash", label: "Cash" },
  { kind: "credit_card", label: "Credit cards" },
  { kind: "other", label: "Other" },
];

type GroupBy = "kind" | "person";
type Group = { key: string; header: { kind: AccountKind } | { userId: string | null } | "closed"; rows: Account[]; totalMinor: number };

/** "21 Nov" for the date under a balance. */
const shortDay = (iso: string) => new Date(`${iso}T00:00:00`).toLocaleDateString(undefined, { day: "numeric", month: "short" });

/** An IBAN in groups of four, as banks print it. */
const formatIban = (iban: string) => iban.replace(/(.{4})/g, "$1 ").trim();

export function AccountsPage() {
  const household = useHousehold();
  const accounts = useAccounts(household.id);
  const assets = useAssets(household.id).data ?? [];
  const members = useMembers(household.id).data ?? [];
  const saveAccount = useSaveAccount(household.id);
  const deleteAccount = useDeleteAccount(household.id);
  const save = useUndoableSave("Account", saveAccount, deleteAccount);
  const remove = useUndoableDelete("Account", deleteAccount);
  const pendingDeletes = usePendingDeleteIds();
  const [name, setName] = useState("");
  const [ownerUserId, setOwnerUserId] = useState("");
  const [kind, setKind] = useState<AccountKind>("bank");
  const [iban, setIban] = useState("");
  // Czech banks withhold 15% of interest; a savings account starts with that.
  const [interestTax, setInterestTax] = useState("15");
  const [renaming, setRenaming] = useState<string | null>(null);
  const prompt = usePrompt();
  const rows = (accounts.data ?? []).filter((a) => !pendingDeletes.has(a.id));
  const values = useAccountValues(household.id).data ?? [];
  const [groupBy, setGroupBy] = useState<GroupBy>("kind");
  const valueOf = (a: Account): AccountValue | undefined => values.find((v) => v.accountId === a.id);
  const balance = (a: Account) => valueOf(a)?.valueMinor ?? null;

  function add(e: FormEvent) {
    e.preventDefault();
    save.mutate(
      {
        input: {
          name,
          ownerUserId: ownerUserId || null,
          kind,
          iban: iban || null,
          interestTaxPercent: kind === "savings" && interestTax.trim() !== "" ? Number(interestTax) : null,
        },
      },
      {
        onSuccess: () => {
          setName("");
          setIban("");
        },
      },
    );
  }

  const update = (account: Account, changes: Partial<AccountInput>) => {
    const previous: AccountInput = {
      name: account.name,
      ownerUserId: account.ownerUserId,
      status: account.status,
      kind: account.kind,
      iban: account.iban,
      interestTaxPercent: account.interestTaxPercent ?? null,
      cards: account.cards ?? [],
      version: account.version,
    };
    save.mutate({
      id: account.id,
      input: { ...previous, ...changes },
      previous,
    });
  };

  async function editIban(a: Account) {
    const next = await prompt({
      title: `IBAN of ${a.name}`,
      label: "IBAN",
      description: "Leave it empty to remove it.",
      initial: a.iban ? formatIban(a.iban) : "",
    });
    if (next !== null) update(a, { iban: next.trim() || null });
  }

  async function editCards(a: Account) {
    const next = await prompt({
      title: `Cards of ${a.name}`,
      label: "Last four digits",
      description:
        "Separate several with spaces. A Revolut top-up names the card that paid (\"Top-Up by *3656\"), so the Inbox knows which account it came from. Leave it empty to remove them.",
      initial: (a.cards ?? []).join(" "),
    });
    if (next !== null) update(a, { cards: next.split(/[\s,]+/).filter(Boolean) });
  }

  async function editInterestTax(a: Account) {
    const next = await prompt({
      title: `Tax on interest from ${a.name}`,
      label: "Withheld, in %",
      description: "What the bank keeps of the interest before paying it out: 15% in Czechia, 0 if it pays gross.",
      initial: String(a.interestTaxPercent ?? 15),
    });
    if (next === null || next.trim() === "") return;
    update(a, { interestTaxPercent: Number(next.trim().replace("%", "")) });
  }

  function deleteRow(a: Account) {
    remove.schedule({ id: a.id, version: a.version });
  }

  const ownerName = (a: Account) => members.find((m) => m.userId === a.ownerUserId)?.displayName ?? "Shared";
  const { currency } = household;
  const showOwner = members.length > 1;

  // Open accounts by kind or by person, biggest balance first, those without one last by name; closed ones at the end.
  const sum = (list: Account[]) => list.reduce((s, a) => s + (balance(a) ?? 0), 0);
  const byBalance = (a: Account, b: Account) => {
    const va = balance(a);
    const vb = balance(b);
    if (va === null || vb === null) return Number(va === null) - Number(vb === null) || a.name.localeCompare(b.name);
    return vb - va || a.name.localeCompare(b.name);
  };
  const open = rows.filter((a) => a.status !== "closed");
  const closed = rows.filter((a) => a.status === "closed").sort(byBalance);
  const ownerOf = (a: Account) => (a.ownerUserId && members.some((m) => m.userId === a.ownerUserId) ? a.ownerUserId : null);
  const group = (key: string, header: Group["header"], list: Account[]): Group => ({ key, header, rows: list.sort(byBalance), totalMinor: sum(list) });
  const groups: Group[] = [
    ...(groupBy === "person" && showOwner
      ? [...members.map((m) => m.userId), null].map((userId) => group(userId ?? "shared", { userId }, open.filter((a) => ownerOf(a) === userId)))
      : KIND_GROUPS.map((k) => group(k.kind, { kind: k.kind }, open.filter((a) => a.kind === k.kind)))),
    group("closed", "closed", closed),
  ].filter((g) => g.rows.length > 0);
  const byKind = (kinds: AccountKind[]) => sum(open.filter((a) => kinds.includes(a.kind)));
  const cardsOwed = byKind(["credit_card"]);
  const showOwnerCol = showOwner && groupBy === "kind";
  const columns = 3 + (showOwnerCol ? 1 : 0) + 1;

  return (
    <>
      <PageHeader title="Accounts" />
      <PageIntro>
        The bank accounts, cards and cash you pay from. A credit card's purchases are spending; paying it off is a transfer.
        A savings account's interest comes in as income, with the tax the bank withheld as a deduction. Close an account
        to keep its history but hide it from new expenses.
      </PageIntro>

      {rows.length > 0 && values.length > 0 && (
        <Summary
          items={[
            { label: "In accounts", value: <Money currency={currency}>{formatAsset(sum(open), currency)}</Money> },
            byKind(["bank"]) !== 0 && { label: "Current", value: <Money currency={currency}>{formatAsset(byKind(["bank"]), currency)}</Money> },
            byKind(["savings"]) !== 0 && { label: "Savings", value: <Money currency={currency}>{formatAsset(byKind(["savings"]), currency)}</Money> },
            byKind(["cash"]) !== 0 && { label: "Cash", value: <Money currency={currency}>{formatAsset(byKind(["cash"]), currency)}</Money> },
            cardsOwed !== 0 && { label: "Owed on cards", value: <Money currency={currency}>{formatAsset(Math.abs(cardsOwed), currency)}</Money> },
          ]}
        />
      )}

      <Surface className="p-4">
        <form onSubmit={add} className="flex flex-wrap items-end gap-3" aria-label="Add account">
          <Field label="Account name" name="name" error={save.error} className="w-56">
            <Input required placeholder="e.g. Revolut" value={name} onChange={(e) => setName(e.target.value)} />
          </Field>
          <Field label="Owner" name="ownerUserId" error={save.error} className="w-40">
            <NativeSelect className="w-full" value={ownerUserId} onChange={(e) => setOwnerUserId(e.target.value)}>
              <NativeSelectOption value="">Shared</NativeSelectOption>
              {members.map((m) => (
                <NativeSelectOption key={m.userId} value={m.userId}>
                  {m.displayName}
                </NativeSelectOption>
              ))}
            </NativeSelect>
          </Field>
          <Field label="Kind" name="kind" error={save.error} className="w-40">
            <NativeSelect className="w-full" value={kind} onChange={(e) => setKind(e.target.value as AccountKind)}>
              {KINDS.map((k) => (
                <NativeSelectOption key={k.value} value={k.value}>
                  {k.label}
                </NativeSelectOption>
              ))}
            </NativeSelect>
          </Field>
          {(kind === "bank" || kind === "savings" || kind === "credit_card") && (
            <Field label="IBAN" name="iban" error={save.error} className="w-64">
              <Input placeholder="Optional" value={iban} onChange={(e) => setIban(e.target.value)} />
            </Field>
          )}
          {kind === "savings" && (
            <Field label="Tax on interest (%)" name="interestTaxPercent" error={save.error} className="w-36">
              <Input inputMode="numeric" value={interestTax} onChange={(e) => setInterestTax(e.target.value)} />
            </Field>
          )}
          <Button type="submit" disabled={save.isPending}>
            Add account
          </Button>
        </form>
        <FormError error={save.error} fields={["name", "ownerUserId", "kind", "iban", "interestTaxPercent"]} />
      </Surface>

      <Surface>
        <ErrorText error={remove.error} className="p-4" />
        <ErrorText error={accounts.error} className="p-4" />
        {accounts.isPending && <SkeletonRows rows={3} />}
        {accounts.data && rows.length === 0 && (
          <EmptyState title="No accounts yet">Add the accounts and cards you pay from to see where money goes.</EmptyState>
        )}
        {rows.length > 0 && showOwner && (
          <div className="border-b px-4 py-3">
            <Tabs value={groupBy} onValueChange={(v) => setGroupBy(v as GroupBy)}>
              <TabsList aria-label="Group accounts by">
                <TabsTrigger value="kind">By kind</TabsTrigger>
                <TabsTrigger value="person">By person</TabsTrigger>
              </TabsList>
            </Tabs>
          </div>
        )}
        {rows.length > 0 && (
          <Table>
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <TableHead>Account</TableHead>
                <TableHead className="max-md:hidden">Kind</TableHead>
                {showOwnerCol && <TableHead className="max-xl:hidden">Owner</TableHead>}
                <TableHead className="text-right">Balance ({currency})</TableHead>
                <TableHead className="w-12">
                  <span className="sr-only">Actions</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {groups.map((g) => (
                <Fragment key={g.key}>
                  {/* One cell per column, so the group's total sits under Balance. */}
                  <TableRow className="bg-muted/40 hover:bg-muted/40">
                    <TableCell className="py-1.5">
                      {g.header === "closed" ? (
                        <span className="text-sm font-medium text-muted-foreground">Closed</span>
                      ) : "kind" in g.header ? (
                        <span className="flex items-center gap-2 text-sm font-medium">
                          <AccountKindIcon kind={g.header.kind} className="size-6 [&>svg]:size-3.5" />
                          {KIND_GROUPS.find((k) => g.header !== "closed" && "kind" in g.header && k.kind === g.header.kind)?.label}
                        </span>
                      ) : (
                        <div className="text-sm font-medium">
                          <PersonLabel members={members} userId={g.header.userId} jointLabel="Shared" />
                        </div>
                      )}
                    </TableCell>
                    <TableCell className="py-1.5 max-md:hidden" />
                    {showOwnerCol && <TableCell className="py-1.5 max-xl:hidden" />}
                    <TableCell className="amount py-1.5 text-right font-medium">{g.header === "closed" ? "" : formatAsset(g.totalMinor, currency)}</TableCell>
                    <TableCell className="py-1.5" />
                  </TableRow>
                  {g.rows.map((a) => (
                    <AccountRow
                      key={a.id}
                      account={a}
                      value={valueOf(a)}
                      currency={currency}
                      showOwnerCol={showOwnerCol}
                      feeds={assets.find((asset) => asset.accountId === a.id)?.name}
                      ownerName={groupBy === "person" && showOwner && a.status !== "closed" ? null : ownerName(a)}
                      members={members}
                      renaming={renaming === a.id}
                      onRename={(newName) => update(a, { name: newName })}
                      onRenameDone={() => setRenaming(null)}
                      onKind={(next) => update(a, { kind: next })}
                      actions={[
                        { label: "Rename", onSelect: () => setRenaming(a.id) },
                        { label: a.iban ? "Edit IBAN" : "Add IBAN", onSelect: () => editIban(a) },
                        { label: (a.cards ?? []).length > 0 ? "Edit cards…" : "Add cards…", onSelect: () => editCards(a) },
                        ...(a.kind === "savings" ? [{ label: "Tax on interest…", onSelect: () => editInterestTax(a) }] : []),
                        {
                          label: a.status === "active" ? "Close account" : "Reopen account",
                          onSelect: () => update(a, { status: a.status === "active" ? "closed" : "active" }),
                        },
                        { label: "Delete", destructive: true, onSelect: () => deleteRow(a) },
                      ]}
                    />
                  ))}
                </Fragment>
              ))}
              {groups.length === 0 && (
                <TableRow>
                  <TableCell colSpan={columns} />
                </TableRow>
              )}
            </TableBody>
          </Table>
        )}
      </Surface>
    </>
  );
}

/** One account: its kind's icon and name with what identifies it, its kind, owner and balance, and its actions. */
function AccountRow({
  account: a,
  value,
  currency,
  showOwnerCol,
  feeds,
  ownerName,
  members,
  renaming,
  onRename,
  onRenameDone,
  onKind,
  actions,
}: {
  account: Account;
  value: AccountValue | undefined;
  currency: string;
  showOwnerCol: boolean;
  feeds: string | undefined;
  /** Null when the group already says whose it is. */
  ownerName: string | null;
  members: Parameters<typeof PersonLabel>[0]["members"];
  renaming: boolean;
  onRename: (name: string) => void;
  onRenameDone: () => void;
  onKind: (kind: AccountKind) => void;
  actions: Parameters<typeof RowMenu>[0]["actions"];
}) {
  const details = [
    a.iban && <span className="amount">{formatIban(a.iban)}</span>,
    (a.cards ?? []).length > 0 && (
      <span>
        {(a.cards ?? []).length > 1 ? "Cards" : "Card"} <span className="amount">{(a.cards ?? []).map((c) => `*${c}`).join(", ")}</span>
      </span>
    ),
    a.kind === "savings" && <span>Interest taxed {a.interestTaxPercent ?? 15}%</span>,
    feeds && <span>Feeds {feeds}</span>,
  ].filter(Boolean);
  return (
    <TableRow className={cn(a.status === "closed" && "text-muted-foreground")}>
      <TableCell className="whitespace-normal">
        <div className="flex items-center gap-3">
          <AccountKindIcon kind={a.kind} className={cn(a.status === "closed" && "opacity-50")} />
          <div className="min-w-0">
            <span className="flex items-center gap-2 font-medium">
              <InlineRename name={a.name} editing={renaming} onSave={onRename} onDone={onRenameDone} />
              {a.status === "closed" && <Chip>Closed</Chip>}
            </span>
            {details.length > 0 && (
              <div className="flex flex-wrap gap-x-2 text-xs text-muted-foreground">
                {details.map((d, i) => (
                  <Fragment key={i}>
                    {i > 0 && <span aria-hidden="true">·</span>}
                    {d}
                  </Fragment>
                ))}
              </div>
            )}
            {/* What the hidden columns would say: the kind on phones, the owner unless the group already names them. */}
            <div className="text-xs text-muted-foreground xl:hidden">
              <span className="md:hidden">{kindLabel(a.kind)}</span>
              {ownerName !== null && (
                <>
                  <span className="md:hidden"> · </span>
                  {ownerName}
                </>
              )}
            </div>
          </div>
        </div>
      </TableCell>
      <TableCell className="max-md:hidden">
        <NativeSelect size="sm" className="w-36" aria-label={`Kind of ${a.name}`} value={a.kind} onChange={(e) => onKind(e.target.value as AccountKind)}>
          {KINDS.map((k) => (
            <NativeSelectOption key={k.value} value={k.value}>
              {k.label}
            </NativeSelectOption>
          ))}
        </NativeSelect>
      </TableCell>
      {showOwnerCol && (
        <TableCell className="text-muted-foreground max-xl:hidden">
          <PersonLabel members={members} userId={a.ownerUserId} jointLabel="Shared" />
        </TableCell>
      )}
      <TableCell className="text-right">
        {value?.valueMinor == null ? (
          <span className="text-sm text-muted-foreground">–</span>
        ) : (
          <div className="amount">{formatAsset(value.valueMinor, currency)}</div>
        )}
        {value && value.source !== "none" && (
          <div className="text-xs whitespace-nowrap text-muted-foreground max-sm:hidden" title={balanceSource(value)}>
            {value.source === "bank" ? "Bank" : "Worked out"}
            {value.asOf && ` · ${shortDay(value.asOf)}`}
          </div>
        )}
      </TableCell>
      <TableCell className="text-right">
        <RowMenu label={a.name} actions={actions} />
      </TableCell>
    </TableRow>
  );
}
