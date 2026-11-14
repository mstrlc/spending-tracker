import { useState, type FormEvent } from "react";
import { useDeleteAsset, useMembers, useSaveAsset } from "../api/hooks";
import type { Asset, AssetKind } from "../api/types";
import { useHousehold } from "../households/HouseholdPages";
import { ErrorText, Field, FormError } from "../ui";
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

  function confirmDelete() {
    if (!asset) return;
    if (window.confirm(`Delete ${asset.name} with all its contributions and values?`)) {
      remove.mutate({ id: asset.id, version: asset.version }, { onSuccess: onDeleted });
    }
  }

  return (
    <form onSubmit={submit} className="sheet-form" aria-label={asset ? "Edit asset" : "Add asset"}>
      <div className="sheet-fields">
        <Field label="Kind" name="kind" error={save.error}>
          <select value={kind} onChange={(e) => changeKind(e.target.value as AssetKind)}>
            <optgroup label="Market">
              {KINDS.filter((k) => k.market).map((k) => (
                <option key={k.value} value={k.value}>
                  {k.label}
                </option>
              ))}
            </optgroup>
            <optgroup label="Valued by hand">
              {KINDS.filter((k) => !k.market).map((k) => (
                <option key={k.value} value={k.value}>
                  {k.label}
                </option>
              ))}
            </optgroup>
          </select>
        </Field>
        {market && (
          <>
            <div className="field">
              <span className="field-label">Follows</span>
              <SecurityPicker value={security} onChange={pick} />
            </div>
            <p className="muted small field-note">
              Pick the fund or stock and record the shares you buy: it's then valued at the latest market close. Leave
              it empty to enter values by hand.
            </p>
          </>
        )}
        <Field label="Name" name="name" error={save.error}>
          <input data-autofocus required placeholder={market ? "World index fund" : "Building savings, the car…"} value={name} onChange={(e) => setName(e.target.value)} />
        </Field>
        {members.length > 1 && (
          <Field label="Held by" name="ownerUserId" error={save.error}>
            <select value={ownerUserId} onChange={(e) => setOwnerUserId(e.target.value)}>
              <option value="">Shared</option>
              {members.map((m) => (
                <option key={m.userId} value={m.userId}>
                  {m.displayName}
                </option>
              ))}
            </select>
          </Field>
        )}
        <Field label="Notes" name="notes" error={save.error}>
          <input value={notes} placeholder={market ? "Broker, ticker, strategy" : "Provider, terms"} onChange={(e) => setNotes(e.target.value)} />
        </Field>
        <FormError error={save.error} fields={FIELDS} />
        <ErrorText error={remove.error} />
      </div>
      <div className="sheet-footer">
        {asset && (
          <button type="button" className="ghost danger" onClick={confirmDelete} disabled={remove.isPending}>
            Delete
          </button>
        )}
        <span className="spacer" />
        <button type="button" className="secondary" onClick={onCancel}>
          Cancel
        </button>
        <button type="submit" disabled={save.isPending}>
          {asset ? "Save" : "Add asset"}
        </button>
      </div>
    </form>
  );
}
