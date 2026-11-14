import { useState } from "react";
import { useAccounts, useBankFeeds, useBankRows, useCategories, useDecideBankRows, useMembers } from "../api/hooks";
import type { BankRow, BankRowRef, DuplicateCandidate, TransferCandidate } from "../api/types";
import { formatDay } from "../lib/dates";
import { formatMoney } from "../lib/money";
import { useHousehold } from "../households/HouseholdPages";
import { useConfirm, usePrompt } from "../dialogs";
import { EmptyState, ErrorText, PageHeader, PageIntro, RowMenu, Sheet, SkeletonRows, Surface } from "../ui";
import { ExpenseForm, type ExpenseDraft } from "../expenses/ExpenseForm";
import { IncomeForm, type IncomeDraft } from "../income/IncomeForm";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { FeedsPanel } from "./FeedsPanel";
import { draftExpense, draftIncome, oppositeSides, skipReason } from "./drafts";
import { SettleForm } from "./SettleForm";
import { TransferForm } from "./TransferForm";

type Editor =
  | { mode: "closed" }
  | { mode: "feeds" }
  | { mode: "expense"; rows: BankRowRef[]; draft: ExpenseDraft }
  | { mode: "income"; rows: BankRowRef[]; draft: IncomeDraft }
  | { mode: "transfer"; row: BankRow; otherAccountId: string | null }
  | { mode: "settle"; rows: BankRow[] };

const ref = (row: BankRow): BankRowRef => ({ id: row.id, version: row.version });

/** A bank amount in its own currency, money in with a plus. */
const bankAmount = (row: Pick<BankRow, "amountMinor" | "currency">) =>
  `${row.amountMinor > 0 ? "+" : ""}${formatMoney(row.amountMinor, row.currency)}`;

export function InboxPage() {
  const household = useHousehold();
  const [tab, setTab] = useState<"pending" | "decided">("pending");
  const feeds = useBankFeeds(household.id);
  const rows = useBankRows(household.id, tab);
  const accounts = useAccounts(household.id).data ?? [];
  const members = useMembers(household.id).data ?? [];
  const categories = useCategories(household.id).data ?? [];
  const decide = useDecideBankRows(household.id);
  const prompt = usePrompt();
  const confirm = useConfirm();
  const [editor, setEditor] = useState<Editor>({ mode: "closed" });
  const [selected, setSelected] = useState<Set<string>>(new Set());

  const list = rows.data ?? [];
  const pendingCount = feeds.data?.reduce((sum, f) => sum + f.pendingCount, 0);
  const picked = list.filter((r) => selected.has(r.id));
  const accountName = (id: string) => accounts.find((a) => a.id === id)?.name ?? "";
  const ownerOf = (accountId: string) => accounts.find((a) => a.id === accountId)?.ownerUserId ?? null;
  const categoryName = (id?: string | null) => categories.find((c) => c.id === id)?.name;
  const memberName = (id?: string | null) => (id ? members.find((m) => m.userId === id)?.displayName : "Joint");

  function toggle(id: string, on: boolean) {
    setSelected((prev) => {
      const next = new Set(prev);
      if (on) next.add(id);
      else next.delete(id);
      return next;
    });
  }

  const clear = () => setSelected(new Set());
  const openExpense = (picks: BankRow[]) => setEditor({ mode: "expense", rows: picks.map(ref), draft: draftExpense(picks) });
  const openIncome = (picks: BankRow[]) =>
    setEditor({ mode: "income", rows: picks.map(ref), draft: draftIncome(picks, ownerOf(picks[0].accountId)) });

  const openTransfer = (row: BankRow) =>
    setEditor({ mode: "transfer", row, otherAccountId: row.suggestion?.transferAccountId ?? null });
  const openSettle = (picks: BankRow[]) => setEditor({ mode: "settle", rows: picks });

  /** Files [row] and [other] as the two sides of one transfer. */
  const pair = (row: BankRow, other: TransferCandidate) =>
    decide.mutate({ action: "transfer", rows: [ref(row), { id: other.rowId, version: other.version }] }, { onSuccess: clear });

  /** Files two selected rows of opposite signs as one transfer. */
  function pairSelected(sides: { out: BankRow; in: BankRow }) {
    decide.mutate({ action: "transfer", rows: [ref(sides.out), ref(sides.in)] }, { onSuccess: clear });
  }

  async function skip(picks: BankRow[]) {
    const reason = await prompt({
      title: picks.length === 1 ? `Skip ${picks[0].text || "this row"}?` : `Skip ${picks.length} rows?`,
      description: "Skipped rows aren't spending or income, such as money between your own accounts or a top-up.",
      label: "Why",
      initial: skipReason(picks),
      placeholder: "e.g. Between own accounts",
    });
    if (reason === null || !reason.trim()) return;
    decide.mutate({ action: "skip", rows: picks.map(ref), reason }, { onSuccess: clear });
  }

  function link(row: BankRow, candidate: DuplicateCandidate) {
    decide.mutate(
      candidate.record === "expense"
        ? { action: "link", rows: [ref(row)], expenseId: candidate.id }
        : { action: "link", rows: [ref(row)], incomeId: candidate.id },
    );
  }

  async function undo(row: BankRow) {
    if (
      row.outcome !== "skipped" &&
      !(await confirm({
        title: `Put ${row.text || "this row"} back in the inbox?`,
        description: `If the ${row.outcome === "income" ? "income line" : row.outcome === "transfer" ? "transfer" : row.outcome === "settlement" ? "settlement" : "expense"} was made from this row, it's deleted once none of its rows is left. One entered by hand stays.`,
        action: "Put back",
      }))
    ) {
      return;
    }
    decide.mutate({ action: "undo", rows: [ref(row)] });
  }

  const sides = oppositeSides(picked);
  const noFeeds = feeds.data?.length === 0;

  return (
    <>
      <PageHeader title="Inbox">
        <Button variant="outline" onClick={() => setEditor({ mode: "feeds" })}>
          Bank feeds
        </Button>
      </PageHeader>
      <PageIntro>
        Bank rows waiting for a decision: add each as an expense or income, file it under one you already entered, pair it
        with the other side of a transfer, or skip it. Suggestions come from what you decided before.
      </PageIntro>

      <Tabs value={tab} onValueChange={(v) => { setTab(v as "pending" | "decided"); clear(); }}>
        <TabsList>
          <TabsTrigger value="pending">To decide{pendingCount ? ` (${pendingCount})` : ""}</TabsTrigger>
          <TabsTrigger value="decided">Decided</TabsTrigger>
        </TabsList>
      </Tabs>

      {picked.length > 0 && (
        <div className="flex flex-wrap items-center gap-2 rounded-lg border bg-muted/40 px-3 py-2" role="toolbar" aria-label="Selected rows">
          <span className="text-sm">
            {picked.length} selected
            {new Set(picked.map((r) => r.currency)).size === 1 &&
              ` · ${bankAmount({ amountMinor: picked.reduce((s, r) => s + r.amountMinor, 0), currency: picked[0].currency })}`}
          </span>
          <span className="flex-1" />
          <Button size="sm" onClick={() => openExpense(picked)}>
            One expense
          </Button>
          <Button size="sm" variant="outline" onClick={() => openIncome(picked)}>
            One income line
          </Button>
          {sides && (
            <>
              <Button size="sm" variant="outline" onClick={() => pairSelected(sides)} disabled={decide.isPending}>
                Pair as transfer
              </Button>
              <Button size="sm" variant="outline" onClick={() => openSettle(picked)}>
                Settle
              </Button>
            </>
          )}
          <Button size="sm" variant="outline" onClick={() => skip(picked)}>
            Skip
          </Button>
          <Button size="sm" variant="ghost" onClick={clear}>
            Clear
          </Button>
        </div>
      )}

      <Surface>
        <ErrorText error={rows.error} className="p-4" />
        <ErrorText error={decide.error} className="p-4" />
        {rows.isPending && <SkeletonRows />}
        {rows.data && list.length === 0 && (
          noFeeds ? (
            <EmptyState title="No bank feeds yet" action={<Button onClick={() => setEditor({ mode: "feeds" })}>Add a feed</Button>}>
              Add a feed for an account, then upload its bank statements. Their rows come here to be decided.
            </EmptyState>
          ) : tab === "pending" ? (
            <EmptyState title="All decided">Nothing waits in the inbox. Upload a newer statement to bring in more rows.</EmptyState>
          ) : (
            <EmptyState title="Nothing decided yet">Rows you add, file or skip show here, where you can put them back.</EmptyState>
          )
        )}
        {list.length > 0 && (
          <Table>
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                {tab === "pending" && (
                  <TableHead className="w-8">
                    <Checkbox
                      aria-label="Select all"
                      checked={picked.length > 0 && picked.length === list.length ? true : picked.length > 0 ? "indeterminate" : false}
                      onCheckedChange={(c) => setSelected(c === true ? new Set(list.map((r) => r.id)) : new Set())}
                    />
                  </TableHead>
                )}
                <TableHead className="max-md:hidden">Day</TableHead>
                <TableHead>Bank row</TableHead>
                <TableHead className="max-lg:hidden">{tab === "pending" ? "Suggestion" : "Became"}</TableHead>
                <TableHead className="text-right">Amount</TableHead>
                <TableHead className="w-12">
                  <span className="sr-only">Actions</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {list.map((row) => {
                const s = row.suggestion;
                const isTransfer = s?.record === "transfer";
                const isExpense = s?.record !== "income";
                const best = s?.transferCandidates[0];
                const otherAccount =
                  accountName(best?.accountId ?? s?.transferAccountId ?? "") || "another account";
                return (
                  <TableRow key={row.id} data-state={selected.has(row.id) ? "selected" : undefined}>
                    {tab === "pending" && (
                      <TableCell className="align-top">
                        <Checkbox
                          aria-label={`Select ${row.text}`}
                          checked={selected.has(row.id)}
                          onCheckedChange={(c) => toggle(row.id, c === true)}
                        />
                      </TableCell>
                    )}
                    <TableCell className="align-top text-muted-foreground max-md:hidden">{formatDay(row.date)}</TableCell>
                    <TableCell className="whitespace-normal align-top">
                      <div className="font-medium">{row.text || row.counterpartyAccount || "Bank row"}</div>
                      <div className="text-xs text-muted-foreground">
                        <span className="md:hidden">{formatDay(row.date)} · </span>
                        {accountName(row.accountId)}
                        {row.message && row.message !== row.text && ` · ${row.message}`}
                      </div>
                      {row.hint && (
                        <Badge variant="secondary" className="mt-1">
                          {row.hint}
                        </Badge>
                      )}
                      {s?.duplicates.map((d) => (
                        <div key={d.id} className="mt-1.5 flex flex-wrap items-center gap-2 text-xs">
                          <span className="text-muted-foreground">
                            Looks like {d.label || (d.record === "income" ? "an income line" : "an expense")} · {formatDay(d.date)} ·{" "}
                            <span className="amount">{formatMoney(d.amountMinor, household.currency)}</span>
                          </span>
                          <Button size="xs" variant="outline" onClick={() => link(row, d)} disabled={decide.isPending}>
                            Same one
                          </Button>
                        </div>
                      ))}
                      {s?.transferCandidates.map((c) => (
                        <div key={c.rowId} className="mt-1.5 flex flex-wrap items-center gap-2 text-xs">
                          <span className="text-muted-foreground">
                            Other side: {c.text || "bank row"} · {accountName(c.accountId)} · {formatDay(c.date)} ·{" "}
                            <span className="amount">{bankAmount(c)}</span>
                          </span>
                          <Button size="xs" variant="outline" onClick={() => pair(row, c)} disabled={decide.isPending}>
                            Pair
                          </Button>
                        </div>
                      ))}
                    </TableCell>
                    <TableCell className="whitespace-normal align-top text-sm max-lg:hidden">
                      {tab === "pending" && s && (
                        <>
                          <div>
                            {isTransfer
                              ? `Transfer ${row.amountMinor < 0 ? "to" : "from"} ${otherAccount}`
                              : isExpense
                                ? s.merchant || "Expense"
                                : s.incomeSource || "Income"}
                          </div>
                          <div className="text-xs text-muted-foreground">
                            {isTransfer
                              ? "Not spending or income"
                              : isExpense
                              ? [categoryName(s.categoryId) ?? "Uncategorized", members.length > 1 && memberName(s.paidByUserId)]
                                  .filter(Boolean)
                                  .join(" · ")
                              : "Income"}
                          </div>
                        </>
                      )}
                      {tab === "decided" && (
                        <>
                          <div>
                            {row.outcome === "expense"
                              ? "Expense"
                              : row.outcome === "income"
                                ? "Income"
                                : row.outcome === "transfer"
                                  ? "Transfer"
                                  : row.outcome === "settlement"
                                    ? "Settlement"
                                    : `Skipped: ${row.skipReason}`}
                          </div>
                          {row.decidedAuto && <div className="text-xs text-muted-foreground">Filed automatically</div>}
                        </>
                      )}
                    </TableCell>
                    <TableCell className="amount text-right align-top">{bankAmount(row)}</TableCell>
                    <TableCell className="text-right align-top">
                      <div className="flex items-center justify-end gap-1">
                        {tab === "pending" && (
                          <Button
                            size="sm"
                            variant="outline"
                            className="max-sm:hidden"
                            onClick={() =>
                              isTransfer ? (best ? pair(row, best) : openTransfer(row)) : isExpense ? openExpense([row]) : openIncome([row])
                            }
                            disabled={isTransfer && decide.isPending}
                          >
                            {isTransfer ? "Transfer" : isExpense ? "Add expense" : "Add income"}
                          </Button>
                        )}
                        <RowMenu
                          label={row.text}
                          actions={
                            tab === "pending"
                              ? [
                                  { label: "Add as expense", onSelect: () => openExpense([row]) },
                                  { label: "Add as income", onSelect: () => openIncome([row]) },
                                  ...(best ? [{ label: "Pair with the other side", onSelect: () => pair(row, best) }] : []),
                                  { label: "Transfer…", onSelect: () => openTransfer(row) },
                                  { label: "Settle between members…", onSelect: () => openSettle([row]) },
                                  { label: "Skip…", onSelect: () => skip([row]) },
                                ]
                              : [{ label: "Put back in the inbox", onSelect: () => undo(row) }]
                          }
                        />
                      </div>
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        )}
      </Surface>

      <Sheet
        open={editor.mode !== "closed"}
        onClose={() => setEditor({ mode: "closed" })}
        title={
          editor.mode === "feeds"
            ? "Bank feeds"
            : editor.mode === "income"
              ? "Add income from the bank"
              : editor.mode === "transfer"
                ? "Transfer"
                : editor.mode === "settle"
                  ? "Settle between members"
                  : "Add expense from the bank"
        }
      >
        {editor.mode === "feeds" && <FeedsPanel onDone={() => setEditor({ mode: "closed" })} />}
        {editor.mode === "expense" && (
          <ExpenseForm
            key={editor.rows.map((r) => r.id).join()}
            draft={editor.draft}
            bankRows={editor.rows}
            onSaved={clear}
            onDone={() => setEditor({ mode: "closed" })}
          />
        )}
        {editor.mode === "income" && (
          <IncomeForm
            key={editor.rows.map((r) => r.id).join()}
            draft={editor.draft}
            bankRows={editor.rows}
            onSaved={clear}
            onDone={() => setEditor({ mode: "closed" })}
          />
        )}
        {editor.mode === "transfer" && (
          <TransferForm
            key={editor.row.id}
            row={editor.row}
            otherAccountId={editor.otherAccountId}
            onSaved={clear}
            onDone={() => setEditor({ mode: "closed" })}
          />
        )}
        {editor.mode === "settle" && (
          <SettleForm
            key={editor.rows.map((r) => r.id).join()}
            rows={editor.rows}
            onSaved={clear}
            onDone={() => setEditor({ mode: "closed" })}
          />
        )}
      </Sheet>
    </>
  );
}
