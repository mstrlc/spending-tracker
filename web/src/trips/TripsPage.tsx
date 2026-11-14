import { useState, type FormEvent } from "react";
import { useDeleteTrip, useSaveTrip, useTrips } from "../api/hooks";
import type { Trip, TripInput } from "../api/types";
import { useHousehold } from "../households/HouseholdPages";
import { InlineRename } from "../InlineRename";
import { formatMoney } from "../lib/money";
import { ErrorText, Field, FormError, PageHeader, SkeletonRows } from "../ui";

export function TripsPage() {
  const household = useHousehold();
  const trips = useTrips(household.id);
  const save = useSaveTrip(household.id);
  const remove = useDeleteTrip(household.id);
  const [name, setName] = useState("");
  const [startDate, setStartDate] = useState("");
  const [endDate, setEndDate] = useState("");
  const [renaming, setRenaming] = useState<string | null>(null);

  function add(e: FormEvent) {
    e.preventDefault();
    save.mutate(
      { input: { name, startDate: startDate || null, endDate: endDate || null } },
      {
        onSuccess: () => {
          setName("");
          setStartDate("");
          setEndDate("");
        },
      },
    );
  }

  const update = (trip: Trip, changes: Partial<TripInput>) =>
    save.mutate({
      id: trip.id,
      input: { name: trip.name, startDate: trip.startDate, endDate: trip.endDate, notes: trip.notes, version: trip.version, ...changes },
    });

  return (
    <>
      <PageHeader title="Trips" />
      <p className="page-intro muted">
        Trips group what you spent away, wherever it's filed. Pick the trip on an expense to add it here.
      </p>

      <div className="add-block">
        <form onSubmit={add} className="toolbar-form" aria-label="Add trip">
          <Field label="Trip" name="name" error={save.error}>
            <input required placeholder="e.g. Slovinsko" value={name} onChange={(e) => setName(e.target.value)} />
          </Field>
          <Field label="From" name="startDate" error={save.error}>
            <input type="date" value={startDate} onChange={(e) => setStartDate(e.target.value)} />
          </Field>
          <Field label="To" name="endDate" error={save.error}>
            <input type="date" value={endDate} min={startDate || undefined} onChange={(e) => setEndDate(e.target.value)} />
          </Field>
          <button type="submit" disabled={save.isPending}>
            Add trip
          </button>
        </form>
        <FormError error={save.error} fields={["name", "startDate", "endDate"]} />
      </div>

      <section className="surface">
        <ErrorText error={remove.error} />
        <ErrorText error={trips.error} />
        {trips.isPending && <SkeletonRows rows={3} />}
        {trips.data?.length === 0 && (
          <div className="empty">
            <p className="empty-title">No trips yet</p>
            <p className="muted">Add a trip, then pick it on the expenses from it.</p>
          </div>
        )}
        {trips.data && trips.data.length > 0 && (
          <div className="table-wrap">
            <table className="data-table align-middle">
              <thead>
                <tr>
                  <th>Trip</th>
                  <th className="hide-narrow">From</th>
                  <th className="hide-narrow">To</th>
                  <th className="num">Spent</th>
                  <th className="actions-col">
                    <span className="visually-hidden">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {trips.data.map((t) => (
                  <tr key={t.id}>
                    <td className="strong">
                      <InlineRename
                        name={t.name}
                        editing={renaming === t.id}
                        onSave={(newName) => update(t, { name: newName })}
                        onDone={() => setRenaming(null)}
                      />
                      <div className="muted small">
                        {t.expenseCount === 1 ? "1 expense" : `${t.expenseCount} expenses`}
                      </div>
                    </td>
                    <td className="hide-narrow">
                      <input
                        type="date"
                        aria-label={`${t.name} starts`}
                        value={t.startDate ?? ""}
                        onChange={(e) => update(t, { startDate: e.target.value || null })}
                      />
                    </td>
                    <td className="hide-narrow">
                      <input
                        type="date"
                        aria-label={`${t.name} ends`}
                        value={t.endDate ?? ""}
                        min={t.startDate ?? undefined}
                        onChange={(e) => update(t, { endDate: e.target.value || null })}
                      />
                    </td>
                    <td className="num">{formatMoney(t.spentMinor, household.currency)}</td>
                    <td className="row-actions">
                      <button className="ghost" onClick={() => setRenaming(t.id)}>
                        Rename
                      </button>
                      <button
                        className="ghost danger"
                        onClick={() => window.confirm(`Delete ${t.name}?`) && remove.mutate({ id: t.id, version: t.version })}
                      >
                        Delete
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </>
  );
}
