import { useState, type ChangeEvent } from "react";
import { Link, Navigate } from "react-router";
import { useImport, useImports, useUndoImport } from "../api/hooks";
import { ApiFailure } from "../api/errors";
import type { HouseholdImport, ImportCounts, ImportResult, ImportSummary, ImportUndoResult } from "../api/types";
import { useConfirm } from "../dialogs";
import { useHousehold } from "../households/HouseholdPages";
import { formatDate } from "../lib/dates";
import { EmptyState, ErrorText, PageHeader, PageIntro, RowMenu, SkeletonRows, Surface } from "../ui";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

const ROWS: { key: keyof ImportCounts; label: string }[] = [
  { key: "expenses", label: "Expenses" },
  { key: "income", label: "Income lines" },
  { key: "contributions", label: "Asset contributions" },
  { key: "members", label: "Members" },
  { key: "accounts", label: "Accounts" },
  { key: "categories", label: "Categories" },
  { key: "merchants", label: "Merchants" },
  { key: "trips", label: "Trips" },
  { key: "assets", label: "Assets" },
  { key: "bankFeeds", label: "Bank feeds" },
  { key: "bankRows", label: "Bank rows" },
];

/** Where the file is wrong, e.g. "expenses[3].account", which the message alone doesn't say. */
function describeError(error: unknown) {
  if (error instanceof ApiFailure && error.field) return new Error(`${error.message} (at ${error.field})`);
  return error;
}

function summarizeCounts(counts: ImportCounts): string {
  const parts = ROWS.filter((r) => counts[r.key] > 0).map((r) => `${counts[r.key]} ${r.label.toLowerCase()}`);
  return parts.length > 0 ? parts.join(", ") : "Nothing new";
}

function formatDateTime(iso: string): string {
  return new Date(iso).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
}

function undoDescription(preview: ImportUndoResult) {
  const toDelete = ROWS.filter((r) => preview.deleted[r.key] > 0).map((r) => `${preview.deleted[r.key]} ${r.label.toLowerCase()}`);
  const edited = ROWS.filter((r) => preview.edited[r.key] > 0).map((r) => `${preview.edited[r.key]} ${r.label.toLowerCase()}`);
  return (
    <div className="space-y-2 text-left">
      <p>{toDelete.length > 0 ? `This will delete: ${toDelete.join(", ")}.` : "No records will be deleted."}</p>
      {edited.length > 0 && (
        <p className="font-medium text-destructive">
          Edited since import (will still be deleted): {edited.join(", ")}.
        </p>
      )}
      {(preview.unlinkedBankRows > 0 || preview.unlinkedRefunds > 0) && (
        <p>
          {[
            preview.unlinkedBankRows > 0 ? `${preview.unlinkedBankRows} bank row(s) will go back to pending` : null,
            preview.unlinkedRefunds > 0 ? `${preview.unlinkedRefunds} refund(s) will be unlinked` : null,
          ]
            .filter(Boolean)
            .join("; ")}
          .
        </p>
      )}
      {preview.keptItems.length > 0 && (
        <div>
          <p className="font-medium text-foreground">Kept:</p>
          <ul className="list-disc pl-5">
            {preview.keptItems.map((item, i) => (
              <li key={i}>
                {item.name} ({item.reason})
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

export function ImportPage() {
  const household = useHousehold();
  const run = useImport(household.id);
  const imports = useImports(household.id);
  const undo = useUndoImport(household.id);
  const confirm = useConfirm();
  const [file, setFile] = useState<{ name: string; data: HouseholdImport } | null>(null);
  const [readError, setReadError] = useState<string | null>(null);
  const [preview, setPreview] = useState<ImportResult | null>(null);
  const [done, setDone] = useState<ImportResult | null>(null);

  if (household.role !== "owner") return <Navigate to="../overview" replace />;

  async function handleUndo(imp: ImportSummary) {
    const dry = await undo.mutateAsync({ importId: imp.id, dryRun: true });
    const ok = await confirm({
      title: `Undo import from ${formatDateTime(imp.createdAt)}?`,
      description: undoDescription(dry),
      action: "Undo import",
    });
    if (ok) {
      await undo.mutateAsync({ importId: imp.id, dryRun: false });
    }
  }

  async function choose(e: ChangeEvent<HTMLInputElement>) {
    const picked = e.target.files?.[0];
    setFile(null);
    setPreview(null);
    setDone(null);
    setReadError(null);
    run.reset();
    if (!picked) return;
    let data: HouseholdImport;
    try {
      data = JSON.parse(await picked.text()) as HouseholdImport;
    } catch {
      setReadError("This file isn't JSON.");
      return;
    }
    setFile({ name: picked.name, data });
    run.mutate({ file: data, dryRun: true }, { onSuccess: setPreview });
  }

  function confirmImport() {
    if (!file) return;
    run.mutate({ file: file.data, dryRun: false }, { onSuccess: setDone });
  }

  const shown = done ?? preview;
  const nothingNew = preview !== null && ROWS.every((r) => preview.created[r.key] === 0) && preview.debtsFrom === null;

  return (
    <>
      <PageHeader title="Import" />
      <PageIntro>
        Bring in data from an import file, such as another app's export converted for this one. People, accounts,
        categories and trips are matched by name, and anything missing is added. Expenses and income already imported
        are skipped, so importing the same file again is safe. You'll see what it adds before anything is saved.
      </PageIntro>

      <div className="flex flex-col gap-2">
        <Label htmlFor="import-file">Import file (JSON)</Label>
        <Input id="import-file" type="file" accept="application/json,.json" className="max-w-sm" onChange={choose} />
        {readError && (
          <p role="alert" className="text-sm text-destructive">
            {readError}
          </p>
        )}
        <ErrorText error={describeError(run.error)} />
      </div>

      {run.isPending && !shown && <p className="text-sm text-muted-foreground">Checking {file?.name}…</p>}

      {shown && (
        <Surface>
          {done ? (
            <div className="border-b p-4">
              <p className="font-medium">Imported {file?.name}</p>
              <p className="text-sm text-muted-foreground">
                See it on the <Link to="../overview" className="underline underline-offset-4">Overview</Link> or under{" "}
                <Link to="../expenses" className="underline underline-offset-4">Expenses</Link>; every row is in{" "}
                <Link to="../activity" className="underline underline-offset-4">Activity</Link>.
              </p>
            </div>
          ) : (
            <div className="border-b p-4">
              <p className="font-medium">{file?.name}: nothing is saved yet</p>
              <p className="text-sm text-muted-foreground">This is what importing it would do.</p>
            </div>
          )}
          {nothingNew && !done ? (
            <EmptyState title="Nothing new">Everything in this file is already here.</EmptyState>
          ) : (
            <Table>
              <TableHeader>
                <TableRow className="hover:bg-transparent">
                  <TableHead>&nbsp;</TableHead>
                  <TableHead className="text-right">{done ? "Added" : "To add"}</TableHead>
                  <TableHead className="text-right">Already here</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {ROWS.filter((r) => shown.created[r.key] + shown.existing[r.key] > 0).map((r) => (
                  <TableRow key={r.key}>
                    <TableCell>{r.label}</TableCell>
                    <TableCell className="amount text-right">{shown.created[r.key]}</TableCell>
                    <TableCell className="amount text-right text-muted-foreground">{shown.existing[r.key]}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
          {shown.debtsFrom && (
            <p className="border-t px-4 py-3 text-sm text-muted-foreground">
              {done
                ? `Balances now count from ${formatDate(shown.debtsFrom)}.`
                : `Balances will count from ${formatDate(shown.debtsFrom)}.`}
            </p>
          )}
          {!done && !nothingNew && (
            <div className="flex justify-end gap-2 border-t p-3">
              <Button onClick={confirmImport} disabled={run.isPending}>
                {run.isPending ? "Importing…" : "Import"}
              </Button>
            </div>
          )}
        </Surface>
      )}

      <section className="space-y-3">
        <div>
          <h2 className="text-base font-medium">Past imports</h2>
          <p className="text-sm text-muted-foreground">
            Imports made before undo support was added aren't listed here and can't be undone.
          </p>
        </div>
        <ErrorText error={imports.error ?? undo.error} />
        <Surface>
          {imports.isPending ? (
            <SkeletonRows rows={3} />
          ) : !imports.data || imports.data.length === 0 ? (
            <EmptyState title="No imports yet">Imports you run from this page will appear here.</EmptyState>
          ) : (
            <Table>
              <TableHeader>
                <TableRow className="hover:bg-transparent">
                  <TableHead>Date</TableHead>
                  <TableHead>By</TableHead>
                  <TableHead>Created</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead className="w-10">&nbsp;</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {imports.data.map((imp) => (
                  <TableRow key={imp.id}>
                    <TableCell className="whitespace-nowrap">{formatDateTime(imp.createdAt)}</TableCell>
                    <TableCell>{imp.createdByName ?? "Someone"}</TableCell>
                    <TableCell className="text-muted-foreground">{summarizeCounts(imp.counts)}</TableCell>
                    <TableCell className="whitespace-nowrap text-muted-foreground">
                      {imp.undoneAt
                        ? `Undone on ${formatDateTime(imp.undoneAt)}${imp.undoneByName ? ` by ${imp.undoneByName}` : ""}`
                        : "Imported"}
                    </TableCell>
                    <TableCell className="text-right">
                      {!imp.undoneAt && (
                        <RowMenu
                          label={`Actions for import from ${formatDateTime(imp.createdAt)}`}
                          actions={[
                            {
                              label: "Undo import…",
                              destructive: true,
                              onSelect: () => handleUndo(imp),
                            },
                          ]}
                        />
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </Surface>
      </section>
    </>
  );
}
