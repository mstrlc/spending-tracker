import { useState, type FormEvent } from "react";
import { useDeleteAsset, useMembers, useSaveAsset } from "../api/hooks";
import type { Asset, AssetKind } from "../api/types";
import { useHousehold } from "../households/HouseholdPages";
import { useConfirm } from "../dialogs";
import { ErrorText, Field, FormError, SheetBody } from "../ui";
import { Button } from "@/components/ui/button";
import { FieldDescription } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { NativeSelect, NativeSelectOptGroup, NativeSelectOption } from "@/components/ui/native-select";
import { isMarketKind, KINDS } from "./holdings";
import { SecurityPicker, type PickedSecurity } from "./SecurityPicker";

// symbol errors show under the form: the picker is no single control a Field could label.
const FIELDS = ["name", "kind", "ownerUserId", "notes"];

/** The kind a security's type suggests ("ETF", "Equity", …), when it suggests one. */
function kindFor(type: string | undefined): AssetKind | null {
  const t = (type ?? "").toLowerCase();
  if (t.includes("etf") || t.includes("fund")) return "fund";
  if (t.includes("equity") || t.includes("stock")) return "stock";
  if (t.includes("crypto")) return "crypto";
  return null;
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
  const save = useSaveAsset(household.id);
  const remove = useDeleteAsset(household.id);
  const confirm = useConfirm();

  const [name, setName] = useState(asset?.name ?? "");
  const [kind, setKind] = useState<AssetKind>(asset?.kind ?? "fund");
  const [ownerUserId, setOwnerUserId] = useState(asset?.ownerUserId ?? "");
  const [notes, setNotes] = useState(asset?.notes ?? "");
  const [security, setSecurity] = useState<PickedSecurity | null>(asset?.security ?? null);

  const market = isMarketKind(kind);

  /** Only market kinds follow a security: switching away drops it. */
  function changeKind(next: AssetKind) {
    setKind(next);
    if (!isMarketKind(next)) setSecurity(null);
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
        input: { name, kind, ownerUserId: ownerUserId || null, notes, symbol: security?.symbol ?? null, version: asset?.version },
      },
      { onSuccess: onSaved },
    );
  }

  async function confirmDelete() {
    if (!asset) return;
    if (await confirm({ title: `Delete ${asset.name}?`, description: "All its contributions, values and fees go with it." })) {
      remove.mutate({ id: asset.id, version: asset.version }, { onSuccess: onDeleted });
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
            <NativeSelectOptGroup label="Market">
              {KINDS.filter((k) => k.market).map((k) => (
                <NativeSelectOption key={k.value} value={k.value}>
                  {k.label}
                </NativeSelectOption>
              ))}
            </NativeSelectOptGroup>
            <NativeSelectOptGroup label="Valued by hand">
              {KINDS.filter((k) => !k.market).map((k) => (
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
