import { useState, type FormEvent } from "react";
import { useDeleteTrip, useSaveTrip, useTrips } from "../api/hooks";
import type { Trip, TripInput } from "../api/types";
import { useHousehold } from "../households/HouseholdPages";
import { InlineRename } from "../InlineRename";
import { formatMoney } from "../lib/money";
import { EmptyState, ErrorText, Field, FormError, PageHeader, PageIntro, RowMenu, SkeletonRows, Surface } from "../ui";
import { usePendingDeleteIds, useUndoableDelete, useUndoableSave } from "../undo";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

export function TripsPage() {
  const household = useHousehold();
  const trips = useTrips(household.id);
  const saveTrip = useSaveTrip(household.id);
  const deleteTrip = useDeleteTrip(household.id);
  const save = useUndoableSave("Trip", saveTrip, deleteTrip);
  const remove = useUndoableDelete("Trip", deleteTrip);
  const pendingDeletes = usePendingDeleteIds();
  const [name, setName] = useState("");
  const [startDate, setStartDate] = useState("");
  const [endDate, setEndDate] = useState("");
  const [renaming, setRenaming] = useState<string | null>(null);
  const rows = (trips.data ?? []).filter((t) => !pendingDeletes.has(t.id));

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

  const update = (trip: Trip, changes: Partial<TripInput>) => {
    const previous: TripInput = {
      name: trip.name,
      startDate: trip.startDate,
      endDate: trip.endDate,
      notes: trip.notes,
      version: trip.version,
    };
    save.mutate({
      id: trip.id,
      input: { ...previous, ...changes },
      previous,
    });
  };

  function deleteRow(trip: Trip) {
    remove.schedule({ id: trip.id, version: trip.version });
  }

  return (
    <>
      <PageHeader title="Trips" />
      <PageIntro>Trips group what you spent away, wherever it's filed. Pick the trip on an expense to add it here.</PageIntro>

      <Surface className="p-4">
        <form onSubmit={add} className="flex flex-wrap items-end gap-3" aria-label="Add trip">
          <Field label="Trip" name="name" error={save.error} className="w-56">
            <Input required placeholder="e.g. Slovinsko" value={name} onChange={(e) => setName(e.target.value)} />
          </Field>
          <Field label="From" name="startDate" error={save.error} className="w-40">
            <Input type="date" value={startDate} onChange={(e) => setStartDate(e.target.value)} />
          </Field>
          <Field label="To" name="endDate" error={save.error} className="w-40">
            <Input type="date" value={endDate} min={startDate || undefined} onChange={(e) => setEndDate(e.target.value)} />
          </Field>
          <Button type="submit" disabled={save.isPending}>
            Add trip
          </Button>
        </form>
        <FormError error={save.error} fields={["name", "startDate", "endDate"]} />
      </Surface>

      <Surface>
        <ErrorText error={remove.error} className="p-4" />
        <ErrorText error={trips.error} className="p-4" />
        {trips.isPending && <SkeletonRows rows={3} />}
        {trips.data && rows.length === 0 && <EmptyState title="No trips yet">Add a trip, then pick it on the expenses from it.</EmptyState>}
        {rows.length > 0 && (
          <Table>
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <TableHead>Trip</TableHead>
                <TableHead className="max-md:hidden">From</TableHead>
                <TableHead className="max-md:hidden">To</TableHead>
                <TableHead className="text-right">Spent</TableHead>
                <TableHead className="w-12">
                  <span className="sr-only">Actions</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((t) => (
                <TableRow key={t.id}>
                  <TableCell className="whitespace-normal font-medium">
                    <InlineRename
                      name={t.name}
                      editing={renaming === t.id}
                      onSave={(newName) => update(t, { name: newName })}
                      onDone={() => setRenaming(null)}
                    />
                    <div className="text-xs font-normal text-muted-foreground">
                      {t.expenseCount === 1 ? "1 expense" : `${t.expenseCount} expenses`}
                    </div>
                  </TableCell>
                  <TableCell className="max-md:hidden">
                    <Input
                      type="date"
                      className="h-8 w-40"
                      aria-label={`${t.name} starts`}
                      value={t.startDate ?? ""}
                      onChange={(e) => update(t, { startDate: e.target.value || null })}
                    />
                  </TableCell>
                  <TableCell className="max-md:hidden">
                    <Input
                      type="date"
                      className="h-8 w-40"
                      aria-label={`${t.name} ends`}
                      value={t.endDate ?? ""}
                      min={t.startDate ?? undefined}
                      onChange={(e) => update(t, { endDate: e.target.value || null })}
                    />
                  </TableCell>
                  <TableCell className="amount text-right">{formatMoney(t.spentMinor, household.currency)}</TableCell>
                  <TableCell className="text-right">
                    <RowMenu
                      label={t.name}
                      actions={[
                        { label: "Rename", onSelect: () => setRenaming(t.id) },
                        { label: "Delete", destructive: true, onSelect: () => deleteRow(t) },
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
