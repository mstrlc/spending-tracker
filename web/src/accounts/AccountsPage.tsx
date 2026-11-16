import { useState, type FormEvent } from "react";
import { AccountIcon, Chip, PersonLabel } from "../chips";
import { useAccounts, useAssets, useDeleteAccount, useMembers, useSaveAccount } from "../api/hooks";
import type { Account, AccountInput, AccountKind } from "../api/types";
import { useHousehold } from "../households/HouseholdPages";
import { InlineRename } from "../InlineRename";
import { usePrompt } from "../dialogs";
import { EmptyState, ErrorText, Field, FormError, PageHeader, PageIntro, RowMenu, SkeletonRows, Surface } from "../ui";
import { usePendingDeleteIds, useUndoableDelete, useUndoableSave } from "../undo";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { cn } from "@/lib/utils";

const KINDS: { value: AccountKind; label: string }[] = [
  { value: "bank", label: "Current account" },
  { value: "savings", label: "Savings account" },
  { value: "credit_card", label: "Credit card" },
  { value: "cash", label: "Cash" },
  { value: "other", label: "Other" },
];

const kindLabel = (kind: AccountKind) => KINDS.find((k) => k.value === kind)?.label ?? kind;

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

  return (
    <>
      <PageHeader title="Accounts" />
      <PageIntro>
        The bank accounts, cards and cash you pay from. A credit card's purchases are spending; paying it off is a transfer.
        A savings account's interest comes in as income, with the tax the bank withheld as a deduction. Close an account
        to keep its history but hide it from new expenses.
      </PageIntro>

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
        {rows.length > 0 && (
          <Table>
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <TableHead>Name</TableHead>
                <TableHead className="max-md:hidden">Kind</TableHead>
                <TableHead className="max-md:hidden">Owner</TableHead>
                <TableHead className="w-12">
                  <span className="sr-only">Actions</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((a) => (
                <TableRow key={a.id} className={cn(a.status === "closed" && "text-muted-foreground")}>
                  <TableCell className="whitespace-normal font-medium">
                    <span className="flex items-center gap-2">
                      <AccountIcon kind={a.kind} className="size-4" />
                      <InlineRename
                        name={a.name}
                        editing={renaming === a.id}
                        onSave={(newName) => update(a, { name: newName })}
                        onDone={() => setRenaming(null)}
                      />
                      {a.status === "closed" && <Chip>Closed</Chip>}
                    </span>
                    {a.iban && <div className="amount pl-6 text-xs font-normal text-muted-foreground">{formatIban(a.iban)}</div>}
                    {(a.cards ?? []).length > 0 && (
                      <div className="pl-6 text-xs font-normal text-muted-foreground">
                        {(a.cards ?? []).length > 1 ? "Cards" : "Card"} <span className="amount">{(a.cards ?? []).map((c) => `*${c}`).join(", ")}</span>
                      </div>
                    )}
                    {a.kind === "savings" && (
                      <div className="pl-6 text-xs font-normal text-muted-foreground">
                        Interest taxed {a.interestTaxPercent ?? 15}%
                      </div>
                    )}
                    {(() => {
                      const fed = assets.find((asset) => asset.accountId === a.id);
                      return fed ? <div className="pl-6 text-xs font-normal text-muted-foreground">Feeds {fed.name}</div> : null;
                    })()}
                    <div className="pl-6 text-xs font-normal text-muted-foreground md:hidden">
                      {kindLabel(a.kind)} · {ownerName(a)}
                    </div>
                  </TableCell>
                  <TableCell className="max-md:hidden">
                    <NativeSelect
                      size="sm"
                      aria-label={`Kind of ${a.name}`}
                      value={a.kind}
                      onChange={(e) => update(a, { kind: e.target.value as AccountKind })}
                    >
                      {KINDS.map((k) => (
                        <NativeSelectOption key={k.value} value={k.value}>
                          {k.label}
                        </NativeSelectOption>
                      ))}
                    </NativeSelect>
                  </TableCell>
                  <TableCell className="text-muted-foreground max-md:hidden">
                    <PersonLabel members={members} userId={a.ownerUserId} jointLabel="Shared" />
                  </TableCell>
                  <TableCell className="text-right">
                    <RowMenu
                      label={a.name}
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
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </Surface>
    </>
  );
}
