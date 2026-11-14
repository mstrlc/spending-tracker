import { useState } from "react";
import {
  useAccounts,
  useContributions,
  useFees,
  useInvestmentHistory,
  useInvestments,
  useMembers,
  useValuations,
} from "../api/hooks";
import type { Fee, HistoryPoint, Investment, SecurityQuote } from "../api/types";
import { useHousehold } from "../households/HouseholdPages";
import { Plus } from "../icons";
import { formatDay } from "../lib/dates";
import { ErrorText, PageHeader, Sheet, SkeletonRows } from "../ui";
import { EntryForm, type EntryTarget } from "./EntryForm";
import { HistoryChart } from "./HistoryChart";
import { HoldingForm } from "./HoldingForm";
import {
  feeSummary,
  feesByYear,
  formatInvestment,
  formatPercent,
  formatSigned,
  gainRatio,
  historyEntries,
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
function valuedBy(i: Investment): string {
  if (i.marketValued && i.security && i.valuedOn) return `${i.security.symbol} · ${formatClose(i.security)} on ${formatDay(i.valuedOn)}`;
  if (i.security) return `${i.security.symbol} · no shares recorded`;
  return i.valuedOn ? `valued ${formatDay(i.valuedOn)}` : "not valued yet";
}

type Editor = { mode: "closed" } | { mode: "add" } | { mode: "edit"; investment: Investment } | { mode: "view"; id: string };

export function InvestmentsPage() {
  const household = useHousehold();
  const investments = useInvestments(household.id);
  const history = useInvestmentHistory(household.id);
  const members = useMembers(household.id).data ?? [];
  const [editor, setEditor] = useState<Editor>({ mode: "closed" });
  const [flashId, setFlashId] = useState<string | null>(null);

  const rows = investments.data ?? [];
  const totals = portfolioTotals(rows);
  const ratio = gainRatio(totals);
  const showOwner = members.length > 1;
  const viewing = editor.mode === "view" ? rows.find((i) => i.id === editor.id) : undefined;
  const openAdd = () => setEditor({ mode: "add" });
  const close = () => setEditor({ mode: "closed" });
  const { currency } = household;

  return (
    <>
      <PageHeader title="Investments">
        <button onClick={openAdd}>
          <Plus /> Add investment
        </button>
      </PageHeader>

      {rows.length > 0 && (
        <div className="overview">
          <dl className="summary">
            <div>
              <dt>Worth</dt>
              <dd className="summary-primary">
                {formatInvestment(totals.valueMinor, currency)}
                <span className="currency">{currency}</span>
              </dd>
            </div>
            <div>
              <dt>Put in</dt>
              <dd>
                {formatInvestment(totals.contributedMinor, currency)}
                <span className="currency">{currency}</span>
              </dd>
            </div>
            <div>
              <dt>Gain{ratio !== null && ` · ${formatPercent(ratio)}`}</dt>
              <dd>
                {formatSigned(totals.gainMinor, currency)}
                <span className="currency">{currency}</span>
              </dd>
            </div>
            {totals.feesMinor !== 0 && (
              <div>
                <dt>
                  Fees paid
                  {totals.contributedMinor > 0 && ` · ${((totals.feesMinor / totals.contributedMinor) * 100).toFixed(1)}% of put in`}
                </dt>
                <dd>
                  {formatInvestment(totals.feesMinor, currency)}
                  <span className="currency">{currency}</span>
                </dd>
              </div>
            )}
          </dl>
          {history.data && <HistoryChart points={history.data.points} currency={currency} />}
        </div>
      )}

      <section className="surface">
        <ErrorText error={investments.error} />
        {investments.isPending && <SkeletonRows />}
        {investments.data && rows.length === 0 && (
          <div className="empty">
            <p className="empty-title">No investments yet</p>
            <p className="muted">Add a fund or stock, then record what you put in and what it's worth now and then.</p>
            <button onClick={openAdd}>
              <Plus /> Add investment
            </button>
          </div>
        )}
        {rows.length > 0 && (
          <div className="table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Investment</th>
                  {showOwner && <th className="hide-narrow">Held by</th>}
                  <th className="num hide-narrow">Put in</th>
                  <th className="num">Worth ({currency})</th>
                  <th className="num hide-narrow">Gain</th>
                  <th className="num">%</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((i) => {
                  const r = gainRatio(i);
                  return (
                    <tr
                      key={i.id}
                      className={i.id === flashId ? "clickable flash" : "clickable"}
                      onClick={() => setEditor({ mode: "view", id: i.id })}
                      onAnimationEnd={() => setFlashId(null)}
                    >
                      <td>
                        <button
                          className="row-link"
                          onClick={(event) => {
                            event.stopPropagation();
                            setEditor({ mode: "view", id: i.id });
                          }}
                        >
                          {i.name}
                        </button>
                        <div className="muted small">
                          {kindLabel(i.kind)} · {valuedBy(i)}
                        </div>
                      </td>
                      {showOwner && (
                        <td className="hide-narrow muted">
                          {members.find((m) => m.userId === i.ownerUserId)?.displayName ?? "Shared"}
                        </td>
                      )}
                      <td className="num hide-narrow">{formatInvestment(i.contributedMinor, currency)}</td>
                      <td className="num">{formatInvestment(i.valueMinor, currency)}</td>
                      <td className="num hide-narrow">{formatSigned(i.gainMinor, currency)}</td>
                      <td className="num">{r === null ? "–" : formatPercent(r)}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <Sheet
        open={editor.mode === "add" || editor.mode === "edit"}
        onClose={close}
        title={editor.mode === "edit" ? "Edit investment" : "Add investment"}
      >
        <HoldingForm
          key={editor.mode === "edit" ? editor.investment.id : "new"}
          investment={editor.mode === "edit" ? editor.investment : undefined}
          onSaved={(saved) => {
            setFlashId(saved.id);
            // A new holding opens straight away, ready for its first contribution.
            setEditor({ mode: "view", id: saved.id });
          }}
          onDeleted={close}
          onCancel={() => setEditor(editor.mode === "edit" ? { mode: "view", id: editor.investment.id } : { mode: "closed" })}
        />
      </Sheet>

      <Sheet open={viewing !== undefined} onClose={close} title={viewing?.name ?? ""}>
        {viewing && <HoldingDetail key={viewing.id} investment={viewing} onEdit={() => setEditor({ mode: "edit", investment: viewing })} />}
      </Sheet>
    </>
  );
}

/** One holding: where it stands, a form for the next contribution or value, and its history. */
function HoldingDetail({ investment, onEdit }: { investment: Investment; onEdit: () => void }) {
  const household = useHousehold();
  const { currency } = household;
  const contributions = useContributions(household.id, investment.id);
  const valuations = useValuations(household.id, investment.id);
  const fees = useFees(household.id, investment.id);
  const timeline = useInvestmentHistory(household.id, investment.id);
  const accounts = useAccounts(household.id).data ?? [];
  // A holding with nothing in it yet starts on its first contribution.
  const [entry, setEntry] = useState<EntryTarget | null>(investment.contributedMinor === 0 ? { type: "contribution" } : null);
  const [panel, setPanel] = useState<"history" | "fees">("history");
  const hasFees = (fees.data?.length ?? 0) > 0;
  const showingFees = hasFees && panel === "fees";
  const ratio = gainRatio(investment);
  const history = historyEntries(contributions.data ?? [], valuations.data ?? [], fees.data ?? []);

  return (
    <div className="sheet-form">
      {/* The figures, then the history in full; while an entry is open, the figures and the form. */}
      <div className="sheet-fields holding-detail">
        <dl className="summary">
          <div>
            <dt>Worth</dt>
            <dd className="summary-primary">
              {formatInvestment(investment.valueMinor, currency)}
              <span className="currency">{currency}</span>
            </dd>
          </div>
          <div>
            <dt>Put in</dt>
            <dd>{formatInvestment(investment.contributedMinor, currency)}</dd>
          </div>
          <div>
            <dt>Gain{ratio !== null && ` · ${formatPercent(ratio)}`}</dt>
            <dd>{formatSigned(investment.gainMinor, currency)}</dd>
          </div>
          {investment.feesMinor !== 0 && (
            <div>
              <dt>Fees paid</dt>
              <dd>{formatInvestment(investment.feesMinor, currency)}</dd>
            </div>
          )}
        </dl>
        {/* The fee breakdown needs the room; the chart and note come back with the history. */}
        {!entry && !showingFees && <p className="muted small">{valuationNote(investment)}</p>}
        {!entry && !showingFees && timeline.data && <HistoryChart points={timeline.data.points} currency={currency} height={110} />}

        {entry ? (
          <EntryForm
            key={entryKey(entry)}
            investmentId={investment.id}
            security={investment.security}
            target={entry}
            onDone={() => setEntry(null)}
          />
        ) : (
          <div className="entry-buttons">
            <button type="button" onClick={() => setEntry({ type: "contribution" })}>
              <Plus /> Add contribution
            </button>
            {/* Market prices value it; a hand-entered value would be ignored. */}
            {!investment.marketValued && (
              <button type="button" className="secondary" onClick={() => setEntry({ type: "valuation" })}>
                Update value
              </button>
            )}
            <button type="button" className="secondary" onClick={() => setEntry({ type: "fee" })}>
              Add fee
            </button>
          </div>
        )}

        <div className="holding-history" hidden={entry !== null}>
          {hasFees ? (
            <div className="panel-tabs" role="tablist" aria-label="Show">
              {(["history", "fees"] as const).map((p) => (
                <button key={p} type="button" role="tab" aria-selected={panel === p} onClick={() => setPanel(p)}>
                  {p === "history" ? "History" : "Fees"}
                </button>
              ))}
            </div>
          ) : (
            <h3 className="sheet-section-title">History</h3>
          )}
          <ErrorText error={contributions.error ?? valuations.error ?? fees.error} />
          {showingFees && (
            <FeeBreakdown fees={fees.data ?? []} points={timeline.data?.points ?? []} investment={investment} currency={currency} />
          )}
          {!showingFees && history.length === 0 && !contributions.isPending && (
            <p className="muted small">Nothing recorded yet.</p>
          )}
          <ul className="card-list history-list" hidden={showingFees}>
            {history.map((h) => {
              const isValuation = h.type === "valuation";
              const item = h.type === "valuation" ? h.valuation : h.type === "fee" ? h.fee : h.contribution;
              const amount = h.type === "valuation" ? h.valuation.valueMinor : h.type === "fee" ? h.fee.amountMinor : h.contribution.amountMinor;
              const account = h.type === "contribution" ? accounts.find((a) => a.id === h.contribution.accountId)?.name : undefined;
              // Market prices value the holding now; a value entered by hand earlier stays listed but isn't used.
              const label = isValuation
                ? investment.marketValued ? "Worth, by hand · not used" : "Worth"
                : h.type === "fee"
                  ? amount < 0 ? "Fee discount" : "Fee"
                  : amount < 0 ? "Took out" : "Put in";
              // Fees are already in the gain: shown muted, as costs rather than money moving.
              const muted = isValuation || h.type === "fee";
              return (
                <li key={`${h.type}-${item.id}`}>
                  <button
                    type="button"
                    className="history-item"
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
                    <span>
                      <span className={muted ? "muted" : undefined}>{label}</span>
                      <span className="muted small block">
                        {formatDay(h.date)}
                        {h.type === "contribution" && h.contribution.quantity !== null && ` · ${formatShares(Math.abs(h.contribution.quantity))}`}
                        {account && ` · ${account}`}
                        {item.notes && ` · ${item.notes}`}
                      </span>
                    </span>
                    <span className={muted ? "num muted" : "num"}>
                      {h.type === "contribution" ? formatSigned(amount, currency) : formatInvestment(amount, currency)}
                    </span>
                  </button>
                </li>
              );
            })}
          </ul>
        </div>
      </div>
      <div className="sheet-footer">
        <button type="button" className="secondary" onClick={onEdit}>
          Edit investment
        </button>
      </div>
    </div>
  );
}

const percent = (ratio: number | null, digits = 2) => (ratio === null ? "–" : `${(ratio * 100).toFixed(digits)}%`);

/** What the holding's fees came to: per year against its worth then, and overall against what went in and came out. */
function FeeBreakdown({
  fees,
  points,
  investment,
  currency,
}: {
  fees: Fee[];
  points: HistoryPoint[];
  investment: Investment;
  currency: string;
}) {
  const years = feesByYear(fees, points);
  const summary = feeSummary(fees, points, investment);
  return (
    <div className="fee-breakdown">
      <dl className="fee-facts">
        <div>
          <dt>Paid in all</dt>
          <dd>
            {formatInvestment(summary.feesMinor, currency)} <span className="currency">{currency}</span>
          </dd>
        </div>
        <div>
          <dt>A year, of worth</dt>
          <dd>{percent(summary.yearlyRate)}</dd>
        </div>
        <div>
          <dt>Of money put in</dt>
          <dd>{percent(summary.ofContributed)}</dd>
        </div>
        <div>
          <dt>Of the gain before fees</dt>
          <dd>{percent(summary.ofGainBeforeFees, 1)}</dd>
        </div>
      </dl>
      <table className="data-table fee-years">
        <thead>
          <tr>
            <th>Year</th>
            <th className="num">Fees</th>
            <th className="num">Avg worth</th>
            <th className="num" title="Fees as a share of average worth, scaled to a full year">A year</th>
          </tr>
        </thead>
        <tbody>
          {years.map((y) => (
            <tr key={y.year}>
              <td>{y.year}</td>
              <td className="num">{formatInvestment(y.feesMinor, currency)}</td>
              <td className="num">{y.averageWorthMinor === null ? "–" : formatInvestment(y.averageWorthMinor, currency)}</td>
              <td className="num">{percent(y.yearlyRate)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="muted small">
        Fees are already taken out of the worth, so they don't change the gain shown above. Part years are scaled to a full
        year from the months that charged a fee.
      </p>
    </div>
  );
}

function valuationNote(i: Investment): string {
  if (i.marketValued && i.security && i.shares !== null && i.valuedOn) {
    return `${formatShares(i.shares)} of ${i.security.name} at ${formatClose(i.security)}, the close on ${formatDay(i.valuedOn)}, converted at the ČNB rate. Anything put in without shares counts at cost.`;
  }
  if (i.security) return `Follows ${i.security.symbol}. Add the shares to its contributions to value it at the market.`;
  if (i.valuedOn) return `Last valued ${formatDay(i.valuedOn)}; anything put in since counts at cost.`;
  return "Not valued yet, so it's worth what went in. Update the value to see growth.";
}

const entryKey = (entry: EntryTarget) =>
  entry.type === "valuation"
    ? `v-${entry.valuation?.id ?? "new"}`
    : entry.type === "fee"
      ? `f-${entry.fee?.id ?? "new"}`
      : `c-${entry.contribution?.id ?? "new"}`;
