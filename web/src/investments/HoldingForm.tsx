import { useState, type FormEvent } from "react";
import { useDeleteInvestment, useMembers, useSaveInvestment } from "../api/hooks";
import type { Investment, InvestmentKind } from "../api/types";
import { useHousehold } from "../households/HouseholdPages";
import { ErrorText, Field, FormError } from "../ui";
import { KINDS } from "./holdings";
import { SecurityPicker, type PickedSecurity } from "./SecurityPicker";

// symbol errors show under the form: the picker is no single control a Field could label.
const FIELDS = ["name", "kind", "ownerUserId", "notes"];

/** The kind a security's type suggests ("ETF", "Equity", …), when it suggests one. */
function kindFor(type: string | undefined): InvestmentKind | null {
  const t = (type ?? "").toLowerCase();
  if (t.includes("etf") || t.includes("fund")) return "fund";
  if (t.includes("equity") || t.includes("stock")) return "stock";
  if (t.includes("crypto")) return "crypto";
  return null;
}

/** Adds a holding or edits [investment]. [onSaved] gets the saved holding; [onDeleted] runs after a delete. */
export function HoldingForm({
  investment,
  onSaved,
  onDeleted,
  onCancel,
}: {
  investment?: Investment;
  onSaved: (investment: Investment) => void;
  onDeleted?: () => void;
  onCancel: () => void;
}) {
  const household = useHousehold();
  const members = useMembers(household.id).data ?? [];
  const save = useSaveInvestment(household.id);
  const remove = useDeleteInvestment(household.id);

  const [name, setName] = useState(investment?.name ?? "");
  const [kind, setKind] = useState<InvestmentKind>(investment?.kind ?? "fund");
  const [ownerUserId, setOwnerUserId] = useState(investment?.ownerUserId ?? "");
  const [notes, setNotes] = useState(investment?.notes ?? "");
  const [security, setSecurity] = useState<PickedSecurity | null>(investment?.security ?? null);

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
        id: investment?.id,
        input: { name, kind, ownerUserId: ownerUserId || null, notes, symbol: security?.symbol ?? null, version: investment?.version },
      },
      { onSuccess: onSaved },
    );
  }

  function confirmDelete() {
    if (!investment) return;
    if (window.confirm(`Delete ${investment.name} with all its contributions and values?`)) {
      remove.mutate({ id: investment.id, version: investment.version }, { onSuccess: onDeleted });
    }
  }

  return (
    <form onSubmit={submit} className="sheet-form" aria-label={investment ? "Edit investment" : "Add investment"}>
      <div className="sheet-fields">
        <div className="field">
          <span className="field-label">Follows</span>
          <SecurityPicker value={security} onChange={pick} />
        </div>
        <p className="muted small field-note">
          Pick the fund or stock and record the shares you buy: it's then valued at the latest market close. Leave it
          empty to enter values by hand.
        </p>
        <Field label="Name" name="name" error={save.error}>
          <input data-autofocus required placeholder="World index fund" value={name} onChange={(e) => setName(e.target.value)} />
        </Field>
        <Field label="Kind" name="kind" error={save.error}>
          <select value={kind} onChange={(e) => setKind(e.target.value as InvestmentKind)}>
            {KINDS.map((k) => (
              <option key={k.value} value={k.value}>
                {k.label}
              </option>
            ))}
          </select>
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
          <input value={notes} placeholder="Broker, ticker, strategy" onChange={(e) => setNotes(e.target.value)} />
        </Field>
        <FormError error={save.error} fields={FIELDS} />
        <ErrorText error={remove.error} />
      </div>
      <div className="sheet-footer">
        {investment && (
          <button type="button" className="ghost danger" onClick={confirmDelete} disabled={remove.isPending}>
            Delete
          </button>
        )}
        <span className="spacer" />
        <button type="button" className="secondary" onClick={onCancel}>
          Cancel
        </button>
        <button type="submit" disabled={save.isPending}>
          {investment ? "Save" : "Add investment"}
        </button>
      </div>
    </form>
  );
}
