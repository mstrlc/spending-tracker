import { useState, type FormEvent } from "react";
import { useAccounts, useCreateConnectionFeeds } from "../api/hooks";
import type { BankConnection, FeedFromAccount } from "../api/types";
import { useHousehold } from "../households/HouseholdPages";
import { ErrorText } from "../ui";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";

interface RowDraft {
  include: boolean;
  accountId: string;
  name: string;
  importFrom: string;
}

/**
 * Picks which bank accounts under [connection] to turn into feeds and which ledger account each reads into.
 * Used both right after returning from the bank and later from the Bank feeds panel.
 */
export function ConnectionFeedsForm({
  connection,
  onDone,
  cancelLabel = "Cancel",
}: {
  connection: BankConnection;
  onDone: () => void;
  cancelLabel?: string;
}) {
  const household = useHousehold();
  const accounts = (useAccounts(household.id).data ?? []).filter((a) => a.status === "active");
  const createFeeds = useCreateConnectionFeeds(household.id);
  const unlinked = connection.accounts.filter((a) => !a.feedId);

  const [drafts, setDrafts] = useState<Record<string, RowDraft>>(() => {
    const initial: Record<string, RowDraft> = {};
    for (const bankAcc of unlinked) {
      const matched =
        (bankAcc.iban && accounts.find((a) => a.iban?.replace(/\s+/g, "").toUpperCase() === bankAcc.iban?.replace(/\s+/g, "").toUpperCase())) ||
        accounts[0];
      initial[bankAcc.externalId] = {
        include: accounts.length > 0,
        accountId: matched?.id ?? "",
        name: bankAcc.name || matched?.name || connection.institutionName,
        importFrom: "",
      };
    }
    return initial;
  });

  function update(externalId: string, patch: Partial<RowDraft>) {
    setDrafts((prev) => ({ ...prev, [externalId]: { ...prev[externalId], ...patch } }));
  }

  function submit(e: FormEvent) {
    e.preventDefault();
    const feeds: FeedFromAccount[] = unlinked
      .filter((a) => drafts[a.externalId]?.include && drafts[a.externalId]?.accountId)
      .map((a) => {
        const d = drafts[a.externalId];
        return {
          externalId: a.externalId,
          accountId: d.accountId,
          name: d.name.trim() || null,
          importFrom: d.importFrom || null,
        };
      });
    if (feeds.length === 0) return;
    createFeeds.mutate({ connectionId: connection.id, feeds }, { onSuccess: onDone });
  }

  if (unlinked.length === 0) {
    return (
      <div className="flex flex-col gap-3">
        <p className="text-sm text-muted-foreground">Every account under {connection.institutionName} already has a feed.</p>
        <div className="flex justify-end">
          <Button type="button" onClick={onDone}>
            Done
          </Button>
        </div>
      </div>
    );
  }

  const chosenCount = unlinked.filter((a) => drafts[a.externalId]?.include && drafts[a.externalId]?.accountId).length;

  return (
    <form onSubmit={submit} className="flex flex-col gap-4">
      <p className="text-sm text-muted-foreground">
        Choose which accounts from <strong>{connection.institutionName}</strong> to read into your household, and which
        ledger account each belongs to.
      </p>
      {accounts.length === 0 && (
        <p className="text-sm text-destructive">Create an active ledger account first before adding feeds.</p>
      )}
      <ul className="flex flex-col divide-y rounded-md border">
        {unlinked.map((bankAcc) => {
          const draft = drafts[bankAcc.externalId];
          const identifier = bankAcc.iban || bankAcc.maskedPan || bankAcc.externalId;
          return (
            <li key={bankAcc.externalId} className="flex flex-col gap-2.5 p-3">
              <label className="flex items-start gap-2.5">
                <Checkbox
                  checked={draft.include}
                  onCheckedChange={(v) => update(bankAcc.externalId, { include: v === true })}
                  disabled={accounts.length === 0}
                />
                <div className="flex-1 text-sm">
                  <div className="font-medium">
                    {bankAcc.name || connection.institutionName} · {bankAcc.currency}
                  </div>
                  <div className="text-xs text-muted-foreground">
                    {identifier}
                    {bankAcc.ownerName ? ` · ${bankAcc.ownerName}` : ""}
                  </div>
                </div>
              </label>
              {draft.include && (
                <div className="grid gap-2 pl-6 sm:grid-cols-3">
                  <NativeSelect
                    aria-label={`Ledger account for ${bankAcc.name || identifier}`}
                    value={draft.accountId}
                    onChange={(e) => update(bankAcc.externalId, { accountId: e.target.value })}
                  >
                    {accounts.map((a) => (
                      <NativeSelectOption key={a.id} value={a.id}>
                        {a.name}
                      </NativeSelectOption>
                    ))}
                  </NativeSelect>
                  <Input
                    aria-label={`Feed name for ${bankAcc.name || identifier}`}
                    value={draft.name}
                    onChange={(e) => update(bankAcc.externalId, { name: e.target.value })}
                    placeholder="Feed name"
                  />
                  <Input
                    type="date"
                    aria-label={`Import from for ${bankAcc.name || identifier}`}
                    value={draft.importFrom}
                    onChange={(e) => update(bankAcc.externalId, { importFrom: e.target.value })}
                    title="Import from (optional)"
                  />
                </div>
              )}
            </li>
          );
        })}
      </ul>
      <ErrorText error={createFeeds.error} />
      <div className="flex justify-end gap-2">
        <Button type="button" variant="outline" onClick={onDone}>
          {cancelLabel}
        </Button>
        <Button type="submit" disabled={chosenCount === 0 || createFeeds.isPending}>
          {createFeeds.isPending ? "Creating…" : `Create ${chosenCount === 1 ? "feed" : `${chosenCount} feeds`}`}
        </Button>
      </div>
    </form>
  );
}
