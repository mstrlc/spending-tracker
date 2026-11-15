import { useState } from "react";
import { useAccounts, useBankConnections, useBankFeeds, useBankRows, useCategories, useDecideBankRows, useInboxRules, useMembers } from "../api/hooks";
import type { BankRow, BankRowRef, DuplicateCandidate, TransferCandidate } from "../api/types";
import { formatDay, formatMonth } from "../lib/dates";
import { formatMoney } from "../lib/money";
import { useHousehold } from "../households/HouseholdPages";
import { useConfirm, usePrompt } from "../dialogs";
import { EmptyState, ErrorText, PageHeader, PageIntro, RowMenu, Sheet, SkeletonRows, Surface } from "../ui";
import { ExpenseForm, type ExpenseDraft } from "../expenses/ExpenseForm";
import { IncomeForm, type IncomeDraft } from "../income/IncomeForm";
import { Avatar, CategoryLabel, Chip, KindChip, signedAmountClass, type RecordKind } from "../chips";
import { ZapIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { Checkbox } from "@/components/ui/checkbox";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { FeedsPanel } from "./FeedsPanel";
import { connectionWarning } from "./connections";
import { loadDecision } from "./decisions";
import { draftExpense, draftIncome, oppositeSides, skipReason } from "./drafts";
import { RuleForm } from "./RuleForm";
import { draftRule, type RuleDraft } from "./ruleDrafts";
import { SettleForm } from "./SettleForm";
import { TransferForm } from "./TransferForm";

type Editor =
  | { mode: "closed" }
  | { mode: "feeds" }
  | { mode: "expense"; rows: BankRowRef[]; draft: ExpenseDraft }
  | { mode: "income"; rows: BankRowRef[]; draft: IncomeDraft }
  | { mode: "transfer"; row: BankRow; otherAccountId: string | null }
  | { mode: "settle"; rows: BankRow[] }
  | { mode: "rule"; rowId: string; draft: RuleDraft };

const ref = (row: BankRow): BankRowRef => ({ id: row.id, version: row.version });

/** A bank amount in its own currency, money in with a plus. */
const bankAmount = (row: Pick<BankRow, "amountMinor" | "currency">) =>
  `${row.amountMinor > 0 ? "+" : ""}${formatMoney(row.amountMinor, row.currency)}`;

export function InboxPage() {
  const household = useHousehold();
  const [tab, setTab] = useState<"pending" | "decided">("pending");
  // In the Decided tab: everything, or only what rules and the matcher filed.
  const [filter, setFilter] = useState<"all" | "auto">("all");
  const feeds = useBankFeeds(household.id);
  const connections = useBankConnections(household.id);
  const rows = useBankRows(household.id, tab);
  const accounts = useAccounts(household.id).data ?? [];
  const members = useMembers(household.id).data ?? [];
  const categories = useCategories(household.id).data ?? [];
  const rules = useInboxRules(household.id).data ?? [];
  const decide = useDecideBankRows(household.id);
  const prompt = usePrompt();
  const confirm = useConfirm();
  const [editor, setEditor] = useState<Editor>({ mode: "closed" });
  const [selected, setSelected] = useState<Set<string>>(new Set());

  const [ruleError, setRuleError] = useState<unknown>(null);
  const loaded = rows.data ?? [];
  const autoCount = tab === "decided" ? loaded.filter((r) => r.decidedAuto).length : 0;
  const list = tab === "decided" && filter === "auto" ? loaded.filter((r) => r.decidedAuto) : loaded;
  const pendingCount = feeds.data?.reduce((sum, f) => sum + f.pendingCount, 0);
  const picked = list.filter((r) => selected.has(r.id));
  const accountName = (id: string) => accounts.find((a) => a.id === id)?.name ?? "";
  const ownerOf = (accountId: string) => accounts.find((a) => a.id === accountId)?.ownerUserId ?? null;
  const ruleName = (id: string | null) => rules.find((r) => r.id === id)?.name;
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
  const candidateRow = (c: TransferCandidate): BankRow =>
    (rows.data ?? []).find((r) => r.id === c.rowId) ?? {
      id: c.rowId,
      feedId: "",
      accountId: c.accountId,
      date: c.date,
      bookingDate: c.date,
      amountMinor: c.amountMinor,
      currency: c.currency,
      counterpartyName: "",
      counterpartyIban: null,
      counterpartyAccount: "",
      text: c.text,
      message: "",
      code: "",
      hint: "",
      merchantKey: "",
      outcome: "pending",
      expenseId: null,
      incomeId: null,
      transferId: null,
      settlementId: null,
      skipReason: null,
      decidedByUserId: null,
      decidedAuto: false,
      ruleId: null,
      decidedAt: null,
      version: c.version,
      suggestion: null,
      outcomeLabel: null,
    };

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

  /** Puts several decided rows back at once, after saying what goes with them. */
  async function undoMany(picks: BankRow[]) {
    const skippedOnly = picks.every((r) => r.outcome === "skipped");
    if (
      !skippedOnly &&
      !(await confirm({
        title: `Put ${picks.length === 1 ? "this row" : `${picks.length} rows`} back in the inbox?`,
        description:
          "An expense, income line or transfer made from them is deleted once none of its rows is left. One you entered by hand stays.",
        action: "Put back",
      }))
    ) {
      return;
    }
    decide.mutate({ action: "undo", rows: picks.map(ref) }, { onSuccess: clear });
  }

  /** Opens the rule form filled in from the decided [row] and what it became. */
  async function makeRule(row: BankRow) {
    setRuleError(null);
    try {
      const decision = await loadDecision(household.id, row);
      setEditor({ mode: "rule", rowId: row.id, draft: draftRule(row, decision) });
    } catch (e) {
      setRuleError(e);
    }
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

      {(connections.data?.connections ?? [])
        .map((c) => ({ c, warning: connectionWarning(c) }))
        .filter((x): x is { c: typeof x.c; warning: string } => x.warning !== null)
        .map(({ c, warning }) => (
          <div
            key={c.id}
            className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-amber-500/40 bg-amber-500/10 px-3 py-2 text-sm"
            role="status"
          >
            <span>
              <strong>{c.institutionName}:</strong> {warning}
            </span>
            <Button size="sm" variant="outline" onClick={() => setEditor({ mode: "feeds" })}>
              Bank feeds
            </Button>
          </div>
        ))}

      <div className="flex flex-wrap items-center gap-3">
        <Tabs value={tab} onValueChange={(v) => { setTab(v as "pending" | "decided"); clear(); }}>
          <TabsList>
            <TabsTrigger value="pending">To decide{pendingCount ? ` (${pendingCount})` : ""}</TabsTrigger>
            <TabsTrigger value="decided">Decided</TabsTrigger>
          </TabsList>
        </Tabs>
        {tab === "decided" && (
          <Tabs value={filter} onValueChange={(v) => { setFilter(v as "all" | "auto"); clear(); }}>
            <TabsList aria-label="Which decided rows">
              <TabsTrigger value="all">All</TabsTrigger>
              <TabsTrigger value="auto">Automatic{autoCount ? ` (${autoCount})` : ""}</TabsTrigger>
            </TabsList>
          </Tabs>
        )}
      </div>

      {tab === "decided" && picked.length > 0 && (
        <div className="flex flex-wrap items-center gap-2 rounded-lg border bg-muted/40 px-3 py-2" role="toolbar" aria-label="Selected rows">
          <span className="text-sm">{picked.length} selected</span>
          <span className="flex-1" />
          <Button size="sm" onClick={() => undoMany(picked)} disabled={decide.isPending}>
            Put back in the inbox
          </Button>
          <Button size="sm" variant="ghost" onClick={clear}>
            Clear
          </Button>
        </div>
      )}

      {tab === "pending" && picked.length > 0 && (
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
        <ErrorText error={ruleError} className="p-4" />
        {rows.isPending && <SkeletonRows />}
        {rows.data && list.length === 0 && (
          noFeeds ? (
            <EmptyState title="No bank feeds yet" action={<Button onClick={() => setEditor({ mode: "feeds" })}>Add a feed</Button>}>
              Add a feed for an account, then upload its bank statements. Their rows come here to be decided.
            </EmptyState>
          ) : tab === "pending" ? (
            <EmptyState title="All decided">Nothing waits in the inbox. Upload a newer statement to bring in more rows.</EmptyState>
          ) : filter === "auto" ? (
            <EmptyState title="Nothing filed automatically">Rows that rules or the matcher file without asking show here.</EmptyState>
          ) : (
            <EmptyState title="Nothing decided yet">Rows you add, file or skip show here, where you can put them back.</EmptyState>
          )
        )}
        {list.length > 0 && (
          // Columns give way by the table's own width, which the sidebar and the side panel narrow, not the window's.
          <div className="@container">
          <Table>
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <TableHead className="w-8">
                  <Checkbox
                    aria-label="Select all"
                    checked={picked.length > 0 && picked.length === list.length ? true : picked.length > 0 ? "indeterminate" : false}
                    onCheckedChange={(c) => setSelected(c === true ? new Set(list.map((r) => r.id)) : new Set())}
                  />
                </TableHead>
                <TableHead className="@max-xl:hidden">Day</TableHead>
                <TableHead>Bank row</TableHead>
                <TableHead className="@max-3xl:hidden">{tab === "pending" ? "Suggestion" : "Became"}</TableHead>
                <TableHead className="text-right @max-sm:hidden">Amount</TableHead>
                <TableHead className="w-12">
                  <span className="sr-only">Actions</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {list.map((row) => {
                const s = row.suggestion;
                const isSkip = s?.skipReason != null;
                const isSettlement = s?.record === "settlement" && !isSkip;
                const isTransfer = s?.record === "transfer" && !isSkip;
                const isExpense = s?.record !== "income";
                const best = s?.transferCandidates[0];
                const suggestedKind: RecordKind = isSkip
                  ? "skip"
                  : isSettlement
                    ? "settlement"
                    : isTransfer
                      ? "transfer"
                      : isExpense
                        ? row.amountMinor > 0
                          ? "refund"
                          : "expense"
                        : s?.incomeKind === "deduction"
                          ? "deduction"
                          : "income";
                const decidedKind: RecordKind =
                  row.outcome === "skipped" || row.outcome === "pending"
                    ? "skip"
                    : row.outcome === "expense" && row.amountMinor > 0
                      ? "refund"
                      : row.outcome;
                const otherAccount =
                  accountName(best?.accountId ?? s?.transferAccountId ?? "") || "another account";
                // What the row probably is (or became), in its own column on a wide table, under the row on a narrow one.
                const became = (
                  <>
                    {tab === "pending" && s && (
                      <>
                        <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
                          <KindChip kind={suggestedKind} />
                          <span>
                            {s.skipReason != null
                              ? s.skipReason
                              : isSettlement
                                ? `${memberName(s.settleFromUserId)} → ${memberName(s.settleToUserId)}`
                                : isTransfer
                                  ? `${row.amountMinor < 0 ? "To" : "From"} ${otherAccount}`
                                  : isExpense
                                    ? s.merchant || ""
                                    : s.incomeSource || ""}
                          </span>
                        </div>
                        <div className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
                          {s.skipReason != null || isTransfer || isSettlement ? (
                            "Not spending or income"
                          ) : isExpense ? (
                            <>
                              <CategoryLabel categories={categories} id={s.categoryId} short />
                              {members.length > 1 && (
                                <span className="inline-flex items-center gap-1.5">
                                  <Avatar members={members} userId={s.paidByUserId} />
                                  {memberName(s.paidByUserId)}
                                </span>
                              )}
                            </>
                          ) : s.incomeKind === "deduction" && s.earnedMonth ? (
                            `Taken off ${formatMonth(s.earnedMonth)}`
                          ) : members.length > 1 ? (
                            <span className="inline-flex items-center gap-1.5">
                              <Avatar members={members} userId={s.paidByUserId} />
                              {memberName(s.paidByUserId)}
                            </span>
                          ) : null}
                        </div>
                        {s.ruleName && (
                          <div className="mt-1 inline-flex items-center gap-1 text-xs text-muted-foreground">
                            <ZapIcon aria-hidden="true" className="size-3" /> Rule: {s.ruleName}
                          </div>
                        )}
                      </>
                    )}
                    {tab === "decided" && (
                      <>
                        <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
                          <KindChip kind={decidedKind}>{row.outcome === "skipped" ? "Skipped" : undefined}</KindChip>
                          <span>{row.outcome === "skipped" ? row.skipReason : row.outcomeLabel}</span>
                        </div>
                        {row.decidedAuto && (
                          <Chip icon={ZapIcon} className="mt-1" title="Filed without asking">
                            {ruleName(row.ruleId) ? `Rule: ${ruleName(row.ruleId)}` : "Automatic"}
                          </Chip>
                        )}
                      </>
                    )}
                  </>
                );
                return (
                  <TableRow key={row.id} data-state={selected.has(row.id) ? "selected" : undefined}>
                    <TableCell className="align-top">
                      <Checkbox
                        aria-label={`Select ${row.text}`}
                        checked={selected.has(row.id)}
                        onCheckedChange={(c) => toggle(row.id, c === true)}
                      />
                    </TableCell>
                    <TableCell className="align-top text-muted-foreground @max-xl:hidden">{formatDay(row.date)}</TableCell>
                    <TableCell className="whitespace-normal align-top">
                      <div className="flex flex-wrap items-baseline justify-between gap-x-2">
                        <span className="font-medium">{row.text || row.counterpartyAccount || "Bank row"}</span>
                        <span className={cn("amount shrink-0 @sm:hidden", signedAmountClass(row.amountMinor))}>{bankAmount(row)}</span>
                      </div>
                      <div className="text-xs text-muted-foreground">
                        <span className="@xl:hidden">{formatDay(row.date)} · </span>
                        {accountName(row.accountId)}
                        {row.message && row.message !== row.text && ` · ${row.message}`}
                      </div>
                      <div className="mt-1 border-l-2 pl-2 text-sm @3xl:hidden">{became}</div>
                      {row.hint && (
                        <div className="mt-1 text-xs">
                          <Chip tone="warning" className="h-auto max-w-full py-0.5 whitespace-normal">
                            {row.hint}
                          </Chip>
                        </div>
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
                      {tab === "pending" && isExpense && !isTransfer && !isSettlement && s?.refundCandidates[0] && (
                        <div className="mt-1.5 text-xs text-muted-foreground">
                          May refund {s.refundCandidates[0].label || "an expense"} · {formatDay(s.refundCandidates[0].date)} ·{" "}
                          <span className="amount">{formatMoney(s.refundCandidates[0].amountMinor, household.currency)}</span>
                          {s.refundCandidates.length > 1 && ` (${s.refundCandidates.length - 1} more to pick from)`}
                        </div>
                      )}
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
                    <TableCell className="whitespace-normal align-top text-sm @max-3xl:hidden">
                      {became}
                    </TableCell>
                    <TableCell className={cn("amount text-right align-top @max-sm:hidden", signedAmountClass(row.amountMinor))}>{bankAmount(row)}</TableCell>
                    <TableCell className="text-right align-top">
                      <div className="flex items-center justify-end gap-1">
                        {tab === "pending" && (
                          <Button
                            size="sm"
                            variant="outline"
                            className="@max-lg:hidden"
                            onClick={() =>
                              isSkip
                                ? skip([row])
                                : isSettlement
                                  ? openSettle(best ? [row, candidateRow(best)] : [row])
                                  : isTransfer
                                    ? best
                                      ? pair(row, best)
                                      : openTransfer(row)
                                    : isExpense
                                      ? openExpense([row])
                                      : openIncome([row])
                            }
                            disabled={isTransfer && decide.isPending}
                          >
                            {isSkip
                              ? "Skip"
                              : isSettlement
                                ? "Settle"
                                : isTransfer
                                  ? "Transfer"
                                  : isExpense
                                    ? s?.refundCandidates[0] && row.amountMinor > 0
                                      ? "Add refund"
                                      : "Add expense"
                                    : s?.incomeKind === "deduction"
                                      ? "Add deduction"
                                      : "Add income"}
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
                                  { label: "Settle between members…", onSelect: () => openSettle(isSettlement && best ? [row, candidateRow(best)] : [row]) },
                                  { label: "Skip…", onSelect: () => skip([row]) },
                                ]
                              : [
                                  { label: "Put back in the inbox", onSelect: () => undo(row) },
                                  { label: "Make a rule…", onSelect: () => makeRule(row) },
                                ]
                          }
                        />
                      </div>
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
          </div>
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
                  : editor.mode === "rule"
                    ? "New rule"
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
        {editor.mode === "rule" && <RuleForm key={editor.rowId} draft={editor.draft} onDone={() => setEditor({ mode: "closed" })} />}
      </Sheet>
    </>
  );
}
