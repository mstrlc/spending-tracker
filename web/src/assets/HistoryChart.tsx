import { useEffect, useId, useState, type ReactNode } from "react";
import type { HistoryPoint } from "../api/types";
import { gainClass } from "../chips";
import { fractionDigits } from "../lib/money";
import { cn } from "@/lib/utils";
import { formatAsset, formatPercent, formatSigned } from "./holdings";

const PAD = { top: 12, right: 10, bottom: 22, left: 8 };

/** Days since the epoch, so points sit at their real distance in time. */
const day = (iso: string) => Date.parse(`${iso}T00:00:00Z`) / 86_400_000;

/** "31 Mar 2026" for the tooltip. */
const fullDate = (iso: string) =>
  new Date(`${iso}T00:00:00`).toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" });

/** "Mar 26" for an x-axis tick. */
const tick = (iso: string) =>
  new Date(`${iso}T00:00:00`).toLocaleDateString(undefined, { month: "short", year: "2-digit" });

/** A rounded-up axis maximum: 1, 2 or 5 times a power of ten. */
export function niceMax(value: number): number {
  if (value <= 0) return 1;
  const power = 10 ** Math.floor(Math.log10(value));
  return ([1, 2, 5, 10].find((m) => m * power >= value) ?? 10) * power;
}

/** Gridline values covering [lo, hi] in about [count] steps of 1, 2 or 5 times a power of ten. */
export function niceTicks(lo: number, hi: number, count = 4): number[] {
  if (hi <= lo) hi = lo + Math.max(1, Math.abs(lo) * 0.1);
  const step = niceMax((hi - lo) / count);
  const first = Math.floor(lo / step) * step;
  const last = Math.ceil(hi / step) * step;
  const ticks: number[] = [];
  for (let v = first; v <= last + step / 2; v += step) ticks.push(v);
  return ticks;
}

export type Range = "6m" | "ytd" | "1y" | "3y" | "5y" | "all";

const RANGES: { value: Range; label: string; months?: number }[] = [
  { value: "6m", label: "6M", months: 6 },
  { value: "ytd", label: "YTD" },
  { value: "1y", label: "1Y", months: 12 },
  { value: "3y", label: "3Y", months: 36 },
  { value: "5y", label: "5Y", months: 60 },
  { value: "all", label: "All" },
];

/** The ISO day a range starts on, counted back from [lastIso]; null for everything. */
function rangeStart(range: Range, lastIso: string): string | null {
  const end = new Date(`${lastIso}T00:00:00Z`);
  if (range === "all") return null;
  if (range === "ytd") return `${end.getUTCFullYear()}-01-01`;
  const months = RANGES.find((r) => r.value === range)!.months!;
  const start = new Date(Date.UTC(end.getUTCFullYear(), end.getUTCMonth() - months, end.getUTCDate()));
  return start.toISOString().slice(0, 10);
}

/**
 * The points of a range: those on or after its start, plus the last one before it, so the line begins at what was
 * there when the range opened.
 */
export function pointsInRange(points: HistoryPoint[], range: Range): HistoryPoint[] {
  if (points.length === 0) return points;
  const start = rangeStart(range, points[points.length - 1].date);
  if (!start) return points;
  const firstInside = points.findIndex((p) => p.date >= start);
  if (firstInside < 0) return points.slice(-1);
  return points.slice(Math.max(0, firstInside - 1));
}

/** The ranges worth offering: each one shorter than the history, then All. */
function rangesFor(points: HistoryPoint[]): typeof RANGES {
  if (points.length < 2) return [];
  const first = points[0].date;
  const offered = RANGES.filter((r) => {
    if (r.value === "all") return true;
    const start = rangeStart(r.value, points[points.length - 1].date)!;
    // A range only helps when it cuts something off and still has a line to draw.
    return start > first && pointsInRange(points, r.value).length >= 2;
  });
  return offered.length > 1 ? offered : [];
}

const compact = (minor: number, currency: string) =>
  new Intl.NumberFormat(undefined, { notation: "compact", maximumFractionDigits: 1 }).format(minor / 10 ** fractionDigits(currency));

/** Worth and put in at a point, and what the difference makes of it. */
function gainAt(p: HistoryPoint) {
  const gain = p.valueMinor - p.contributedMinor;
  return { gain, ratio: p.contributedMinor > 0 ? gain / p.contributedMinor : null };
}

/**
 * Worth against money put in over time, with the gap between them shaded green (gain) or red (loss). Where the
 * points know what the accounts held, that stacks on top of worth as a band whose top edge is net worth.
 * Ranges trim it to the last months or years; the figures above sum up what changed over the range.
 * Hover (or tap) anywhere to read the figures of the nearest point.
 */
export function HistoryChart({
  points: allPoints,
  currency,
  height = 200,
  compactHeader = false,
}: {
  points: HistoryPoint[];
  currency: string;
  height?: number;
  /** In the side panel: smaller figures and range buttons. */
  compactHeader?: boolean;
}) {
  const HEIGHT = height;
  const ids = useId();
  // State rather than a ref: the plot mounts only once there are points, and the width must follow it then.
  const [plot, setPlot] = useState<HTMLDivElement | null>(null);
  const [width, setWidth] = useState(0);
  const [hover, setHover] = useState<number | null>(null);
  // Three years by default; a shorter history falls back to All.
  const [range, setRange] = useState<Range>("3y");

  useEffect(() => {
    if (!plot) return;
    const observer = new ResizeObserver(([entry]) => setWidth(entry.contentRect.width));
    observer.observe(plot);
    return () => observer.disconnect();
  }, [plot]);

  const ranges = rangesFor(allPoints);
  const active = ranges.some((r) => r.value === range) ? range : "all";
  const points = pointsInRange(allPoints, active);
  if (points.length < 2) return null;

  const firstPoint = points[0];
  const last = points[points.length - 1];
  const first = day(firstPoint.date);
  const span = Math.max(1, day(last.date) - first);
  // Net worth from the first point on which, and on every one after it, the accounts' balances are known.
  const net = (p: HistoryPoint) => p.valueMinor + (p.accountsMinor ?? 0);
  let knownFrom = points.length;
  while (knownFrom > 0 && points[knownFrom - 1].accountsMinor != null) knownFrom--;
  const netPoints = points.slice(knownFrom);
  const hasNet = netPoints.length > 0 && netPoints.some((p) => p.accountsMinor !== 0);
  const allValues = [...points.flatMap((p) => [p.valueMinor, p.contributedMinor]), ...(hasNet ? netPoints.map(net) : [])];
  const lo = Math.min(...allValues);
  const hi = Math.max(...allValues);
  // From zero over the whole history, so the lines' sizes compare; a shorter range zooms in on what moved.
  const gridValues = active === "all" ? niceTicks(Math.min(0, lo), Math.max(0, hi)) : niceTicks(lo, hi);
  const min = gridValues[0];
  const max = gridValues[gridValues.length - 1];
  const valueSpan = Math.max(1, max - min);
  const padRight = hasNet ? 16 : PAD.right;
  const plotWidth = Math.max(0, width - PAD.left - padRight);
  const plotBottom = HEIGHT - PAD.bottom;
  const x = (p: HistoryPoint) => PAD.left + ((day(p.date) - first) / span) * plotWidth;
  const y = (minor: number) => PAD.top + (1 - (minor - min) / valueSpan) * (plotBottom - PAD.top);
  const line = (pick: (p: HistoryPoint) => number, list = points) =>
    list.map((p, i) => `${i === 0 ? "M" : "L"}${x(p).toFixed(1)},${y(pick(p)).toFixed(1)}`).join("");
  const worthLine = line((p) => p.valueMinor);
  const putInLine = line((p) => p.contributedMinor);
  const backwards = [...points].reverse();
  const putInBack = backwards.map((p) => `L${x(p).toFixed(1)},${y(p.contributedMinor).toFixed(1)}`).join("");
  // The gap between the lines, then clipped into the part above put in (gain) and below it (loss).
  const band = `${worthLine}${putInBack}Z`;
  const right = x(last).toFixed(1);
  const left = x(firstPoint).toFixed(1);
  const aboveClip = `${putInLine}L${right},${PAD.top - 2}L${left},${PAD.top - 2}Z`;
  const belowClip = `${putInLine}L${right},${plotBottom}L${left},${plotBottom}Z`;
  const worthArea = `${worthLine}L${right},${plotBottom}L${left},${plotBottom}Z`;
  // The accounts band: up along net worth, back along worth.
  const netLine = hasNet ? line(net, netPoints) : "";
  const accountsBand = hasNet
    ? `${netLine}${[...netPoints].reverse().map((p) => `L${x(p).toFixed(1)},${y(p.valueMinor).toFixed(1)}`).join("")}Z`
    : "";
  const netStart = hasNet ? netPoints[0] : null;
  // About one tick per 110px, always including the first and last point.
  const tickCount = Math.max(2, Math.min(points.length, Math.floor(plotWidth / 110)));
  const ticks = [...new Set(Array.from({ length: tickCount }, (_, i) => Math.round((i * (points.length - 1)) / (tickCount - 1))))];
  const shown = hover === null ? null : points[hover];
  const previous = hover === null || hover === 0 ? null : points[hover - 1];

  function track(clientX: number) {
    const leftEdge = plot?.getBoundingClientRect().left ?? 0;
    const at = clientX - leftEdge;
    let nearest = 0;
    points.forEach((p, i) => {
      if (Math.abs(x(p) - at) < Math.abs(x(points[nearest]) - at)) nearest = i;
    });
    setHover(nearest);
  }

  // What the range did: the change in worth, split into money put in and what the market (or the bank) added.
  const change = last.valueMinor - firstPoint.valueMinor;
  const putIn = last.contributedMinor - firstPoint.contributedMinor;
  const growth = change - putIn;
  const lastGain = gainAt(last);
  const netChange = netStart && netStart !== last ? net(last) - net(netStart) : null;
  const summary = `Worth ${formatAsset(last.valueMinor, currency)} against ${formatAsset(last.contributedMinor, currency)} ${currency} put in, from ${fullDate(firstPoint.date)}`;
  const tooltipLeft = shown ? x(shown) : 0;
  const flip = tooltipLeft > width / 2;

  return (
    <figure className="flex flex-col gap-3">
      <div className="flex flex-wrap items-end justify-between gap-x-6 gap-y-3">
        <dl className={cn("flex flex-wrap gap-x-6 gap-y-2", compactHeader ? "text-xs" : "text-sm")}>
          <div className="flex flex-col gap-0.5">
            <dt className="text-muted-foreground">Assets since {fullDate(firstPoint.date)}</dt>
            <dd className={cn("amount font-medium", compactHeader ? "text-sm" : "text-base", gainClass(change))}>
              {formatSigned(change, currency)}
            </dd>
          </div>
          <div className="flex flex-col gap-0.5">
            <dt className="flex items-center gap-1.5 text-muted-foreground">
              <span className="w-3 border-t-2 border-dashed border-muted-foreground" aria-hidden="true" />
              Put in
            </dt>
            <dd className={cn("amount font-medium", compactHeader ? "text-sm" : "text-base")}>{formatSigned(putIn, currency)}</dd>
          </div>
          <div className="flex flex-col gap-0.5">
            <dt className="flex items-center gap-1.5 text-muted-foreground">
              <span className="size-2.5 rounded-sm bg-(--tone-income)/40" aria-hidden="true" />
              Growth
            </dt>
            <dd className={cn("amount font-medium", compactHeader ? "text-sm" : "text-base", gainClass(growth))}>
              {formatSigned(growth, currency)}
            </dd>
          </div>
          {hasNet && (
            <div className="flex flex-col gap-0.5">
              <dt className="flex items-center gap-1.5 text-muted-foreground">
                <span className="size-2.5 rounded-sm bg-(--chart-accounts)/50" aria-hidden="true" />
                In accounts
              </dt>
              <dd className={cn("amount font-medium", compactHeader ? "text-sm" : "text-base")}>{formatAsset(last.accountsMinor ?? 0, currency)}</dd>
            </div>
          )}
          {hasNet && (
            <div className="flex flex-col gap-0.5">
              <dt className="flex items-center gap-1.5 text-muted-foreground">
                <span className="w-3 border-t-2 border-(--chart-accounts)" aria-hidden="true" />
                Net worth
              </dt>
              <dd className={cn("amount font-medium", compactHeader ? "text-sm" : "text-base")}>
                {formatAsset(net(last), currency)}
                {netChange !== null && netStart && (
                  <span className={cn("ml-1.5 text-xs", gainClass(netChange))} title={`Since ${fullDate(netStart.date)}`}>
                    {formatSigned(netChange, currency)} since {tick(netStart.date)}
                  </span>
                )}
              </dd>
            </div>
          )}
          {!compactHeader && lastGain.ratio !== null && (
            <div className="flex flex-col gap-0.5">
              <dt className="text-muted-foreground">Gain on put in</dt>
              <dd className={cn("amount text-base font-medium", gainClass(lastGain.gain))}>
                {formatSigned(lastGain.gain, currency)}
                <span className="ml-1.5 text-xs">{formatPercent(lastGain.ratio)}</span>
              </dd>
            </div>
          )}
        </dl>
        {ranges.length > 0 && (
          <div role="group" aria-label="Time range" className="flex rounded-lg bg-muted p-0.5">
            {ranges.map((r) => (
              <button
                key={r.value}
                type="button"
                aria-pressed={active === r.value}
                onClick={() => {
                  setRange(r.value);
                  setHover(null);
                }}
                className={cn(
                  "rounded-md px-2.5 py-1 text-xs font-medium text-muted-foreground transition-colors outline-none hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring/50",
                  compactHeader && "px-2",
                  active === r.value && "bg-background text-foreground shadow-sm",
                )}
              >
                {r.label}
              </button>
            ))}
          </div>
        )}
      </div>
      <div
        ref={setPlot}
        className="relative touch-pan-y select-none"
        style={{ height: HEIGHT }}
        role="img"
        aria-label={summary}
        onPointerMove={(e) => track(e.clientX)}
        onPointerDown={(e) => track(e.clientX)}
        onPointerLeave={() => setHover(null)}
      >
        {width > 0 && (
          <svg width={width} height={HEIGHT} aria-hidden="true">
            <defs>
              <linearGradient id={`${ids}-worth`} x1="0" x2="0" y1="0" y2="1">
                <stop offset="0%" stopColor="var(--chart-worth)" stopOpacity={0.22} />
                <stop offset="100%" stopColor="var(--chart-worth)" stopOpacity={0} />
              </linearGradient>
              <clipPath id={`${ids}-above`}>
                <path d={aboveClip} />
              </clipPath>
              <clipPath id={`${ids}-below`}>
                <path d={belowClip} />
              </clipPath>
            </defs>
            {gridValues.map((v) => (
              <g key={v}>
                <line
                  className={v === 0 ? "stroke-muted-foreground/40" : "stroke-border"}
                  strokeDasharray={v === 0 ? undefined : "2 4"}
                  x1={PAD.left}
                  x2={width - padRight}
                  y1={y(v)}
                  y2={y(v)}
                />
                {v !== min && (
                  <text className="fill-muted-foreground text-[10px]" x={PAD.left} y={y(v) - 4}>
                    {compact(v, currency)}
                  </text>
                )}
              </g>
            ))}
            <path fill={`url(#${ids}-worth)`} d={worthArea} />
            <path className="fill-(--tone-income) opacity-25" clipPath={`url(#${ids}-above)`} d={band} />
            <path className="fill-destructive opacity-25" clipPath={`url(#${ids}-below)`} d={band} />
            <path className="fill-none stroke-muted-foreground [stroke-dasharray:4_3]" strokeWidth={1.5} d={putInLine} />
            <path className="fill-none stroke-(--chart-worth)" strokeWidth={2.25} strokeLinejoin="round" d={worthLine} />
            {hasNet && netStart && (
              <g>
                {netPoints.length > 1 ? (
                  <>
                    <path className="fill-(--chart-accounts) opacity-30" d={accountsBand} />
                    <path className="fill-none stroke-(--chart-accounts)" strokeWidth={2.25} strokeLinejoin="round" d={netLine} />
                  </>
                ) : (
                  // Only today is known: a bar from worth up to net worth.
                  <line
                    className="stroke-(--chart-accounts)"
                    strokeWidth={6}
                    strokeLinecap="round"
                    x1={x(last)}
                    x2={x(last)}
                    y1={y(last.valueMinor)}
                    y2={y(net(last))}
                  />
                )}
                {/* Where the accounts' balances begin, unless that's the start of the range. */}
                {knownFrom > 0 && (
                  <>
                    <line
                      className="stroke-(--chart-accounts)/60"
                      strokeDasharray="3 3"
                      x1={x(netStart)}
                      x2={x(netStart)}
                      y1={PAD.top}
                      y2={plotBottom}
                    />
                    {!shown && (
                      <text
                        className="fill-muted-foreground text-[10px]"
                        x={x(netStart) - 6}
                        y={PAD.top + 8}
                        textAnchor="end"
                      >
                        Accounts from {tick(netStart.date)}
                      </text>
                    )}
                  </>
                )}
                {!shown && (
                  <>
                    <circle className="fill-(--chart-accounts) stroke-background" strokeWidth={2} cx={x(last)} cy={y(net(last))} r={4.5} />
                    <text className="fill-(--chart-accounts) text-[11px] font-medium" x={x(last) - 10} y={y(net(last)) - 8} textAnchor="end">
                      Net worth {compact(net(last), currency)}
                    </text>
                  </>
                )}
              </g>
            )}
            {!shown && <circle className="fill-(--chart-worth) stroke-background" strokeWidth={2} cx={x(last)} cy={y(last.valueMinor)} r={4} />}
            {ticks.map((i) => (
              <text
                key={i}
                className="fill-muted-foreground text-[11px]"
                x={x(points[i])}
                y={HEIGHT - 6}
                textAnchor={i === 0 ? "start" : i === points.length - 1 ? "end" : "middle"}
              >
                {tick(points[i].date)}
              </text>
            ))}
            {shown && (
              <g>
                <line className="stroke-muted-foreground/50" x1={x(shown)} x2={x(shown)} y1={PAD.top} y2={plotBottom} />
                <circle className="fill-background stroke-muted-foreground" strokeWidth={2} cx={x(shown)} cy={y(shown.contributedMinor)} r={3.5} />
                <circle className="fill-(--chart-worth) stroke-background" strokeWidth={2} cx={x(shown)} cy={y(shown.valueMinor)} r={4.5} />
                {hasNet && shown.accountsMinor != null && points.indexOf(shown) >= knownFrom && (
                  <circle className="fill-(--chart-accounts) stroke-background" strokeWidth={2} cx={x(shown)} cy={y(net(shown))} r={4.5} />
                )}
              </g>
            )}
          </svg>
        )}
        {shown && (
          <div
            className="pointer-events-none absolute top-1 z-10 min-w-44 rounded-lg border bg-popover px-3 py-2 text-xs text-popover-foreground shadow-md"
            style={flip ? { right: width - tooltipLeft + 12 } : { left: tooltipLeft + 12 }}
          >
            <div className="mb-1.5 font-medium">{fullDate(shown.date)}</div>
            <TooltipRow swatch={<span className="size-2 rounded-full bg-(--chart-worth)" />} label="Worth">
              {formatAsset(shown.valueMinor, currency)}
            </TooltipRow>
            <TooltipRow swatch={<span className="w-2.5 border-t-2 border-dashed border-muted-foreground" />} label="Put in">
              {formatAsset(shown.contributedMinor, currency)}
            </TooltipRow>
            {(() => {
              const g = gainAt(shown);
              return (
                <TooltipRow swatch={<span className="size-2 rounded-sm bg-(--tone-income)/50" />} label="Gain">
                  <span className={gainClass(g.gain)}>
                    {formatSigned(g.gain, currency)}
                    {g.ratio !== null && ` · ${formatPercent(g.ratio)}`}
                  </span>
                </TooltipRow>
              );
            })()}
            {hasNet && shown.accountsMinor != null && points.indexOf(shown) >= knownFrom && (
              <div className="mt-1.5 border-t pt-1.5">
                <TooltipRow swatch={<span className="size-2 rounded-sm bg-(--chart-accounts)/60" />} label="In accounts">
                  {formatAsset(shown.accountsMinor, currency)}
                </TooltipRow>
                <TooltipRow swatch={<span className="w-2.5 border-t-2 border-(--chart-accounts)" />} label="Net worth">
                  <span className="font-medium">{formatAsset(net(shown), currency)}</span>
                </TooltipRow>
              </div>
            )}
            {previous && (
              <div className="mt-1.5 border-t pt-1.5">
                <TooltipRow label={`Since ${tick(previous.date)}`}>
                  <span className={gainClass(shown.valueMinor - previous.valueMinor)}>
                    {formatSigned(shown.valueMinor - previous.valueMinor, currency)}
                  </span>
                </TooltipRow>
              </div>
            )}
          </div>
        )}
      </div>
    </figure>
  );
}

function TooltipRow({ swatch, label, children }: { swatch?: ReactNode; label: string; children: ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-4">
      <span className="flex items-center gap-1.5 text-muted-foreground">
        {swatch && <span className="flex w-2.5 justify-center">{swatch}</span>}
        {label}
      </span>
      <span className="amount">{children}</span>
    </div>
  );
}
