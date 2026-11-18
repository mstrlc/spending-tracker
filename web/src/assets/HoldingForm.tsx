import { useState, type FormEvent } from "react";
import { useAccounts, useAssets, useDeleteAsset, useMembers, useSaveAsset } from "../api/hooks";
import type { Asset, AssetInput, AssetKind } from "../api/types";
import { useHousehold } from "../households/HouseholdPages";
import { useConfirm } from "../dialogs";
import { useUndoableDelete, useUndoableSave } from "../undo";
import { ErrorText, Field, FormError, SheetBody } from "../ui";
import { Button } from "@/components/ui/button";
import { FieldDescription } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { NativeSelect, NativeSelectOptGroup, NativeSelectOption } from "@/components/ui/native-select";
import { isMarketKind, isTrackableKind, KINDS } from "./holdings";
import { SecurityPicker, type PickedSecurity } from "./SecurityPicker";

// symbol errors show under the form: the picker is no single control a Field could label.
const FIELDS = ["name", "kind", "ownerUserId", "accountId", "notes"];

/** The kind a security's type suggests ("ETF", "Equity", …), when it suggests one. */
function kindFor(type: string | undefined): AssetKind | null {
  const t = (type ?? "").toLowerCase();
  if (t.includes("etf") || t.includes("fund")) return "fund";
  if (t.includes("equity") || t.includes("stock")) return "stock";
  if (t.includes("crypto")) return "crypto";
  return null;
}

function assetToInput(a: Asset): AssetInput {
  return {
    name: a.name,
    kind: a.kind,
    ownerUserId: a.ownerUserId,
    notes: a.notes,
    symbol: a.security?.symbol ?? null,
    accountId: a.accountId,
    version: a.version,
  };
}

/** Adds an asset or edits [asset]. [onSaved] gets the saved asset; [onDeleted] runs after a delete. */
export function HoldingForm({
  asset,
  onSaved,
  onDeleted,
  onCancel,
}: {
  asset?: Asset;
  onSaved: (asset: Asset) => void;
  onDeleted?: () => void;
  onCancel: () => void;
}) {
  const household = useHousehold();
  const members = useMembers(household.id).data ?? [];
  const accounts = useAccounts(household.id).data ?? [];
  const existingAssets = useAssets(household.id).data ?? [];
  const saveAsset = useSaveAsset(household.id);
  const deleteAsset = useDeleteAsset(household.id);
  const save = useUndoableSave("Asset", saveAsset, deleteAsset);
  const remove = useUndoableDelete("Asset", deleteAsset);
  const confirm = useConfirm();

  const [name, setName] = useState(asset?.name ?? "");
  const [kind, setKind] = useState<AssetKind>(asset?.kind ?? "fund");
  const [ownerUserId, setOwnerUserId] = useState(asset?.ownerUserId ?? "");
  const [accountId, setAccountId] = useState(asset?.accountId ?? "");
  const [notes, setNotes] = useState(asset?.notes ?? "");
  const [security, setSecurity] = useState<PickedSecurity | null>(asset?.security ?? null);

  const market = isMarketKind(kind);
  const trackable = isTrackableKind(kind);
  const trackedByOther = new Set(
    existingAssets.filter((a) => a.id !== asset?.id && a.accountId !== null).map((a) => a.accountId as string),
  );
  const accountOptions = accounts.filter(
    (a) => (a.status === "active" || a.id === accountId) && !trackedByOther.has(a.id),
  );

  /** Only market kinds follow a security and only trackable kinds link to an account: switching away drops them. */
  function changeKind(next: AssetKind) {
    setKind(next);
    if (!isMarketKind(next)) setSecurity(null);
    if (!isTrackableKind(next)) setAccountId("");
  }

  function pick(picked: PickedSecurity | null) {
    setSecurity(picked);
    if (!picked) return;
    if (!name.trim()) setName(picked.name);
    setKind((current) => kindFor(picked.type) ?? current);
  }

  function submit(e: FormEvent) {
    e.preventDefault();
    save.mutate(
      {
        id: asset?.id,
        input: {
          name,
          kind,
          ownerUserId: ownerUserId || null,
          notes,
          symbol: security?.symbol ?? null,
          accountId: trackable ? accountId || null : null,
          version: asset?.version,
        },
        previous: asset ? assetToInput(asset) : undefined,
      },
      { onSuccess: onSaved },
    );
  }

  async function confirmDelete() {
    if (!asset) return;
    if (await confirm({ title: `Delete ${asset.name}?`, description: "All its contributions, values and fees go with it." })) {
      remove.schedule({ id: asset.id, version: asset.version });
      onDeleted?.();
    }
  }

  return (
    <form onSubmit={submit} className="flex flex-1 flex-col" aria-label={asset ? "Edit asset" : "Add asset"}>
      <SheetBody
        footer={
          <>
            {asset && (
              <Button type="button" variant="ghost" className="text-destructive" onClick={confirmDelete} disabled={remove.isPending}>
                Delete
              </Button>
            )}
            <span className="flex-1" />
            <Button type="button" variant="outline" onClick={onCancel}>
              Cancel
            </Button>
            <Button type="submit" disabled={save.isPending}>
              {asset ? "Save" : "Add asset"}
            </Button>
          </>
        }
      >
        <Field label="Kind" name="kind" error={save.error}>
          <NativeSelect className="w-full" value={kind} onChange={(e) => changeKind(e.target.value as AssetKind)}>
            <NativeSelectOptGroup label="Owned">
              {KINDS.filter((k) => !k.liability).map((k) => (
                <NativeSelectOption key={k.value} value={k.value}>
                  {k.label}
                </NativeSelectOption>
              ))}
            </NativeSelectOptGroup>
            <NativeSelectOptGroup label="Owed">
              {KINDS.filter((k) => k.liability).map((k) => (
                <NativeSelectOption key={k.value} value={k.value}>
                  {k.label}
                </NativeSelectOption>
              ))}
            </NativeSelectOptGroup>
          </NativeSelect>
        </Field>
        {market && (
          <div className="flex flex-col gap-1.5">
            <span className="text-sm font-medium text-muted-foreground">Follows</span>
            <SecurityPicker value={security} onChange={pick} />
            <FieldDescription>
              Pick the fund or stock and record the shares you buy: it's then valued at the latest market close. Leave it
              empty to enter values by hand.
            </FieldDescription>
          </div>
        )}
        {trackable && accountOptions.length > 0 && (
          <div className="flex flex-col gap-1.5">
            <Field label="Tracked by account" name="accountId" error={save.error}>
              <NativeSelect className="w-full" value={accountId} onChange={(e) => setAccountId(e.target.value)}>
                <NativeSelectOption value="">None (enter values by hand)</NativeSelectOption>
                {accountOptions.map((a) => (
                  <NativeSelectOption key={a.id} value={a.id}>
                    {a.name}
                  </NativeSelectOption>
                ))}
              </NativeSelect>
            </Field>
            <FieldDescription>
              Reads the balance from this account's bank feeds instead of hand valuations.
            </FieldDescription>
          </div>
        )}
        <Field label="Name" name="name" error={save.error}>
          <Input
            data-autofocus
            required
            placeholder={market ? "World index fund" : "Building savings, the car…"}
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
        </Field>
        {members.length > 1 && (
          <Field label="Held by" name="ownerUserId" error={save.error}>
            <NativeSelect className="w-full" value={ownerUserId} onChange={(e) => setOwnerUserId(e.target.value)}>
              <NativeSelectOption value="">Shared</NativeSelectOption>
              {members.map((m) => (
                <NativeSelectOption key={m.userId} value={m.userId}>
                  {m.displayName}
                </NativeSelectOption>
              ))}
            </NativeSelect>
          </Field>
        )}
        <Field label="Notes" name="notes" error={save.error}>
          <Input
            value={notes}
            placeholder={market ? "Broker, ticker, strategy" : "Provider, terms"}
            onChange={(e) => setNotes(e.target.value)}
          />
        </Field>
        <FormError error={save.error} fields={FIELDS} />
        <ErrorText error={remove.error} />
      </SheetBody>
    </form>
  );
}
