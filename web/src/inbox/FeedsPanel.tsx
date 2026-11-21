import { useRef, useState, type ChangeEvent, type FormEvent } from "react";
import { ConnectionStatus } from "../chips";
import {
  useAccounts,
  useBankConnections,
  useBankFeeds,
  useBankInstitutions,
  useDeleteBankConnection,
  useDeleteBankFeed,
  useFinishBankConnection,
  useSaveBankFeed,
  useStartBankConnection,
  useSyncBankConnection,
  useSyncBankFeed,
  useUploadStatement,
} from "../api/hooks";
import type { BankConnection, BankFeed, BankInstitution, FeedSyncResult, StatementResult } from "../api/types";
import { formatDate } from "../lib/dates";
import { formatMoney } from "../lib/money";
import { useHousehold } from "../households/HouseholdPages";
import { useConfirm } from "../dialogs";
import { ErrorText, Field, FormError, RowMenu, SheetBody } from "../ui";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { ConnectionFeedsForm } from "./ConnectionFeedsForm";
import { connectionWarning } from "./connections";

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

function describeSync(feeds: FeedSyncResult[]) {
  return feeds.map((f) => `${f.feedName}: ${f.message}`).join(" ");
}

const COUNTRIES = [
  { code: "CZ", label: "Czechia" },
  { code: "SK", label: "Slovakia" },
  { code: "DE", label: "Germany" },
  { code: "AT", label: "Austria" },
  { code: "PL", label: "Poland" },
  { code: "GB", label: "United Kingdom" },
  { code: "LT", label: "Lithuania" },
];

type PanelView =
  | { mode: "list" }
  | { mode: "editFeed"; feed?: BankFeed }
  | { mode: "connectBank" }
  | { mode: "mapAccounts"; connection: BankConnection };

/** The household's bank feeds: live bank connections, statement uploads, and adding or editing feeds. */
export function FeedsPanel({ onDone }: { onDone: () => void }) {
  const household = useHousehold();
  const isOwner = household.role === "owner";
  const feeds = useBankFeeds(household.id);
  const connections = useBankConnections(household.id);
  const accounts = useAccounts(household.id).data ?? [];
  const upload = useUploadStatement(household.id);
  const remove = useDeleteBankFeed(household.id);
  const startConn = useStartBankConnection(household.id);
  const finishConn = useFinishBankConnection(household.id);
  const syncConn = useSyncBankConnection(household.id);
  const syncFeed = useSyncBankFeed(household.id);
  const deleteConn = useDeleteBankConnection(household.id);
  const confirm = useConfirm();
  const fileRef = useRef<HTMLInputElement>(null);
  const [uploadingTo, setUploadingTo] = useState<BankFeed | null>(null);
  const [syncingFeedId, setSyncingFeedId] = useState<string | null>(null);
  const [syncingConnId, setSyncingConnId] = useState<string | null>(null);
  const [result, setResult] = useState<{ feedId: string; text: string } | null>(null);
  const [connResult, setConnResult] = useState<{ connId: string; text: string } | null>(null);
  const [view, setView] = useState<PanelView>({ mode: "list" });

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

  function runFeedSync(feed: BankFeed) {
    setSyncingFeedId(feed.id);
    setResult(null);
    syncFeed.mutate(feed.id, {
      onSuccess: (res) => {
        const msg = res.feeds[0]?.message ?? "Synced.";
        setResult({ feedId: feed.id, text: msg });
      },
    });
  }

  function runConnectionSync(conn: BankConnection) {
    setSyncingConnId(conn.id);
    setConnResult(null);
    syncConn.mutate(conn.id, {
      onSuccess: (res) => setConnResult({ connId: conn.id, text: describeSync(res.feeds) || "No feeds to sync." }),
    });
  }

  function reconnect(institutionId: string) {
    startConn.mutate(institutionId, {
      onSuccess: (started) => window.location.assign(started.link),
    });
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

  async function confirmDisconnect(conn: BankConnection) {
    if (
      await confirm({
        title: `Disconnect ${conn.institutionName}?`,
        description: "Your existing feeds and bank rows stay, they just stop syncing automatically.",
      })
    ) {
      deleteConn.mutate({ id: conn.id, version: conn.version });
    }
  }

  if (view.mode === "editFeed") {
    return <FeedForm feed={view.feed} onDone={() => setView({ mode: "list" })} />;
  }
  if (view.mode === "connectBank") {
    return <ConnectBankView onDone={() => setView({ mode: "list" })} />;
  }
  if (view.mode === "mapAccounts") {
    return (
      <SheetBody>
        <ConnectionFeedsForm connection={view.connection} onDone={() => setView({ mode: "list" })} />
      </SheetBody>
    );
  }

  const configured = connections.data?.configured ?? false;
  const connList = connections.data?.connections ?? [];

  return (
    <SheetBody
      footer={
        <>
          <span className="flex-1" />
          <Button type="button" variant="outline" onClick={onDone}>
            Done
          </Button>
          {configured && isOwner && (
            <Button type="button" variant="outline" onClick={() => setView({ mode: "connectBank" })}>
              Connect bank
            </Button>
          )}
          <Button type="button" onClick={() => setView({ mode: "editFeed" })}>
            Add feed
          </Button>
        </>
      }
    >
      <p className="text-sm text-muted-foreground">
        A feed is what a bank gives you for one account and currency. Connect a bank for daily syncs, or upload
        statements (CSV from Revolut, ČSOB, KB or Raiffeisenbank, or GoCardless JSON).
      </p>

      {connections.data && !configured && (
        <p className="rounded-md bg-muted px-3 py-2 text-xs text-muted-foreground">
          Live bank connections aren&apos;t set up on this server. You can still add feeds and upload statements.
        </p>
      )}

      {configured && connList.length > 0 && (
        <div className="flex flex-col gap-2">
          <div className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Connected banks</div>
          <ErrorText error={startConn.error} />
          <ErrorText error={finishConn.error} />
          <ErrorText error={deleteConn.error} />
          <ul className="flex flex-col divide-y rounded-md border">
            {connList.map((conn) => {
              const warning = connectionWarning(conn);
              const unlinkedCount = conn.accounts.filter((a) => !a.feedId).length;
              return (
                <li key={conn.id} className="flex flex-col gap-2 p-3">
                  <div className="flex items-start gap-2">
                    <div className="flex-1">
                      <div className="flex items-center gap-2 font-medium">
                        {conn.institutionName}
                        <ConnectionStatus status={conn.status} />
                      </div>
                      <div className="text-xs text-muted-foreground">
                        {conn.accounts.length} {conn.accounts.length === 1 ? "account" : "accounts"}
                        {conn.consentExpiresAt ? ` · expires ${formatDate(conn.consentExpiresAt.slice(0, 10))}` : ""}
                      </div>
                      {warning && <div className="mt-1 text-xs font-medium text-amber-700 dark:text-amber-400">{warning}</div>}
                    </div>
                    {conn.status === "pending" && isOwner && (
                      <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        onClick={() => finishConn.mutate(conn.id)}
                        disabled={finishConn.isPending}
                      >
                        Check status
                      </Button>
                    )}
                    {conn.status === "active" && (
                      <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        onClick={() => runConnectionSync(conn)}
                        disabled={syncConn.isPending}
                      >
                        {syncConn.isPending && syncingConnId === conn.id ? "Syncing…" : "Sync now"}
                      </Button>
                    )}
                    {conn.status !== "active" && conn.status !== "pending" && isOwner && (
                      <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        onClick={() => reconnect(conn.institutionId)}
                        disabled={startConn.isPending}
                      >
                        Reconnect
                      </Button>
                    )}
                    {isOwner && (
                      <RowMenu
                        label={conn.institutionName}
                        actions={[
                          ...(conn.status === "active" && unlinkedCount > 0
                            ? [{ label: `Add feeds (${unlinkedCount})`, onSelect: () => setView({ mode: "mapAccounts", connection: conn }) }]
                            : []),
                          { label: "Disconnect", destructive: true, onSelect: () => confirmDisconnect(conn) },
                        ]}
                      />
                    )}
                  </div>
                  {syncingConnId === conn.id && <ErrorText error={syncConn.error} />}
                  {connResult?.connId === conn.id && (
                    <p className="rounded-md bg-muted px-3 py-2 text-xs" role="status">
                      {connResult.text}
                    </p>
                  )}
                </li>
              );
            })}
          </ul>
        </div>
      )}

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
                {feed.balance && (
                  <div className="text-xs text-muted-foreground">
                    Balance: {formatMoney(feed.balance.amountMinor, feed.balance.currency)}
                    {feed.balance.balanceDate ? ` (${formatDate(feed.balance.balanceDate)})` : ""}
                  </div>
                )}
                {feed.connectionId && (
                  <div className="text-xs text-muted-foreground">
                    Live sync · {feed.syncsToday}/4 today
                    {feed.lastSyncedAt ? ` · last ${formatDate(feed.lastSyncedAt.slice(0, 10))}` : ""}
                  </div>
                )}
              </div>
              {feed.connectionId ? (
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => runFeedSync(feed)}
                  disabled={syncFeed.isPending || feed.syncsToday >= 4}
                >
                  {syncFeed.isPending && syncingFeedId === feed.id ? "Syncing…" : "Sync now"}
                </Button>
              ) : (
                <Button type="button" variant="outline" size="sm" onClick={() => pickFile(feed)} disabled={upload.isPending}>
                  {upload.isPending && uploadingTo?.id === feed.id ? "Reading…" : "Upload statement"}
                </Button>
              )}
              <RowMenu
                label={feed.name}
                actions={[
                  ...(feed.connectionId ? [{ label: "Upload statement", onSelect: () => pickFile(feed) }] : []),
                  { label: "Edit", onSelect: () => setView({ mode: "editFeed", feed }) },
                  { label: "Delete", destructive: true, onSelect: () => confirmDelete(feed) },
                ]}
              />
            </div>
            {uploadingTo?.id === feed.id && <ErrorText error={upload.error} />}
            {syncingFeedId === feed.id && <ErrorText error={syncFeed.error} />}
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

function ConnectBankView({ onDone }: { onDone: () => void }) {
  const household = useHousehold();
  const [country, setCountry] = useState("CZ");
  const [search, setSearch] = useState("");
  const institutions = useBankInstitutions(household.id, country);
  const start = useStartBankConnection(household.id);

  const q = search.trim().toLowerCase();
  const filtered = (institutions.data ?? []).filter((b) => !q || b.name.toLowerCase().includes(q));

  return (
    <SheetBody
      footer={
        <>
          <span className="flex-1" />
          <Button type="button" variant="outline" onClick={onDone}>
            Cancel
          </Button>
        </>
      }
    >
      <p className="text-sm text-muted-foreground">
        Pick your bank to open its login page. Once you agree to share read-only access, you&apos;ll come right back here.
      </p>
      <div className="grid grid-cols-3 gap-2">
        <NativeSelect aria-label="Country" value={country} onChange={(e) => setCountry(e.target.value)}>
          {COUNTRIES.map((c) => (
            <NativeSelectOption key={c.code} value={c.code}>
              {c.label}
            </NativeSelectOption>
          ))}
        </NativeSelect>
        <Input
          className="col-span-2"
          placeholder="Search bank…"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          data-autofocus
        />
      </div>
      <ErrorText error={institutions.error} />
      <ErrorText error={start.error} />
      {institutions.isPending && <p className="text-sm text-muted-foreground">Loading banks…</p>}
      <ul className="flex max-h-80 flex-col divide-y overflow-y-auto rounded-md border">
        {filtered.map((bank) => (
          <li key={bank.id} className="flex items-center justify-between gap-2 p-2.5">
            <div className="flex items-center gap-2.5">
              {bank.logo && <img src={bank.logo} alt="" className="size-6 rounded-sm object-contain" />}
              <div>
                <div className="text-sm font-medium">{bank.name}</div>
                <div className="text-xs text-muted-foreground">
                  Up to {bank.historyDays} days history · {bank.accessDays} days access
                </div>
              </div>
            </div>
            <Button
              type="button"
              size="sm"
              variant="outline"
              disabled={start.isPending}
              onClick={() => start.mutate(bank.id, { onSuccess: (s) => window.location.assign(s.link) })}
            >
              Connect
            </Button>
          </li>
        ))}
        {!institutions.isPending && filtered.length === 0 && (
          <li className="p-3 text-sm text-muted-foreground">No banks match.</li>
        )}
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
