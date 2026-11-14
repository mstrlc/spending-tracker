import { useState, type ChangeEvent } from "react";
import { Link, Navigate } from "react-router";
import { useImport } from "../api/hooks";
import { ApiFailure } from "../api/errors";
import type { HouseholdImport, ImportCounts, ImportResult } from "../api/types";
import { useHousehold } from "../households/HouseholdPages";
import { EmptyState, ErrorText, PageHeader, PageIntro, Surface } from "../ui";
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
];

/** Where the file is wrong, e.g. "expenses[3].account", which the message alone doesn't say. */
function describeError(error: unknown) {
  if (error instanceof ApiFailure && error.field) return new Error(`${error.message} (at ${error.field})`);
  return error;
}

export function ImportPage() {
  const household = useHousehold();
  const run = useImport(household.id);
  const [file, setFile] = useState<{ name: string; data: HouseholdImport } | null>(null);
  const [readError, setReadError] = useState<string | null>(null);
  const [preview, setPreview] = useState<ImportResult | null>(null);
  const [done, setDone] = useState<ImportResult | null>(null);

  if (household.role !== "owner") return <Navigate to="../overview" replace />;

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

  function confirm() {
    if (!file) return;
    run.mutate({ file: file.data, dryRun: false }, { onSuccess: setDone });
  }

  const shown = done ?? preview;
  const nothingNew = preview !== null && ROWS.every((r) => preview.created[r.key] === 0);

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
          {!done && !nothingNew && (
            <div className="flex justify-end gap-2 border-t p-3">
              <Button onClick={confirm} disabled={run.isPending}>
                {run.isPending ? "Importing…" : "Import"}
              </Button>
            </div>
          )}
        </Surface>
      )}
    </>
  );
}
