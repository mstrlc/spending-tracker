import { Fragment, useState } from "react";
import { useAccounts, useAssets, useBankConnections, useBankFeeds, useBankRows, useCategories, useDecideBankRows, useInboxRules, useMembers, useOrders } from "../api/hooks";
import { transferLiabilityLabel } from "../assets/holdings";
import type { BankRow, BankRowRef, DuplicateCandidate, Order, TransferCandidate } from "../api/types";
import { formatDay, formatMonth, todayIso } from "../lib/dates";
import { formatMoney } from "../lib/money";
import { useHousehold } from "../households/HouseholdPages";
import { useConfirm, usePrompt } from "../dialogs";
import { EmptyState, ErrorText, PageHeader, PageIntro, RowMenu, Sheet, SkeletonRows, Surface } from "../ui";
import { ExpenseForm, type ExpenseDraft } from "../expenses/ExpenseForm";
import { IncomeForm, type IncomeDraft } from "../income/IncomeForm";
import { Avatar, CategoryLabel, Chip, KindChip, RECORD_KINDS, signedAmountClass, type RecordKind } from "../chips";
import { ArrowRightIcon, SearchIcon, ZapIcon, EyeOffIcon, ScissorsIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import type { Account, Category, Member } from "../api/types";
import {
  activeFilterCount,
  decidedKind as decidedKindOf,
  headerSort,
  inboxSortOf,
  type InboxColumn,
  filterRows,
  NO_FILTERS,
  SORTS,
  suggestedKind as suggestedKindOf,
  type InboxFilters,
  type InboxSort,
} from "./filters";
import { cn } from "@/lib/utils";
import { Checkbox } from "@/components/ui/checkbox";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { FeedsPanel } from "./FeedsPanel";
import { SyncStatusBar } from "./SyncStatusBar";
import { connectionWarning } from "./connections";
import { loadDecision } from "./decisions";
import { draftExpense, draftIncome, oppositeSides, skipReason } from "./drafts";
import { nextSort, SortableHead, type SortDir } from "../sorting";
import { emptySide, groupAlike, groupMoves, WAIT_DAYS, type EmptySide, type Move, type MoveGroup } from "./moves";
import { categoryTree, incomeCategories, spendingCategories } from "../categories/tree";
import { RuleForm } from "./RuleForm";
import { draftRule, type RuleDraft } from "./ruleDrafts";
import { SettleForm } from "./SettleForm";
import { TransferForm } from "./TransferForm";
import { ContributeForm } from "./ContributeForm";
import { OrderEmailForm, OrderItemsForm, WaitingOrders } from "./OrderPanels";

type Editor =
  | { mode: "closed" }
  | { mode: "feeds" }
  | { mode: "expense"; rows: BankRowRef[]; draft: ExpenseDraft }
  | { mode: "income"; rows: BankRowRef[]; draft: IncomeDraft }
  | { mode: "transfer"; row: BankRow; otherAccountId: string | null }
  | { mode: "settle"; rows: BankRow[] }
  | { mode: "contribute"; row: BankRow; assetId: string | null }
  | { mode: "rule"; rowId: string; draft: RuleDraft }
  | { mode: "orderEmail" }
  | { mode: "orderItems"; order: Order };

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
  const assets = useAssets(household.id).data ?? [];
  const members = useMembers(household.id).data ?? [];
  const categories = useCategories(household.id).data ?? [];
  const rules = useInboxRules(household.id).data ?? [];
  const orders = useOrders(household.id).data ?? [];
  const orderOfRow = (rowId: string) => orders.find((o) => o.bankRowId === rowId);
  const decide = useDecideBankRows(household.id);
  const prompt = usePrompt();
  const confirm = useConfirm();
  const [editor, setEditor] = useState<Editor>({ mode: "closed" });
  const [selected, setSelected] = useState<Set<string>>(new Set());
  // Groups of alike moves opened to show each move.
  const [expanded, setExpanded] = useState<Set<string>>(new Set());

  const [ruleError, setRuleError] = useState<unknown>(null);
  const loaded = rows.data ?? [];
  const autoCount = tab === "decided" ? loaded.filter((r) => r.decidedAuto).length : 0;
  const [filters, setFilters] = useState<InboxFilters>(NO_FILTERS);
  const tabRows = tab === "decided" && filter === "auto" ? loaded.filter((r) => r.decidedAuto) : loaded;
  // Category only means something on pending rows; the Decided tab leaves it out.
  const applied = tab === "decided" ? { ...filters, categoryId: "" } : filters;
  const list = filterRows(tabRows, applied, accounts, categories);
  const filtered = activeFilterCount(applied) > 0;
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
  const openContribute = (row: BankRow) => setEditor({ mode: "contribute", row, assetId: row.suggestion?.assetId ?? null });
  const assetName = (id?: string | null) => assets.find((a) => a.id === id)?.name;

  /** Files [row] into the asset its suggestion names, or asks which asset when it names none. */
  function contribute(row: BankRow) {
    const assetId = row.suggestion?.assetId;
    if (!assetId) return openContribute(row);
    decide.mutate({ action: "contribute", rows: [ref(row)], assetId }, { onSuccess: clear });
  }
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
      contributionId: null,
      remember: true,
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
        description: `If the ${row.outcome === "income" ? "income line" : row.outcome === "transfer" ? "transfer" : row.outcome === "settlement" ? "settlement" : row.outcome === "contribution" ? "investment" : "expense"} was made from this row, it's deleted once none of its rows is left. One entered by hand stays.`,
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
          "An expense, income line, transfer or investment made from them is deleted once none of its rows is left. One you entered by hand stays.",
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
  // Clicking a column header sets the same sort the sort menu does.
  const sortBy = (column: InboxColumn, first: SortDir) =>
    setFilters((f) => ({ ...f, sort: inboxSortOf(nextSort(headerSort(f.sort), column, first)) }));
  /** One bank row as a table row: what it is, what it probably is (or became), and what to do with it. */
  const renderRow = (row: BankRow) => {
                const s = row.suggestion;
                // A prepared split makes the row an expense, whatever the guess was.
                const drafted = tab === "pending" ? row.draft : null;
                const isSkip = !drafted && s?.skipReason != null;
                const isSettlement = !drafted && s?.record === "settlement" && !isSkip;
                const isTransfer = !drafted && s?.record === "transfer" && !isSkip;
                const isContribution = !drafted && s?.record === "contribution" && !isSkip;
                const isExpense = !!drafted || (s?.record !== "income" && !isContribution);
                const best = s?.transferCandidates[0];
                const suggestedKind = suggestedKindOf(row);
                const decidedKind = decidedKindOf(row);
                const otherAccountId = best?.accountId ?? s?.transferAccountId ?? null;
                // Where the money went, this row's account first when it's money out. A side the app doesn't read is named
                // by what the bank says (its account number), greyed.
                const otherSide = otherAccountId ? (
                  <span>{accountName(otherAccountId)}</span>
                ) : (
                  <span className="text-muted-foreground" title="Not an account in the app">
                    {row.counterpartyAccount || row.counterpartyIban || row.counterpartyName || "another account"}
                  </span>
                );
                const thisSide = <span>{accountName(row.accountId)}</span>;
                const route = (
                  <span className="inline-flex flex-wrap items-center gap-x-1">
                    {row.amountMinor < 0 ? thisSide : otherSide}
                    <ArrowRightIcon aria-label="to" className="size-3.5 shrink-0 text-muted-foreground" />
                    {row.amountMinor < 0 ? otherSide : thisSide}
                  </span>
                );
                const liabilityLabel = isTransfer
                  ? transferLiabilityLabel(
                      assets,
                      row.amountMinor < 0 ? row.accountId : otherAccountId,
                      row.amountMinor < 0 ? otherAccountId : row.accountId,
                    )
                  : null;
                // What the row probably is (or became), in its own column on a wide table, under the row on a narrow one.
                const became = (
                  <>
                    {drafted && (
                      <>
                        <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
                          <KindChip kind={suggestedKind} />
                          <span>{drafted.merchant || s?.merchant || ""}</span>
                          <Chip icon={ScissorsIcon} title="Prepared ahead; the row stays here until you add it">
                            {drafted.lines.length > 1 ? `Split drafted · ${drafted.lines.length} lines` : "Drafted"}
                          </Chip>
                        </div>
                        <ul className="mt-1 space-y-0.5 text-xs text-muted-foreground">
                          {drafted.lines.map((l, i) => (
                            <li key={i} className="flex flex-wrap items-center gap-x-3 gap-y-0.5">
                              <CategoryLabel categories={categories} id={l.categoryId ?? null} short />
                              {members.length > 1 && (
                                <span className="inline-flex items-center gap-1.5">
                                  <Avatar members={members} userId={l.paidByUserId ?? null} />
                                  {memberName(l.paidByUserId)}
                                </span>
                              )}
                              {l.notes && <span className="min-w-0 truncate">{l.notes}</span>}
                              <span className="amount ml-auto">{formatMoney(l.amountMinor, household.currency)}</span>
                            </li>
                          ))}
                        </ul>
                        {(drafted.source || drafted.notes) && (
                          <div className="mt-1 text-xs text-muted-foreground">{[drafted.source, drafted.notes].filter(Boolean).join(" · ")}</div>
                        )}
                      </>
                    )}
                    {tab === "pending" && !drafted && s && (
                      <>
                        <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
                          <KindChip kind={suggestedKind} />
                          <span>
                            {s.skipReason != null
                              ? s.skipReason
                              : isSettlement
                                ? `${memberName(s.settleFromUserId)} → ${memberName(s.settleToUserId)}`
                                : isContribution
                                  ? `${row.amountMinor < 0 ? "Into" : "Out of"} ${assetName(s.assetId) ?? "an asset"}`
                                  : isTransfer
                                  ? route
                                  : isExpense
                                    ? s.merchant || ""
                                    : s.incomeSource || ""}
                          </span>
                        </div>
                        <div className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
                          {isContribution ? (
                            "Invested, not spending"
                          ) : isSettlement ? (
                            route
                          ) : s.skipReason != null || isTransfer ? (
                            liabilityLabel ?? "Not spending or income"
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
                          ) : (
                            <>
                              <CategoryLabel categories={categories} id={s.categoryId} short />
                              {members.length > 1 && (
                                <span className="inline-flex items-center gap-1.5">
                                  <Avatar members={members} userId={s.paidByUserId} />
                                  {memberName(s.paidByUserId)}
                                </span>
                              )}
                            </>
                          )}
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
                        {row.remember === false && (
                          <Chip icon={EyeOffIcon} className="mt-1" title="Later rows from the same payee aren't suggested from this one">
                            Not remembered
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
                                : isContribution
                                  ? contribute(row)
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
                            disabled={(isTransfer || isContribution) && decide.isPending}
                          >
                            {isSkip
                              ? "Skip"
                              : isContribution
                                ? row.amountMinor < 0
                                  ? "Invest"
                                  : "Take out"
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
                                  ...(best ? [{ label: "Pair", onSelect: () => pair(row, best) }] : []),
                                  { label: "Transfer…", onSelect: () => openTransfer(row) },
                                  { label: "Settle between members…", onSelect: () => openSettle(isSettlement && best ? [row, candidateRow(best)] : [row]) },
                                  { label: "Investment…", onSelect: () => openContribute(row) },
                                  { label: "Skip…", onSelect: () => skip([row]) },
                                  ...(orderOfRow(row.id)
                                    ? [{ label: "Order items…", onSelect: () => setEditor({ mode: "orderItems", order: orderOfRow(row.id)! }) }]
                                    : []),
                                  ...(drafted
                                    ? [
                                        {
                                          label: "Discard the prepared split",
                                          onSelect: () => decide.mutate({ action: "draft", row: ref(row), draft: null }),
                                        },
                                      ]
                                    : []),
                                ]
                              : [
                                  { label: "Put back in the inbox", onSelect: () => undo(row) },
                                  { label: "Make a rule…", onSelect: () => makeRule(row) },
                                  row.remember !== false
                                    ? {
                                        label: "Don't remember this",
                                        onSelect: () => decide.mutate({ action: "remember", rows: [ref(row)], remember: false }),
                                      }
                                    : {
                                        label: "Remember this again",
                                        onSelect: () => decide.mutate({ action: "remember", rows: [ref(row)], remember: true }),
                                      },
                                ]
                          }
                        />
                      </div>
                    </TableCell>
                  </TableRow>
                );
  };

  const readAccounts = new Set((feeds.data ?? []).map((f) => f.accountId));
  const today = todayIso();
  const items = groupMoves(list, tab, candidateRow);

  /** What a move's empty side says: why the app has no row for it. */
  const emptyText = (e: EmptySide | null) =>
    e === null || e.why === "unknown"
      ? "Not an account in the app"
      : e.why === "waiting"
        ? "Waiting for the bank to book it"
        : e.why === "late"
          ? `Nothing came in ${WAIT_DAYS} days`
          : "Not read by the app";

  /** One side of a move: the account, what it moved and what the bank said; dashed and empty when there's no row for it. */
  const moveSide = (r: BankRow | null, present: BankRow, empty: EmptySide | null, amountLabel?: string) =>
    r ? (
      <div className="min-w-0">
        <div className="flex flex-wrap items-baseline justify-between gap-x-2">
          <span className="font-medium">{accountName(r.accountId)}</span>
          <span className={cn("amount shrink-0 text-sm", signedAmountClass(r.amountMinor))}>{amountLabel ?? bankAmount(r)}</span>
        </div>
        <div className="truncate text-xs text-muted-foreground" title={r.text}>
          {r.text || r.counterpartyAccount || "Bank row"}
        </div>
        <div className="text-xs text-muted-foreground">{formatDay(r.date)}</div>
      </div>
    ) : (
      <div className="min-w-0 rounded-md border border-dashed px-2 py-1">
        <div className="font-medium text-muted-foreground">
          {empty && empty.why !== "unknown"
            ? accountName(empty.accountId)
            : present.counterpartyAccount || present.counterpartyIban || present.counterpartyName || "Another account"}
        </div>
        <div className="text-xs text-muted-foreground">{emptyText(empty)}</div>
      </div>
    );

  /** Money between two accounts as one table row: the side it left on the left, the side it arrived on the right. */
  const renderMove = (m: Move, group?: MoveGroup, inGroup = false) => {
    const present = (m.out ?? m.into)!;
    // A group stands for all its moves: their rows, their total, and one action for them all.
    const groupRows = group ? group.rows : m.rows;
    const count = group ? group.moves.length : 1;
    const sideAmount = (side: "out" | "into") => {
      if (!group) return undefined;
      const amounts = group.moves.map((g) => (side === "out" ? g.out : g.into)?.amountMinor ?? 0);
      const r = (side === "out" ? m.out : m.into)!;
      return amounts.every((a) => a === amounts[0])
        ? `${count} × ${bankAmount(r)}`
        : bankAmount({ amountMinor: amounts.reduce((a, b) => a + b, 0), currency: r.currency });
    };
    const totalMinor = group ? group.moves.reduce((sum, g) => sum + Math.abs((g.out ?? g.into)!.amountMinor), 0) : Math.abs(present.amountMinor);
    const open = group ? expanded.has(group.key) : false;
    const toggleOpen = () =>
      group &&
      setExpanded((prev) => {
        const next = new Set(prev);
        if (next.has(group.key)) next.delete(group.key);
        else next.add(group.key);
        return next;
      });
    const s = present.suggestion;
    const pending = tab === "pending";
    const empty = m.rows.length === 1 ? emptySide(present, s?.transferAccountId ?? null, readAccounts, today) : null;
    const isSettlement = m.record === "settlement";
    const allPicked = groupRows.every((r) => selected.has(r.id));
    const somePicked = groupRows.some((r) => selected.has(r.id));
    const liabilityLabel = !isSettlement
      ? transferLiabilityLabel(assets, m.out?.accountId ?? (empty && empty.why !== "unknown" ? empty.accountId : null), m.into?.accountId ?? (empty && empty.why !== "unknown" ? empty.accountId : null))
      : null;
    const label = present.text || "this move";
    // Whose accounts the money moved between: one person (or Joint) when both are theirs, "A → B" when not, and
    // only the known side when the other isn't an account in the app.
    const otherSideAccount = empty && empty.why !== "unknown" ? empty.accountId : null;
    const ownerAt = (accountId: string | null | undefined) =>
      accountId && accounts.some((a) => a.id === accountId) ? ownerOf(accountId) : undefined;
    const fromOwner = ownerAt(m.out?.accountId ?? otherSideAccount);
    const toOwner = ownerAt(m.into?.accountId ?? otherSideAccount);
    const person = (userId: string | null) => (
      <span className="inline-flex items-center gap-1.5">
        <Avatar members={members} userId={userId} />
        {memberName(userId)}
      </span>
    );
    const people =
      members.length < 2 || (fromOwner === undefined && toOwner === undefined) ? null : fromOwner === undefined || toOwner === undefined ? (
        person(fromOwner !== undefined ? fromOwner : toOwner!)
      ) : fromOwner === toOwner ? (
        person(fromOwner)
      ) : (
        <>
          {person(fromOwner)}
          <ArrowRightIcon aria-label="to" className="size-3" />
          {person(toOwner)}
        </>
      );
    const what = (
      <>
        <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
          <KindChip kind={isSettlement ? "settlement" : "transfer"} />
          {isSettlement && pending && s && <span>{`${memberName(s.settleFromUserId)} → ${memberName(s.settleToUserId)}`}</span>}
          {!pending && <span>{present.outcomeLabel}</span>}
        </div>
        <div className="mt-1 flex flex-wrap items-center gap-x-1.5 gap-y-1 text-xs text-muted-foreground">
          {people ?? (liabilityLabel ? null : "Not spending or income")}
          {liabilityLabel && <span>{people ? `· ${liabilityLabel}` : liabilityLabel}</span>}
        </div>
        {pending && s?.ruleName && (
          <div className="mt-1 inline-flex items-center gap-1 text-xs text-muted-foreground">
            <ZapIcon aria-hidden="true" className="size-3" /> Rule: {s.ruleName}
          </div>
        )}
        {!pending && present.decidedAuto && (
          <Chip icon={ZapIcon} className="mt-1" title="Filed without asking">
            {ruleName(present.ruleId) ? `Rule: ${ruleName(present.ruleId)}` : "Automatic"}
          </Chip>
        )}
      </>
    );
    const fileOneSide = () => (isSettlement ? openSettle([present]) : openTransfer(present));
    return (
      <TableRow
        key={group ? `group:${group.key}` : m.rows.map((r) => r.id).join("+")}
        data-state={allPicked ? "selected" : undefined}
        className={cn(inGroup && "bg-muted/40")}
      >
        <TableCell className="align-top">
          <Checkbox
            aria-label={`Select ${label}`}
            checked={allPicked ? true : somePicked ? "indeterminate" : false}
            onCheckedChange={(c) => groupRows.forEach((r) => toggle(r.id, c === true))}
          />
        </TableCell>
        <TableCell className="align-top text-muted-foreground @max-xl:hidden">{formatDay((m.out ?? m.into)!.date)}</TableCell>
        <TableCell className="whitespace-normal align-top">
          <div className="grid grid-cols-[minmax(0,1fr)_auto_minmax(0,1fr)] items-start gap-2">
            {moveSide(m.out, present, empty, m.out ? sideAmount("out") : undefined)}
            <ArrowRightIcon aria-label="to" className="mt-1 size-4 text-muted-foreground" />
            {moveSide(m.into, present, empty, m.into ? sideAmount("into") : undefined)}
          </div>
          {group && (
            <Button size="xs" variant="ghost" className="mt-1 -ml-2 text-muted-foreground" onClick={toggleOpen} aria-expanded={open}>
              {open ? "Hide them" : `${count} alike · show each`}
            </Button>
          )}
          <div className="mt-1 border-l-2 pl-2 text-sm @3xl:hidden">{what}</div>
        </TableCell>
        <TableCell className="whitespace-normal align-top text-sm @max-3xl:hidden">{what}</TableCell>
        <TableCell className="amount text-right align-top @max-sm:hidden">
          {formatMoney(totalMinor, present.currency)}
        </TableCell>
        <TableCell className="text-right align-top">
          <div className="flex items-center justify-end gap-1">
            {pending &&
              (group && !(m.rows.length === 2 && !isSettlement) && empty?.why !== "waiting" ? (
                <Button size="sm" variant="outline" className="@max-lg:hidden" onClick={toggleOpen}>
                  {open ? "Hide" : "Show each"}
                </Button>
              ) : m.rows.length === 2 ? (
                <Button
                  size="sm"
                  variant="outline"
                  className="@max-lg:hidden"
                  onClick={() =>
                    group
                      ? pairAll(group.moves)
                      : isSettlement
                        ? openSettle(m.rows)
                        : decide.mutate({ action: "transfer", rows: [ref(m.out!), ref(m.into!)] }, { onSuccess: clear })
                  }
                  disabled={!isSettlement && decide.isPending}
                >
                  {isSettlement ? "Settle" : group ? `Pair all ${count}` : "Pair"}
                </Button>
              ) : empty?.why === "waiting" ? (
                <Button size="sm" variant="ghost" className="@max-lg:hidden" disabled title="Pair it once the other side comes in">
                  Waiting
                </Button>
              ) : (
                <Button size="sm" variant="outline" className="@max-lg:hidden" onClick={fileOneSide}>
                  {isSettlement ? "Settle" : "Transfer"}
                </Button>
              ))}
            <RowMenu
              label={label}
              actions={
                group
                  ? [
                      { label: open ? "Hide each" : "Show each", onSelect: toggleOpen },
                      ...(pending && m.rows.length === 2 && !isSettlement ? [{ label: `Pair all ${count}`, onSelect: () => pairAll(group.moves) }] : []),
                      ...(pending
                        ? [{ label: `Skip all ${count}…`, onSelect: () => skip(group.rows) }]
                        : [{ label: `Put all ${count} back in the inbox`, onSelect: () => undoMany(group.rows) }]),
                    ]
                  : pending
                  ? m.rows.length === 2
                    ? [
                        isSettlement
                          ? { label: "Settle between members…", onSelect: () => openSettle(m.rows) }
                          : { label: "Pair", onSelect: () => decide.mutate({ action: "transfer", rows: [ref(m.out!), ref(m.into!)] }, { onSuccess: clear }) },
                        { label: "Skip both…", onSelect: () => skip(m.rows) },
                      ]
                    : [
                        { label: isSettlement ? "Settle between members…" : "Transfer…", onSelect: fileOneSide },
                        { label: "Add as expense", onSelect: () => openExpense([present]) },
                        { label: "Add as income", onSelect: () => openIncome([present]) },
                        { label: "Investment…", onSelect: () => openContribute(present) },
                        { label: "Skip…", onSelect: () => skip([present]) },
                      ]
                  : [
                      { label: "Put back in the inbox", onSelect: () => undoMany(m.rows) },
                      { label: "Make a rule…", onSelect: () => makeRule(present) },
                    ]
              }
            />
          </div>
        </TableCell>
      </TableRow>
    );
  };

  /** Pairs each of [moves] as a transfer, one after another. */
  async function pairAll(moves: Move[]) {
    for (const m of moves) {
      if (m.out && m.into) await decide.mutateAsync({ action: "transfer", rows: [ref(m.out), ref(m.into)] });
    }
    clear();
  }

  // Waiting moves alike in all but the amount (ten top-ups from one card on one day) go in one row; so do pairs.
  const moveKey = (m: Move): string | null => {
    if (tab !== "pending") return null;
    const present = (m.out ?? m.into)!;
    const empty = m.rows.length === 1 ? emptySide(present, present.suggestion?.transferAccountId ?? null, readAccounts, today) : null;
    const side = (r: BankRow | null) => (r ? [r.accountId, r.date, r.text, r.currency].join("~") : "");
    return [m.record, side(m.out), side(m.into), empty ? `${empty.why}~${"accountId" in empty ? empty.accountId : ""}` : ""].join("|");
  };
  const shownItems = groupAlike(items, moveKey);

  const renderGroup = (g: MoveGroup) => (
    <Fragment key={`group:${g.key}`}>
      {renderMove(g.moves[0], g)}
      {expanded.has(g.key) && g.moves.map((m) => renderMove(m, undefined, true))}
    </Fragment>
  );

  const noFeeds = feeds.data?.length === 0;

  return (
    <>
      <PageHeader title="Inbox">
        <Button variant="outline" onClick={() => setEditor({ mode: "orderEmail" })}>
          Order emails
        </Button>
        <Button variant="outline" onClick={() => setEditor({ mode: "feeds" })}>
          Bank feeds
        </Button>
      </PageHeader>
      <PageIntro>
        Bank rows waiting for a decision: add each as an expense or income, file it under one you already entered, pair it
        with the other side of a transfer, or skip it. Suggestions come from what you decided before.
      </PageIntro>

      <SyncStatusBar onDetails={() => setEditor({ mode: "feeds" })} />

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

      {tab === "pending" && <WaitingOrders orders={orders} rows={loaded} />}

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

      {rows.data && tabRows.length > 0 && (
        <FilterBar
          value={filters}
          onChange={(f) => setFilters(f)}
          tab={tab}
          accounts={accounts}
          members={members}
          categories={categories}
          shown={list.length}
          total={tabRows.length}
          active={activeFilterCount(applied)}
        />
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
          ) : filtered && tabRows.length > 0 ? (
            <EmptyState title="Nothing matches" action={<Button variant="outline" onClick={() => setFilters({ ...NO_FILTERS, sort: filters.sort })}>Clear filters</Button>}>
              No {tab === "pending" ? "waiting" : "decided"} row passes these filters.
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
                <SortableHead column="day" first="desc" sort={headerSort(filters.sort)} onSort={sortBy} className="@max-xl:hidden">
                  Day
                </SortableHead>
                <SortableHead column="name" sort={headerSort(filters.sort)} onSort={sortBy}>
                  Bank row
                </SortableHead>
                <TableHead className="@max-3xl:hidden">{tab === "pending" ? "Suggestion" : "Became"}</TableHead>
                <SortableHead column="amount" first="desc" sort={headerSort(filters.sort)} onSort={sortBy} className="text-right @max-sm:hidden">
                  Amount
                </SortableHead>
                <TableHead className="w-12">
                  <span className="sr-only">Actions</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {shownItems.map((item) =>
                item.kind === "group" ? renderGroup(item) : item.kind === "move" ? renderMove(item) : renderRow(item.row),
              )}
            </TableBody>
          </Table>
          </div>
        )}
      </Surface>

      {/* Sticks to the window's bottom while rows are selected, wherever the table is scrolled to. */}
      {picked.length > 0 && (
        <div
          className="sticky bottom-4 z-10 flex flex-wrap items-center gap-2 rounded-lg border bg-background/95 px-3 py-2 shadow-lg backdrop-blur supports-[backdrop-filter]:bg-background/80"
          role="toolbar"
          aria-label="Selected rows"
        >
          <span className="text-sm">
            {picked.length} selected
            {tab === "pending" &&
              new Set(picked.map((r) => r.currency)).size === 1 &&
              ` · ${bankAmount({ amountMinor: picked.reduce((s, r) => s + r.amountMinor, 0), currency: picked[0].currency })}`}
          </span>
          <span className="flex-1" />
          {tab === "decided" ? (
            <Button size="sm" onClick={() => undoMany(picked)} disabled={decide.isPending}>
              Put back in the inbox
            </Button>
          ) : (
            <>
              <Button size="sm" onClick={() => openExpense(picked)}>
                One expense
              </Button>
              <Button size="sm" variant="outline" onClick={() => openIncome(picked)}>
                One income line
              </Button>
              {sides && (
                <>
                  <Button size="sm" variant="outline" onClick={() => pairSelected(sides)} disabled={decide.isPending}>
                    Pair
                  </Button>
                  <Button size="sm" variant="outline" onClick={() => openSettle(picked)}>
                    Settle
                  </Button>
                </>
              )}
              {picked.length === 1 && (
                <Button size="sm" variant="outline" onClick={() => openContribute(picked[0])}>
                  Investment
                </Button>
              )}
              <Button size="sm" variant="outline" onClick={() => skip(picked)}>
                Skip
              </Button>
            </>
          )}
          <Button size="sm" variant="ghost" onClick={clear}>
            Clear
          </Button>
        </div>
      )}

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
                  : editor.mode === "contribute"
                    ? "Investment"
                  : editor.mode === "rule"
                    ? "New rule"
                  : editor.mode === "orderEmail"
                    ? "Order emails"
                  : editor.mode === "orderItems"
                    ? "Order items"
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
        {editor.mode === "contribute" && (
          <ContributeForm
            key={editor.row.id}
            row={editor.row}
            assetId={editor.assetId}
            onSaved={clear}
            onDone={() => setEditor({ mode: "closed" })}
          />
        )}
        {editor.mode === "rule" && <RuleForm key={editor.rowId} draft={editor.draft} onDone={() => setEditor({ mode: "closed" })} />}
        {editor.mode === "orderEmail" && <OrderEmailForm onDone={() => setEditor({ mode: "closed" })} />}
        {editor.mode === "orderItems" && (
          <OrderItemsForm key={editor.order.id} order={editor.order} onDone={() => setEditor({ mode: "closed" })} />
        )}
      </Sheet>
    </>
  );
}

const PENDING_KINDS: RecordKind[] = ["expense", "refund", "income", "deduction", "transfer", "settlement", "contribution", "skip"];

/** Search, date range, account, whose, kind, category, direction and sort for the inbox's rows. */
function FilterBar({
  value,
  onChange,
  tab,
  accounts,
  members,
  categories,
  shown,
  total,
  active,
}: {
  value: InboxFilters;
  onChange: (f: InboxFilters) => void;
  tab: "pending" | "decided";
  accounts: Account[];
  members: Member[];
  categories: Category[];
  shown: number;
  total: number;
  active: number;
}) {
  const set = <K extends keyof InboxFilters>(key: K, v: InboxFilters[K]) => onChange({ ...value, [key]: v });
  // Spending first, then income, each labelled, so the two kinds never mix in one list.
  const shownCategories = categories.filter((c) => !c.archived);
  const tree = [
    ...categoryTree(spendingCategories(shownCategories)).map((parent) => ({ parent, label: parent.name })),
    ...categoryTree(incomeCategories(shownCategories)).map((parent) => ({ parent, label: `${parent.name} (income)` })),
  ];
  return (
    <div className="flex flex-col gap-2" role="search" aria-label="Filter bank rows">
      <div className="flex flex-wrap items-center gap-2">
        <div className="relative w-full sm:w-56">
          <SearchIcon aria-hidden="true" className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            type="search"
            aria-label="Search bank rows"
            placeholder="Search"
            className="h-8 pl-8"
            value={value.query}
            onChange={(e) => set("query", e.target.value)}
          />
        </div>
        <Input
          type="date"
          aria-label="From day"
          className="h-8 w-36"
          value={value.from}
          max={value.to || undefined}
          onChange={(e) => set("from", e.target.value)}
        />
        <span className="text-sm text-muted-foreground">to</span>
        <Input
          type="date"
          aria-label="To day"
          className="h-8 w-36"
          value={value.to}
          min={value.from || undefined}
          onChange={(e) => set("to", e.target.value)}
        />
        <NativeSelect aria-label="Account" value={value.accountId} onChange={(e) => set("accountId", e.target.value)} className="w-40">
          <NativeSelectOption value="">All accounts</NativeSelectOption>
          {accounts.map((a) => (
            <NativeSelectOption key={a.id} value={a.id}>
              {a.name}
            </NativeSelectOption>
          ))}
        </NativeSelect>
        {members.length > 1 && (
          <NativeSelect aria-label="Whose" value={value.person} onChange={(e) => set("person", e.target.value)} className="w-36">
            <NativeSelectOption value="">Everyone</NativeSelectOption>
            <NativeSelectOption value="joint">Joint</NativeSelectOption>
            {members.map((m) => (
              <NativeSelectOption key={m.userId} value={m.userId}>
                {m.displayName}
              </NativeSelectOption>
            ))}
          </NativeSelect>
        )}
        <NativeSelect
          aria-label={tab === "pending" ? "Suggested as" : "Became"}
          value={value.kind}
          onChange={(e) => set("kind", e.target.value as InboxFilters["kind"])}
          className="w-36"
        >
          <NativeSelectOption value="">Any kind</NativeSelectOption>
          {PENDING_KINDS.map((k) => (
            <NativeSelectOption key={k} value={k}>
              {k === "skip" && tab === "decided" ? "Skipped" : RECORD_KINDS[k].label}
            </NativeSelectOption>
          ))}
        </NativeSelect>
        {tab === "pending" && (
          <NativeSelect aria-label="Category" value={value.categoryId} onChange={(e) => set("categoryId", e.target.value)} className="w-40">
            <NativeSelectOption value="">Any category</NativeSelectOption>
            <NativeSelectOption value="none">Uncategorised</NativeSelectOption>
            {tree.map(({ parent, label }) => (
              <optgroup key={parent.id} label={label}>
                <NativeSelectOption value={parent.id}>All {parent.name}</NativeSelectOption>
                {parent.children
                  .filter((c) => !c.archived)
                  .map((c) => (
                    <NativeSelectOption key={c.id} value={c.id}>
                      {c.name}
                    </NativeSelectOption>
                  ))}
              </optgroup>
            ))}
          </NativeSelect>
        )}
        <NativeSelect
          aria-label="Money in or out"
          value={value.direction}
          onChange={(e) => set("direction", e.target.value as InboxFilters["direction"])}
          className="w-32"
        >
          <NativeSelectOption value="">In and out</NativeSelectOption>
          <NativeSelectOption value="out">Money out</NativeSelectOption>
          <NativeSelectOption value="in">Money in</NativeSelectOption>
        </NativeSelect>
        <NativeSelect aria-label="Sort" value={value.sort} onChange={(e) => set("sort", e.target.value as InboxSort)} className="w-44">
          {SORTS.map((s) => (
            <NativeSelectOption key={s.value} value={s.value}>
              {s.label}
            </NativeSelectOption>
          ))}
        </NativeSelect>
      </div>
      {active > 0 && (
        <div className="flex items-center gap-2 text-sm text-muted-foreground" aria-live="polite">
          Showing {shown} of {total}
          <Button size="xs" variant="ghost" onClick={() => onChange({ ...NO_FILTERS, sort: value.sort })}>
            Clear filters
          </Button>
        </div>
      )}
    </div>
  );
}
