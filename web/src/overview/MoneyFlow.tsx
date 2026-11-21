import { useEffect, useRef, useState } from "react";
import { Link } from "react-router";
import { formatAmount } from "../lib/money";
import { cn } from "@/lib/utils";
import { layoutSankey, NODE_WIDTH, type FlowNode, type PlacedNode } from "./sankey";

/** Room for the labels beside each side's nodes. */
const LEFT_LABELS = 190;
const RIGHT_LABELS = 210;
const MIN_WIDTH = 640;
const TOP = 26;

/** The container's width, so the chart is drawn at its real size and its text isn't scaled. */
function useWidth() {
  const ref = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(900);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const observer = new ResizeObserver(([entry]) => setWidth(Math.round(entry.contentRect.width)));
    observer.observe(el);
    return () => observer.disconnect();
  }, []);
  return [ref, Math.max(MIN_WIDTH, width)] as const;
}

/**
 * The month's money as a sankey: income sources (and savings drawn on) on the left flow into the month's income, which
 * flows out to deductions, each category, investing and what was left. Hovering a node or band picks it out; a
 * category opens Reports on it.
 */
export function MoneyFlow({
  flow,
  currency,
  categoryHref,
}: {
  flow: { sources: FlowNode[]; uses: FlowNode[]; totalMinor: number };
  currency: string;
  categoryHref: (categoryId: string) => string;
}) {
  const [active, setActive] = useState<string | null>(null);
  const [ref, width] = useWidth();
  const columns: [number, number, number] = [LEFT_LABELS, (width - NODE_WIDTH) / 2, width - RIGHT_LABELS - NODE_WIDTH];
  const layout = layoutSankey(flow, { width, columns });
  const share = (n: FlowNode) => Math.round((n.valueMinor / flow.totalMinor) * 100);
  const describe = (n: FlowNode) => `${n.label}: ${formatAmount(n.valueMinor, currency)} ${currency} (${share(n)}%)`;
  const dim = (key: string) => active !== null && active !== key;

  const label = (n: PlacedNode, side: "left" | "right") => {
    const x = side === "left" ? n.x - 8 : n.x + NODE_WIDTH + 8;
    const text = (
      <text
        x={x}
        y={n.y + n.height / 2}
        dominantBaseline="middle"
        textAnchor={side === "left" ? "end" : "start"}
        className={cn("fill-foreground text-[13px] transition-opacity", dim(n.key) && "opacity-40")}
      >
        {side === "left" ? (
          <>
            <tspan className="amount fill-muted-foreground">{formatAmount(n.valueMinor, currency)}</tspan> {n.label}
          </>
        ) : (
          <>
            {n.label} <tspan className="amount fill-muted-foreground">{formatAmount(n.valueMinor, currency)}</tspan>
          </>
        )}
      </text>
    );
    return n.categoryId !== undefined && n.key !== "category:other" ? (
      <Link to={categoryHref(n.categoryId)} className="hover:underline focus-visible:underline" aria-label={`${describe(n)}, open in Reports`}>
        {text}
      </Link>
    ) : (
      text
    );
  };

  const node = (n: PlacedNode) => (
    <rect
      key={n.key}
      x={n.x}
      y={n.y}
      width={NODE_WIDTH}
      height={n.height}
      rx={2}
      fill={`var(--${n.color})`}
      className={cn("transition-opacity", dim(n.key) && "opacity-40")}
      onMouseEnter={() => setActive(n.key)}
      onMouseLeave={() => setActive(null)}
    >
      <title>{describe(n)}</title>
    </rect>
  );

  const income = flow.sources.filter((n) => n.key.startsWith("source:")).reduce((s, n) => s + n.valueMinor, 0);

  return (
    <div ref={ref} className="overflow-x-auto">
      <svg
        width={width}
        height={layout.height + TOP}
        viewBox={`0 0 ${width} ${layout.height + TOP}`}
        className="block"
        role="img"
        aria-label={`Money in and out: ${flow.sources.map(describe).join(", ")}; went to ${flow.uses.map(describe).join(", ")}`}
      >
        <g transform={`translate(0 ${TOP})`}>
          {layout.links.map(({ node: n, path }) => (
            <path
              key={`link:${n.key}`}
              d={path}
              fill={`var(--${n.color})`}
              className={cn("transition-opacity", active === n.key ? "opacity-60" : dim(n.key) ? "opacity-10" : "opacity-30")}
              onMouseEnter={() => setActive(n.key)}
              onMouseLeave={() => setActive(null)}
            >
              <title>{describe(n)}</title>
            </path>
          ))}
          <rect x={layout.pot.x} y={layout.pot.y} width={NODE_WIDTH} height={layout.pot.height} rx={2} className="fill-foreground" />
          <text x={layout.pot.x + NODE_WIDTH / 2} y={-12} textAnchor="middle" className="fill-foreground text-[13px] font-medium">
            {income > 0 ? "Income " : "Spent "}
            <tspan className="amount">{formatAmount(income > 0 ? income : flow.totalMinor, currency)}</tspan>
          </text>
          {layout.sources.map(node)}
          {layout.uses.map(node)}
          {layout.sources.map((n) => (
            <g key={`label:${n.key}`}>{label(n, "left")}</g>
          ))}
          {layout.uses.map((n) => (
            <g key={`label:${n.key}`}>{label(n, "right")}</g>
          ))}
        </g>
      </svg>
    </div>
  );
}
