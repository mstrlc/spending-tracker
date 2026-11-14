import { useState, type FormEvent } from "react";
import { useAccounts, useDeleteAccount, useMembers, useSaveAccount } from "../api/hooks";
import type { Account, AccountInput, AccountKind } from "../api/types";
import { useHousehold } from "../households/HouseholdPages";
import { InlineRename } from "../InlineRename";
import { useConfirm, usePrompt } from "../dialogs";
import { EmptyState, ErrorText, Field, FormError, PageHeader, PageIntro, RowMenu, SkeletonRows, Surface } from "../ui";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { cn } from "@/lib/utils";

const KINDS: { value: AccountKind; label: string }[] = [
  { value: "bank", label: "Bank account" },
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
  const members = useMembers(household.id).data ?? [];
  const save = useSaveAccount(household.id);
  const remove = useDeleteAccount(household.id);
  const [name, setName] = useState("");
  const [ownerUserId, setOwnerUserId] = useState("");
  const [kind, setKind] = useState<AccountKind>("bank");
  const [iban, setIban] = useState("");
  const [renaming, setRenaming] = useState<string | null>(null);
  const confirm = useConfirm();
  const prompt = usePrompt();

  function add(e: FormEvent) {
    e.preventDefault();
    save.mutate(
      { input: { name, ownerUserId: ownerUserId || null, kind, iban: iban || null } },
      {
        onSuccess: () => {
          setName("");
          setIban("");
        },
      },
    );
  }

  const update = (account: Account, changes: Partial<AccountInput>) =>
    save.mutate({
      id: account.id,
      input: {
        name: account.name,
        ownerUserId: account.ownerUserId,
        status: account.status,
        kind: account.kind,
        iban: account.iban,
        version: account.version,
        ...changes,
      },
    });

  async function editIban(a: Account) {
    const next = await prompt({
      title: `IBAN of ${a.name}`,
      label: "IBAN",
      description: "Leave it empty to remove it.",
      initial: a.iban ? formatIban(a.iban) : "",
    });
    if (next !== null) update(a, { iban: next.trim() || null });
  }

  async function confirmDelete(a: Account) {
    if (await confirm({ title: `Delete ${a.name}?`, description: "Only an account nothing uses can be deleted; close it to keep its history." })) {
      remove.mutate({ id: a.id, version: a.version });
    }
  }

  const ownerName = (a: Account) => members.find((m) => m.userId === a.ownerUserId)?.displayName ?? "Shared";

  return (
    <>
      <PageHeader title="Accounts" />
      <PageIntro>
        The bank accounts, cards and cash you pay from. A credit card's purchases are spending; paying it off is a transfer.
        Close an account to keep its history but hide it from new expenses.
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
          {(kind === "bank" || kind === "credit_card") && (
            <Field label="IBAN" name="iban" error={save.error} className="w-64">
              <Input placeholder="Optional" value={iban} onChange={(e) => setIban(e.target.value)} />
            </Field>
          )}
          <Button type="submit" disabled={save.isPending}>
            Add account
          </Button>
        </form>
        <FormError error={save.error} fields={["name", "ownerUserId", "kind", "iban"]} />
      </Surface>

      <Surface>
        <ErrorText error={remove.error} className="p-4" />
        <ErrorText error={accounts.error} className="p-4" />
        {accounts.isPending && <SkeletonRows rows={3} />}
        {accounts.data?.length === 0 && (
          <EmptyState title="No accounts yet">Add the accounts and cards you pay from to see where money goes.</EmptyState>
        )}
        {accounts.data && accounts.data.length > 0 && (
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
              {accounts.data.map((a) => (
                <TableRow key={a.id} className={cn(a.status === "closed" && "text-muted-foreground")}>
                  <TableCell className="whitespace-normal font-medium">
                    <span className="flex items-center gap-2">
                      <InlineRename
                        name={a.name}
                        editing={renaming === a.id}
                        onSave={(newName) => update(a, { name: newName })}
                        onDone={() => setRenaming(null)}
                      />
                      {a.status === "closed" && <Badge variant="secondary">Closed</Badge>}
                    </span>
                    {a.iban && <div className="amount text-xs font-normal text-muted-foreground">{formatIban(a.iban)}</div>}
                    <div className="text-xs font-normal text-muted-foreground md:hidden">
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
                  <TableCell className="text-muted-foreground max-md:hidden">{ownerName(a)}</TableCell>
                  <TableCell className="text-right">
                    <RowMenu
                      label={a.name}
                      actions={[
                        { label: "Rename", onSelect: () => setRenaming(a.id) },
                        { label: a.iban ? "Edit IBAN" : "Add IBAN", onSelect: () => editIban(a) },
                        {
                          label: a.status === "active" ? "Close account" : "Reopen account",
                          onSelect: () => update(a, { status: a.status === "active" ? "closed" : "active" }),
                        },
                        { label: "Delete", destructive: true, onSelect: () => confirmDelete(a) },
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
