/**
 * A month's money as a flow: where it came from (income sources, and savings or assets drawn on when it didn't
 * cover the month) into one pot, and where it went (deductions, spending by category, investing, what was left).
 * Both sides add up to the same total, so the pot balances.
 */

export type FlowNode = {
  key: string;
  label: string;
  valueMinor: number;
  /** A CSS colour variable name without `--`, e.g. `hue-3` or `tone-income`. */
  color: string;
  /** Top-level category id ("" for uncategorized) when the node is a category. */
  categoryId?: string;
};

export type MonthFlowInput = {
  incomeSources: { name: string; amountMinor: number }[];
  deductionsMinor: number;
  topCategories: { categoryId: string | null; amountMinor: number }[];
  investedMinor: number;
};

/** Sources and categories under this share of the total are summed into one "Other" node, so labels don't collide. */
export const MIN_SHARE = 0.02;
const MAX_SOURCES = 6;
const MAX_CATEGORIES = 10;

export function buildFlow(
  input: MonthFlowInput,
  categoryLabel: (id: string | null) => string,
  categoryColor: (id: string | null) => string,
): { sources: FlowNode[]; uses: FlowNode[]; totalMinor: number } {
  const gross = input.incomeSources.reduce((sum, s) => sum + s.amountMinor, 0);
  const deductions = Math.max(0, input.deductionsMinor);
  const spending = input.topCategories.reduce((sum, c) => sum + c.amountMinor, 0);
  const invested = Math.max(0, input.investedMinor);
  // Money taken out of assets came in this month like income.
  const fromAssets = Math.max(0, -input.investedMinor);
  const leftover = gross - deductions + fromAssets - spending - invested;
  const fromSavings = Math.max(0, -leftover);
  const total = gross + fromAssets + fromSavings;
  if (total <= 0) return { sources: [], uses: [], totalMinor: 0 };

  const sources = group(
    input.incomeSources.map((s, i) => ({
      key: `source:${s.name.toLowerCase()}:${i}`,
      label: s.name || "Unnamed income",
      valueMinor: s.amountMinor,
      color: "tone-income",
    })),
    total,
    MAX_SOURCES,
    { key: "source:other", label: "Other income", color: "tone-income" },
  );
  if (fromAssets > 0) sources.push({ key: "from-assets", label: "Taken from assets", valueMinor: fromAssets, color: "chart-worth" });
  if (fromSavings > 0) sources.push({ key: "from-savings", label: "From savings", valueMinor: fromSavings, color: "tone-warning" });

  const uses: FlowNode[] = [];
  if (deductions > 0) uses.push({ key: "deductions", label: "Deductions", valueMinor: deductions, color: "tone-deduction" });
  uses.push(
    ...group(
      input.topCategories.map((c) => ({
        key: `category:${c.categoryId ?? ""}`,
        label: categoryLabel(c.categoryId),
        valueMinor: c.amountMinor,
        color: categoryColor(c.categoryId),
        categoryId: c.categoryId ?? "",
      })),
      total,
      MAX_CATEGORIES,
      { key: "category:other", label: "Other spending", color: "chart-3" },
    ),
  );
  if (invested > 0) uses.push({ key: "invested", label: "Invested", valueMinor: invested, color: "chart-worth" });
  if (leftover > 0) uses.push({ key: "left", label: "Left over", valueMinor: leftover, color: "tone-income" });
  return { sources, uses, totalMinor: total };
}

/** Keeps the largest [max] nodes of at least [MIN_SHARE] of [total] and sums the rest into one [other] node. */
function group(nodes: FlowNode[], total: number, max: number, other: Omit<FlowNode, "valueMinor">): FlowNode[] {
  const sorted = nodes.filter((n) => n.valueMinor > 0).sort((a, b) => b.valueMinor - a.valueMinor);
  const kept = sorted.filter((n, i) => i < max && n.valueMinor / total >= MIN_SHARE);
  const rest = sorted.slice(kept.length);
  // A single leftover node keeps its own name.
  if (rest.length === 1) return [...kept, rest[0]];
  if (rest.length === 0) return kept;
  return [...kept, { ...other, valueMinor: rest.reduce((sum, n) => sum + n.valueMinor, 0) }];
}

export type PlacedNode = FlowNode & { x: number; y: number; height: number };
export type PlacedLink = { node: PlacedNode; path: string };

export type SankeyLayout = {
  width: number;
  height: number;
  sources: PlacedNode[];
  uses: PlacedNode[];
  pot: { x: number; y: number; height: number };
  links: PlacedLink[];
};

export const NODE_WIDTH = 10;

/**
 * Places the nodes in three columns (sources, the pot, uses) on one scale, each column centred, and draws each node's
 * band to or from the pot, stacked along it in order.
 */
export function layoutSankey(
  flow: { sources: FlowNode[]; uses: FlowNode[]; totalMinor: number },
  { width, columns, gap = 14, minHeight = 2, scale = 280 }: { width: number; columns: [number, number, number]; gap?: number; minHeight?: number; scale?: number },
): SankeyLayout {
  const k = scale / flow.totalMinor;
  const nodeHeight = (n: FlowNode) => Math.max(minHeight, n.valueMinor * k);
  const columnHeight = (nodes: FlowNode[]) => nodes.reduce((sum, n) => sum + nodeHeight(n), 0) + gap * Math.max(0, nodes.length - 1);
  const height = Math.max(scale, columnHeight(flow.sources), columnHeight(flow.uses));
  const potHeight = scale;
  const pot = { x: columns[1], y: (height - potHeight) / 2, height: potHeight };

  const place = (nodes: FlowNode[], x: number): PlacedNode[] => {
    let y = (height - columnHeight(nodes)) / 2;
    return nodes.map((n) => {
      const placed = { ...n, x, y, height: nodeHeight(n) };
      y += placed.height + gap;
      return placed;
    });
  };
  const sources = place(flow.sources, columns[0]);
  const uses = place(flow.uses, columns[2]);

  const links: PlacedLink[] = [];
  let into = pot.y;
  for (const node of sources) {
    const band = node.valueMinor * k;
    links.push({ node, path: bandPath(node.x + NODE_WIDTH, node.y, pot.x, into, Math.min(band, node.height)) });
    into += band;
  }
  let out = pot.y;
  for (const node of uses) {
    const band = node.valueMinor * k;
    links.push({ node, path: bandPath(pot.x + NODE_WIDTH, out, node.x, node.y, Math.min(band, node.height)) });
    out += band;
  }
  return { width, height, sources, uses, pot, links };
}

/** A filled band [h] thick from (x0, y0) to (x1, y1), its top and bottom edges as matching cubic curves. */
function bandPath(x0: number, y0: number, x1: number, y1: number, h: number): string {
  const mid = (x0 + x1) / 2;
  const f = (n: number) => Math.round(n * 10) / 10;
  return (
    `M${f(x0)},${f(y0)} C${f(mid)},${f(y0)} ${f(mid)},${f(y1)} ${f(x1)},${f(y1)} ` +
    `L${f(x1)},${f(y1 + h)} C${f(mid)},${f(y1 + h)} ${f(mid)},${f(y0 + h)} ${f(x0)},${f(y0 + h)} Z`
  );
}
