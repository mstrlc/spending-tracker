import { useRef, useState, type ChangeEvent, type FormEvent } from "react";
import { useAccounts, useBankFeeds, useDeleteBankFeed, useSaveBankFeed, useUploadStatement } from "../api/hooks";
import type { BankFeed, BankInstitution, StatementResult } from "../api/types";
import { formatDate } from "../lib/dates";
import { useHousehold } from "../households/HouseholdPages";
import { useConfirm } from "../dialogs";
import { ErrorText, Field, FormError, RowMenu, SheetBody } from "../ui";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";

export const INSTITUTIONS: { value: BankInstitution; label: string }[] = [
  { value: "revolut", label: "Revolut" },
  { value: "csob", label: "ČSOB" },
  { value: "kb", label: "Komerční banka" },
  { value: "raiffeisen", label: "Raiffeisenbank" },
  { value: "other", label: "Other" },
];

/**
 * A statement file as text. Most banks export UTF-8; KB exports Windows-1250, which isn't valid UTF-8, so a file
 * that fails as UTF-8 is read as that.
 */
export async function readStatementFile(file: File): Promise<string> {
  const bytes = await file.arrayBuffer();
  try {
    return new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  } catch {
    return new TextDecoder("windows-1250").decode(bytes);
  }
}

function describeResult(r: StatementResult) {
  const parts = [`${r.added} new ${r.added === 1 ? "row" : "rows"}`];
  if (r.existing > 0) parts.push(`${r.existing} already here`);
  if (r.beforeImportFrom > 0) parts.push(`${r.beforeImportFrom} before the import-from date`);
  if (r.notBooked > 0) parts.push(`${r.notBooked} not settled yet`);
  return `${r.format}: ${parts.join(", ")}.`;
}

/** The household's bank feeds: what each reads into, statement uploads, and adding or editing one. */
export function FeedsPanel({ onDone }: { onDone: () => void }) {
  const household = useHousehold();
  const feeds = useBankFeeds(household.id);
  const accounts = useAccounts(household.id).data ?? [];
  const upload = useUploadStatement(household.id);
  const remove = useDeleteBankFeed(household.id);
  const confirm = useConfirm();
  const fileRef = useRef<HTMLInputElement>(null);
  const [uploadingTo, setUploadingTo] = useState<BankFeed | null>(null);
  const [result, setResult] = useState<{ feedId: string; text: string } | null>(null);
  const [editing, setEditing] = useState<BankFeed | "new" | null>(null);

  const accountName = (id: string) => accounts.find((a) => a.id === id)?.name ?? "";

  function pickFile(feed: BankFeed) {
    setUploadingTo(feed);
    setResult(null);
    upload.reset();
    fileRef.current?.click();
  }

  async function sendFile(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    e.target.value = "";
    if (!file || !uploadingTo) return;
    const feed = uploadingTo;
    const content = await readStatementFile(file);
    upload.mutate(
      { feedId: feed.id, content, fileName: file.name },
      { onSuccess: (r) => setResult({ feedId: feed.id, text: describeResult(r) }) },
    );
  }

  async function confirmDelete(feed: BankFeed) {
    if (
      await confirm({
        title: `Delete ${feed.name}?`,
        description: "Its rows go with it. A feed with decided rows can't be deleted: put them back in the inbox first.",
      })
    ) {
      remove.mutate({ id: feed.id, version: feed.version });
    }
  }

  if (editing) {
    return <FeedForm feed={editing === "new" ? undefined : editing} onDone={() => setEditing(null)} />;
  }

  return (
    <SheetBody
      footer={
        <>
          <span className="flex-1" />
          <Button type="button" variant="outline" onClick={onDone}>
            Done
          </Button>
          <Button type="button" onClick={() => setEditing("new")}>
            Add feed
          </Button>
        </>
      }
    >
      <p className="text-sm text-muted-foreground">
        A feed is what a bank gives you for one account and currency. Upload its statements (CSV from Revolut, ČSOB,
        KB or Raiffeisenbank, or GoCardless JSON) and the rows land in the inbox. Uploading overlapping statements is
        fine: rows already here are left alone.
      </p>
      <input ref={fileRef} type="file" accept=".csv,.json,text/csv,application/json" className="hidden" onChange={sendFile} />
      <ErrorText error={feeds.error} />
      <ErrorText error={remove.error} />
      {feeds.data?.length === 0 && <p className="text-sm">No feeds yet. Add one for each account and currency you have statements for.</p>}
      <ul className="flex flex-col divide-y rounded-md border">
        {feeds.data?.map((feed) => (
          <li key={feed.id} className="flex flex-col gap-2 p-3">
            <div className="flex items-start gap-2">
              <div className="flex-1">
                <div className="font-medium">{feed.name}</div>
                <div className="text-xs text-muted-foreground">
                  {accountName(feed.accountId)} · {INSTITUTIONS.find((i) => i.value === feed.institution)?.label} · {feed.currency}
                </div>
                <div className="text-xs text-muted-foreground">
                  {feed.rowCount === 0
                    ? "No rows yet"
                    : `${feed.rowCount} rows, ${feed.pendingCount} to decide${feed.lastDate ? `, up to ${formatDate(feed.lastDate)}` : ""}`}
                  {feed.importFrom && ` · from ${formatDate(feed.importFrom)}`}
                </div>
              </div>
              <Button type="button" variant="outline" size="sm" onClick={() => pickFile(feed)} disabled={upload.isPending}>
                {upload.isPending && uploadingTo?.id === feed.id ? "Reading…" : "Upload statement"}
              </Button>
              <RowMenu
                label={feed.name}
                actions={[
                  { label: "Edit", onSelect: () => setEditing(feed) },
                  { label: "Delete", destructive: true, onSelect: () => confirmDelete(feed) },
                ]}
              />
            </div>
            {uploadingTo?.id === feed.id && <ErrorText error={upload.error} />}
            {result?.feedId === feed.id && (
              <p className="rounded-md bg-muted px-3 py-2 text-sm" role="status">
                {result.text}
              </p>
            )}
          </li>
        ))}
      </ul>
    </SheetBody>
  );
}

const FIELDS = ["accountId", "name", "institution", "currency", "iban", "importFrom"];

function FeedForm({ feed, onDone }: { feed?: BankFeed; onDone: () => void }) {
  const household = useHousehold();
  const accounts = useAccounts(household.id).data ?? [];
  const save = useSaveBankFeed(household.id);
  const active = accounts.filter((a) => a.status === "active" || a.id === feed?.accountId);
  const [accountId, setAccountId] = useState(feed?.accountId ?? active[0]?.id ?? "");
  const [name, setName] = useState(feed?.name ?? "");
  const [institution, setInstitution] = useState<BankInstitution>(feed?.institution ?? "other");
  const [currency, setCurrency] = useState(feed?.currency ?? household.currency);
  const [iban, setIban] = useState(feed?.iban ?? "");
  const [importFrom, setImportFrom] = useState(feed?.importFrom ?? "");

  // A new feed is named after its account and currency until someone types a name.
  const [named, setNamed] = useState(feed !== undefined);
  const suggestedName = (account: string, cur: string) => {
    const base = accounts.find((a) => a.id === account)?.name ?? "";
    return base && cur && cur !== household.currency ? `${base} · ${cur}` : base;
  };
  const shownName = named ? name : suggestedName(accountId, currency);

  function submit(e: FormEvent) {
    e.preventDefault();
    save.mutate(
      {
        id: feed?.id,
        input: {
          accountId,
          name: shownName,
          institution,
          currency,
          iban: iban.trim() || null,
          importFrom: importFrom || null,
          version: feed?.version,
        },
      },
      { onSuccess: onDone },
    );
  }

  return (
    <form onSubmit={submit} className="flex flex-1 flex-col" aria-label={feed ? "Edit feed" : "Add feed"}>
      <SheetBody
        footer={
          <>
            <span className="flex-1" />
            <Button type="button" variant="outline" onClick={onDone}>
              Cancel
            </Button>
            <Button type="submit" disabled={save.isPending}>
              {feed ? "Save" : "Add feed"}
            </Button>
          </>
        }
      >
        <Field label="Account" name="accountId" error={save.error}>
          <NativeSelect data-autofocus className="w-full" required value={accountId} onChange={(e) => setAccountId(e.target.value)}>
            {active.map((a) => (
              <NativeSelectOption key={a.id} value={a.id}>
                {a.name}
              </NativeSelectOption>
            ))}
          </NativeSelect>
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Bank" name="institution" error={save.error}>
            <NativeSelect className="w-full" value={institution} onChange={(e) => setInstitution(e.target.value as BankInstitution)}>
              {INSTITUTIONS.map((i) => (
                <NativeSelectOption key={i.value} value={i.value}>
                  {i.label}
                </NativeSelectOption>
              ))}
            </NativeSelect>
          </Field>
          <Field label="Currency" name="currency" error={save.error}>
            <Input required maxLength={3} value={currency} onChange={(e) => setCurrency(e.target.value.toUpperCase())} />
          </Field>
        </div>
        <Field label="Name" name="name" error={save.error}>
          <Input
            required
            value={shownName}
            onChange={(e) => {
              setNamed(true);
              setName(e.target.value);
            }}
          />
        </Field>
        <Field label="IBAN" name="iban" error={save.error}>
          <Input placeholder="Optional" value={iban} onChange={(e) => setIban(e.target.value)} />
        </Field>
        <Field label="Import from" name="importFrom" error={save.error}>
          <Input type="date" value={importFrom} onChange={(e) => setImportFrom(e.target.value)} />
        </Field>
        <p className="-mt-2 text-xs text-muted-foreground">
          Rows booked earlier are left out: the expenses you already entered cover them.
        </p>
        <FormError error={save.error} fields={FIELDS} />
      </SheetBody>
    </form>
  );
}
