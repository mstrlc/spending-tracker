import { useState } from "react";
import { AssetIcon, PersonLabel, gainClass } from "../chips";
import {
  useAccounts,
  useAssetHistory,
  useAssets,
  useAssetSummary,
  useContributions,
  useFees,
  useMembers,
  useValuations,
} from "../api/hooks";
import type { Fee, HistoryPoint, Asset, SecurityQuote } from "../api/types";
import { useHousehold } from "../households/HouseholdPages";
import { PlusIcon } from "lucide-react";
import { formatDay } from "../lib/dates";
import { EmptyState, ErrorText, Money, PageHeader, Sheet, SheetBody, SkeletonRows, Summary, Surface } from "../ui";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { cn } from "@/lib/utils";
import { EntryForm, type EntryTarget } from "./EntryForm";
import { HistoryChart } from "./HistoryChart";
import { HoldingForm } from "./HoldingForm";
import {
  feeSummary,
  feesByYear,
  formatAsset,
  formatPercent,
  formatSigned,
  gainRatio,
  historyEntries,
  isLiabilityKind,
  kindLabel,
  portfolioTotals,
} from "./holdings";

/** "€168.06" in the security's own currency. */
function formatClose(security: SecurityQuote): string {
  if (security.close === null) return "–";
  try {
    return new Intl.NumberFormat(undefined, { style: "currency", currency: security.currency }).format(security.close);
  } catch {
    return `${security.close} ${security.currency}`;
  }
}

/** "55 shares" without trailing float noise. */
const formatShares = (shares: number) => `${Number(shares.toFixed(6)).toLocaleString()} ${shares === 1 ? "share" : "shares"}`;

/** What a holding's figures rest on, for the line under its name. */
function valuedBy(i: Asset): string {
  if (i.valueSource === "market" && i.security && i.valuedOn) return `${i.security.symbol} · ${formatClose(i.security)} on ${formatDay(i.valuedOn)}`;
  if (i.security) return `${i.security.symbol} · no shares recorded`;
  if (i.valueSource === "bank" && i.valuedOn) return `bank balance on ${formatDay(i.valuedOn)}`;
  return i.valuedOn
    ? `valued ${formatDay(i.valuedOn)}`
    : i.bankAvailableOnly
      ? "bank only reports available credit"
      : i.accountId
        ? "waiting for bank balance"
        : "not valued yet";
}

type Editor = { mode: "closed" } | { mode: "add" } | { mode: "edit"; asset: Asset } | { mode: "view"; id: string };

export function AssetsPage() {
  const household = useHousehold();
  const assets = useAssets(household.id);
  const summaryQuery = useAssetSummary(household.id);
  const history = useAssetHistory(household.id);
  const members = useMembers(household.id).data ?? [];
  const [editor, setEditor] = useState<Editor>({ mode: "closed" });
  const [flashId, setFlashId] = useState<string | null>(null);

  const rows = assets.data ?? [];
  const owned = rows.filter((i) => !isLiabilityKind(i.kind));
  const owed = rows.filter((i) => isLiabilityKind(i.kind));
  const totals = portfolioTotals(rows);
  const summary = summaryQuery.data ?? {
    assetsMinor: totals.valueMinor,
    liabilitiesMinor: owed.reduce((s, i) => s + i.valueMinor, 0),
    netMinor: totals.valueMinor + owed.reduce((s, i) => s + i.valueMinor, 0),
    contributedMinor: totals.contributedMinor,
    gainMinor: totals.gainMinor,
    feesMinor: rows.reduce((s, i) => s + i.feesMinor, 0),
    annualReturn: null,
  };
  const ratio = gainRatio(summary);
  const showOwner = members.length > 1;
  const viewing = editor.mode === "view" ? rows.find((i) => i.id === editor.id) : undefined;
  const openAdd = () => setEditor({ mode: "add" });
  const close = () => setEditor({ mode: "closed" });
  const { currency } = household;

  return (
    <>
      <PageHeader title="Assets">
        <Button size="lg" onClick={openAdd}>
          <PlusIcon /> Add asset
        </Button>
      </PageHeader>

      {rows.length > 0 && (
        <div className="flex flex-col gap-5">
          <Summary
            items={[
              { label: "Owned", value: <Money currency={currency}>{formatAsset(summary.assetsMinor, currency)}</Money> },
              { label: "Owed", value: <Money currency={currency}>{formatAsset(Math.abs(summary.liabilitiesMinor), currency)}</Money> },
              { label: "Net", value: <Money currency={currency}>{formatAsset(summary.netMinor, currency)}</Money> },
              (summary.contributedMinor > 0 || summary.gainMinor !== 0) && {
                label: `Gain${ratio !== null ? ` · ${formatPercent(ratio)}` : ""}`,
                value: <Money currency={currency}>{formatSigned(summary.gainMinor, currency)}</Money>,
              },
              summary.annualReturn !== null && {
                label: "Yearly return (money-weighted)",
                value: <span className="amount">{formatPercent(summary.annualReturn)}</span>,
              },
              summary.feesMinor !== 0 && {
                label: `Fees paid${summary.contributedMinor > 0 ? ` · ${((summary.feesMinor / summary.contributedMinor) * 100).toFixed(1)}% of put in` : ""}`,
                value: <Money currency={currency}>{formatAsset(summary.feesMinor, currency)}</Money>,
              },
            ]}
          />
          {history.data && <HistoryChart points={history.data.points} currency={currency} />}
        </div>
      )}

      {(assets.isPending || assets.error || rows.length === 0 || owned.length > 0) && (
        <Surface>
          <ErrorText error={assets.error} className="p-4" />
          {assets.isPending && <SkeletonRows />}
          {assets.data && rows.length === 0 && (
            <EmptyState
              title="No assets yet"
              action={
                <Button onClick={openAdd}>
                  <PlusIcon /> Add asset
                </Button>
              }
            >
              Add a fund, savings, a car or a loan, then record what you put in and what it's worth now and then.
            </EmptyState>
          )}
          {owned.length > 0 && (
            <Table>
              <TableHeader>
                <TableRow className="hover:bg-transparent">
                  <TableHead>Asset</TableHead>
                  {showOwner && <TableHead className="max-md:hidden">Held by</TableHead>}
                  <TableHead className="text-right max-md:hidden">Put in</TableHead>
                  <TableHead className="text-right">Worth ({currency})</TableHead>
                  <TableHead className="text-right max-md:hidden">Gain</TableHead>
                  <TableHead className="text-right">%</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {owned.map((i) => {
                  const r = gainRatio(i);
                  return (
                    <TableRow
                      key={i.id}
                      className={cn("cursor-pointer", i.id === flashId && "animate-flash")}
                      onClick={() => setEditor({ mode: "view", id: i.id })}
                      onAnimationEnd={() => setFlashId(null)}
                    >
                      <TableCell className="whitespace-normal">
                        <div className="flex items-center gap-3">
                          <AssetIcon kind={i.kind} />
                          <div className="min-w-0">
                            <button
                              className="text-left font-medium outline-none hover:underline focus-visible:underline"
                              onClick={(event) => {
                                event.stopPropagation();
                                setEditor({ mode: "view", id: i.id });
                              }}
                            >
                              {i.name}
                            </button>
                            <div className="text-xs text-muted-foreground">
                              {kindLabel(i.kind)} · {valuedBy(i)}
                            </div>
                          </div>
                        </div>
                      </TableCell>
                      {showOwner && (
                        <TableCell className="text-muted-foreground max-md:hidden">
                          <PersonLabel members={members} userId={i.ownerUserId} jointLabel="Shared" />
                        </TableCell>
                      )}
                      <TableCell className="amount text-right max-md:hidden">{formatAsset(i.contributedMinor, currency)}</TableCell>
                      <TableCell className="amount text-right">{formatAsset(i.valueMinor, currency)}</TableCell>
                      <TableCell className={cn("amount text-right max-md:hidden", gainClass(i.gainMinor))}>{formatSigned(i.gainMinor, currency)}</TableCell>
                      <TableCell className="amount text-right">
                        <div className={cn(r !== null && gainClass(r))}>{r === null ? "–" : formatPercent(r)}</div>
                        {i.annualReturn !== null && (
                          <div className="text-xs text-muted-foreground" title="Yearly return (money-weighted)">
                            {formatPercent(i.annualReturn)}/yr
                          </div>
                        )}
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          )}
        </Surface>
      )}

      {owed.length > 0 && (
        <div className="flex flex-col gap-2">
          <h2 className="text-sm font-medium text-muted-foreground">Liabilities</h2>
          <Surface>
            <Table>
              <TableHeader>
                <TableRow className="hover:bg-transparent">
                  <TableHead>Liability</TableHead>
                  {showOwner && <TableHead className="max-md:hidden">Held by</TableHead>}
                  <TableHead className="text-right max-md:hidden">Repaid</TableHead>
                  <TableHead className="text-right">Owed ({currency})</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {owed.map((i) => (
                  <TableRow
                    key={i.id}
                    className={cn("cursor-pointer", i.id === flashId && "animate-flash")}
                    onClick={() => setEditor({ mode: "view", id: i.id })}
                    onAnimationEnd={() => setFlashId(null)}
                  >
                    <TableCell className="whitespace-normal">
                      <div className="flex items-center gap-3">
                        <AssetIcon kind={i.kind} />
                        <div className="min-w-0">
                          <button
                            className="text-left font-medium outline-none hover:underline focus-visible:underline"
                            onClick={(event) => {
                              event.stopPropagation();
                              setEditor({ mode: "view", id: i.id });
                            }}
                          >
                            {i.name}
                          </button>
                          <div className="text-xs text-muted-foreground">
                            {kindLabel(i.kind)} · {valuedBy(i)}
                          </div>
                        </div>
                      </div>
                    </TableCell>
                    {showOwner && (
                      <TableCell className="text-muted-foreground max-md:hidden">
                        <PersonLabel members={members} userId={i.ownerUserId} jointLabel="Shared" />
                      </TableCell>
                    )}
                    <TableCell className="amount text-right max-md:hidden">{formatAsset(i.repaidMinor ?? 0, currency)}</TableCell>
                    <TableCell className="amount text-right">{formatAsset(-i.valueMinor, currency)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Surface>
        </div>
      )}

      <Sheet
        open={editor.mode === "add" || editor.mode === "edit"}
        onClose={close}
        title={editor.mode === "edit" ? "Edit asset" : "Add asset"}
      >
        <HoldingForm
          key={editor.mode === "edit" ? editor.asset.id : "new"}
          asset={editor.mode === "edit" ? editor.asset : undefined}
          onSaved={(saved) => {
            setFlashId(saved.id);
            // A new holding opens straight away, ready for its first contribution.
            setEditor({ mode: "view", id: saved.id });
          }}
          onDeleted={close}
          onCancel={() => setEditor(editor.mode === "edit" ? { mode: "view", id: editor.asset.id } : { mode: "closed" })}
        />
      </Sheet>

      <Sheet open={viewing !== undefined} onClose={close} title={viewing?.name ?? ""}>
        {viewing && <HoldingDetail key={viewing.id} asset={viewing} onEdit={() => setEditor({ mode: "edit", asset: viewing })} />}
      </Sheet>
    </>
  );
}

/** One holding: where it stands, a form for the next contribution or value, and its history. */
function HoldingDetail({ asset, onEdit }: { asset: Asset; onEdit: () => void }) {
  const household = useHousehold();
  const { currency } = household;
  const contributions = useContributions(household.id, asset.id);
  const valuations = useValuations(household.id, asset.id);
  const fees = useFees(household.id, asset.id);
  const timeline = useAssetHistory(household.id, asset.id);
  const accounts = useAccounts(household.id).data ?? [];
  // A holding with nothing in it yet starts on its first contribution.
  const [entry, setEntry] = useState<EntryTarget | null>(asset.contributedMinor === 0 ? { type: "contribution" } : null);
  const [panel, setPanel] = useState<"history" | "fees">("history");
  const hasFees = (fees.data?.length ?? 0) > 0;
  const showingFees = hasFees && panel === "fees";
  const isLiability = isLiabilityKind(asset.kind);
  const ratio = isLiability ? null : gainRatio(asset);
  const history = historyEntries(contributions.data ?? [], valuations.data ?? [], fees.data ?? []);

  return (
    <div className="flex flex-1 flex-col">
      <SheetBody
        footer={
          <Button type="button" variant="outline" onClick={onEdit}>
            Edit asset
          </Button>
        }
      >
        {/* The figures, then the history in full; while an entry is open, the figures and the form. */}
        <dl className="grid grid-cols-2 gap-x-6 gap-y-3">
          <div className="col-span-2 flex flex-col gap-1">
            <dt className="text-sm text-muted-foreground">{isLiability ? "Owed" : "Worth"}</dt>
            <dd className="text-2xl font-medium">
              <Money currency={currency}>{formatAsset(isLiability ? -asset.valueMinor : asset.valueMinor, currency)}</Money>
            </dd>
          </div>
          <div className="flex flex-col gap-1">
            <dt className="text-sm text-muted-foreground">{isLiability ? "Repaid" : "Put in"}</dt>
            <dd className="amount">{formatAsset(isLiability ? (asset.repaidMinor ?? 0) : asset.contributedMinor, currency)}</dd>
          </div>
          {isLiability && (
            <div className="flex flex-col gap-1">
              <dt className="text-sm text-muted-foreground">Borrowed</dt>
              <dd className="amount">{formatAsset(asset.borrowedMinor ?? 0, currency)}</dd>
            </div>
          )}
          {!isLiability && (
            <div className="flex flex-col gap-1">
              <dt className="text-sm text-muted-foreground">Gain{ratio !== null && ` · ${formatPercent(ratio)}`}</dt>
              <dd className="amount">{formatSigned(asset.gainMinor, currency)}</dd>
            </div>
          )}
          {!isLiability && asset.annualReturn !== null && (
            <div className="flex flex-col gap-1">
              <dt className="text-sm text-muted-foreground">Yearly return (money-weighted)</dt>
              <dd className="amount">{formatPercent(asset.annualReturn)}</dd>
            </div>
          )}
          {asset.feesMinor !== 0 && (
            <div className="flex flex-col gap-1">
              <dt className="text-sm text-muted-foreground">Fees paid</dt>
              <dd className="amount">{formatAsset(asset.feesMinor, currency)}</dd>
            </div>
          )}
        </dl>
        {/* The fee breakdown needs the room; the chart and note come back with the history. */}
        {!entry && !showingFees && <p className="text-xs text-muted-foreground">{valuationNote(asset)}</p>}
        {!entry && !showingFees && timeline.data && <HistoryChart points={timeline.data.points} currency={currency} height={110} />}

        {entry ? (
          <EntryForm
            key={entryKey(entry)}
            assetId={asset.id}
            security={asset.security}
            isLiability={isLiability}
            target={entry}
            onDone={() => setEntry(null)}
          />
        ) : (
          <div className="flex flex-wrap gap-2">
            <Button type="button" onClick={() => setEntry({ type: "contribution" })}>
              <PlusIcon /> Add contribution
            </Button>
            {/* Market prices value it; a hand-entered value would be ignored. */}
            {!asset.marketValued && (
              <Button type="button" variant="outline" onClick={() => setEntry({ type: "valuation" })}>
                {isLiability ? "Update balance" : "Update value"}
              </Button>
            )}
            <Button type="button" variant="outline" onClick={() => setEntry({ type: "fee" })}>
              Add fee
            </Button>
          </div>
        )}

        <div className="flex flex-col gap-3" hidden={entry !== null}>
          {hasFees ? (
            <Tabs value={panel} onValueChange={(v) => setPanel(v as "history" | "fees")}>
              <TabsList aria-label="Show">
                <TabsTrigger value="history">History</TabsTrigger>
                <TabsTrigger value="fees">Fees</TabsTrigger>
              </TabsList>
            </Tabs>
          ) : (
            <h3 className="text-sm font-medium">History</h3>
          )}
          <ErrorText error={contributions.error ?? valuations.error ?? fees.error} />
          {showingFees && (
            <FeeBreakdown fees={fees.data ?? []} points={timeline.data?.points ?? []} asset={asset} currency={currency} />
          )}
          {!showingFees && history.length === 0 && !contributions.isPending && (
            <p className="text-sm text-muted-foreground">Nothing recorded yet.</p>
          )}
          <ul className="-mx-2 flex flex-col" hidden={showingFees}>
            {history.map((h) => {
              const isValuation = h.type === "valuation";
              const item = h.type === "valuation" ? h.valuation : h.type === "fee" ? h.fee : h.contribution;
              const amount = h.type === "valuation" ? h.valuation.valueMinor : h.type === "fee" ? h.fee.amountMinor : h.contribution.amountMinor;
              const account = h.type === "contribution" ? accounts.find((a) => a.id === h.contribution.accountId)?.name : undefined;
              // Market prices value the holding now; a value entered by hand earlier stays listed but isn't used.
              const valuationLabel = isLiability ? "Owed" : "Worth";
              const label = isValuation
                ? asset.marketValued ? `${valuationLabel}, by hand · not used` : valuationLabel
                : h.type === "fee"
                  ? amount < 0 ? "Fee discount" : "Fee"
                  : isLiability
                    ? amount < 0 ? "Borrowed" : "Repaid"
                    : amount < 0 ? "Took out" : "Put in";
              // Fees are already in the gain: shown muted, as costs rather than money moving.
              const muted = isValuation || h.type === "fee";
              return (
                <li key={`${h.type}-${item.id}`}>
                  <button
                    type="button"
                    className="flex w-full items-baseline justify-between gap-3 rounded-md px-2 py-2 text-left text-sm outline-none hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/50"
                    onClick={() =>
                      setEntry(
                        h.type === "valuation"
                          ? { type: "valuation", valuation: h.valuation }
                          : h.type === "fee"
                            ? { type: "fee", fee: h.fee }
                            : { type: "contribution", contribution: h.contribution },
                      )
                    }
                  >
                    <span className="min-w-0">
                      <span className={cn("block", muted && "text-muted-foreground")}>{label}</span>
                      <span className="block text-xs text-muted-foreground">
                        {formatDay(h.date)}
                        {h.type === "contribution" && h.contribution.originalAmountMinor !== null && h.contribution.originalCurrency !== null &&
                          ` · ${formatAsset(h.contribution.originalAmountMinor, h.contribution.originalCurrency)} ${h.contribution.originalCurrency}`}
                        {h.type === "contribution" && h.contribution.quantity !== null && ` · ${formatShares(Math.abs(h.contribution.quantity))}`}
                        {account && ` · ${account}`}
                        {item.notes && ` · ${item.notes}`}
                      </span>
                    </span>
                    <span className={cn("amount", muted && "text-muted-foreground")}>
                      {h.type === "contribution" ? formatSigned(amount, currency) : formatAsset(amount, currency)}
                    </span>
                  </button>
                </li>
              );
            })}
          </ul>
        </div>
      </SheetBody>
    </div>
  );
}

const percent = (ratio: number | null, digits = 2) => (ratio === null ? "–" : `${(ratio * 100).toFixed(digits)}%`);

/** What the holding's fees came to: per year against its worth then, and overall against what went in and came out. */
function FeeBreakdown({
  fees,
  points,
  asset,
  currency,
}: {
  fees: Fee[];
  points: HistoryPoint[];
  asset: Asset;
  currency: string;
}) {
  const years = feesByYear(fees, points);
  const summary = feeSummary(fees, points, asset);
  return (
    <div className="flex flex-col gap-4">
      <dl className="grid grid-cols-2 gap-x-6 gap-y-3 text-sm">
        <div className="flex flex-col gap-0.5">
          <dt className="text-muted-foreground">Paid in all</dt>
          <dd>
            <Money currency={currency}>{formatAsset(summary.feesMinor, currency)}</Money>
          </dd>
        </div>
        <div className="flex flex-col gap-0.5">
          <dt className="text-muted-foreground">A year, of worth</dt>
          <dd className="amount">{percent(summary.yearlyRate)}</dd>
        </div>
        <div className="flex flex-col gap-0.5">
          <dt className="text-muted-foreground">Of money put in</dt>
          <dd className="amount">{percent(summary.ofContributed)}</dd>
        </div>
        <div className="flex flex-col gap-0.5">
          <dt className="text-muted-foreground">Of the gain before fees</dt>
          <dd className="amount">{percent(summary.ofGainBeforeFees, 1)}</dd>
        </div>
      </dl>
      <Table>
        <TableHeader>
          <TableRow className="hover:bg-transparent">
            <TableHead className="pl-0 first:pl-0">Year</TableHead>
            <TableHead className="text-right">Fees</TableHead>
            <TableHead className="text-right">Avg worth</TableHead>
            <TableHead className="pr-0 text-right last:pr-0" title="Fees as a share of average worth, scaled to a full year">
              A year
            </TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {years.map((y) => (
            <TableRow key={y.year}>
              <TableCell className="pl-0 first:pl-0">{y.year}</TableCell>
              <TableCell className="amount text-right">{formatAsset(y.feesMinor, currency)}</TableCell>
              <TableCell className="amount text-right">
                {y.averageWorthMinor === null ? "–" : formatAsset(y.averageWorthMinor, currency)}
              </TableCell>
              <TableCell className="amount pr-0 text-right last:pr-0">{percent(y.yearlyRate)}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
      <p className="text-xs text-muted-foreground">
        Fees are already taken out of the worth, so they don't change the gain shown above. Part years are scaled to a full
        year from the months that charged a fee.
      </p>
    </div>
  );
}

function valuationNote(i: Asset): string {
  if (i.valueSource === "market" && i.security && i.shares !== null && i.valuedOn) {
    return `${formatShares(i.shares)} of ${i.security.name} at ${formatClose(i.security)}, the close on ${formatDay(i.valuedOn)}, converted at the ČNB rate. Anything put in without shares counts at cost.`;
  }
  if (i.security) return `Follows ${i.security.symbol}. Add the shares to its contributions to value it at the market.`;
  if (i.valueSource === "bank" && i.valuedOn) {
    return `Valued from the linked account's bank feed balances as of ${formatDay(i.valuedOn)}, converted at the ČNB rate.`;
  }
  if (i.bankAvailableOnly) {
    return "The linked bank only reports available credit, which includes the unused credit line, so hand valuations are used instead.";
  }
  if (i.accountId) return "Linked to an account, waiting for a bank balance reading. Hand valuations fill in until one arrives.";
  if (i.valuedOn) return `Last valued ${formatDay(i.valuedOn)}; anything put in since counts at cost.`;
  return "Not valued yet, so it's worth what went in. Update the value to see growth.";
}

const entryKey = (entry: EntryTarget) =>
  entry.type === "valuation"
    ? `v-${entry.valuation?.id ?? "new"}`
    : entry.type === "fee"
      ? `f-${entry.fee?.id ?? "new"}`
      : `c-${entry.contribution?.id ?? "new"}`;
