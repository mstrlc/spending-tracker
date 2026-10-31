export type Slice = { name: string; amountMinor: number };

/**
 * Named totals as bar slices, largest first. Totals at zero or below are dropped,
 * and anything past the fifth slice becomes "Other".
 */
export function rankSlices(sums: Map<string, number>): Slice[] {
  const slices = [...sums]
    .map(([name, amountMinor]) => ({ name, amountMinor }))
    .filter((s) => s.amountMinor > 0)
    .sort((a, b) => b.amountMinor - a.amountMinor);
  if (slices.length <= 6) return slices;
  const rest = slices.slice(5).reduce((sum, s) => sum + s.amountMinor, 0);
  return [...slices.slice(0, 5), { name: "Other", amountMinor: rest }];
}

/** One bar split into slices, then a legend with each share. Shades follow rank, so the biggest leads. */
export function ShareBar({ slices, label }: { slices: Slice[]; label: string }) {
  const total = slices.reduce((sum, s) => sum + s.amountMinor, 0);
  if (total === 0) return null;
  const percent = (s: Slice) => Math.round((s.amountMinor / total) * 100);
  return (
    <figure className="breakdown" aria-label={label}>
      <div className="category-bar" aria-hidden="true">
        {slices.map((s, i) => (
          <span key={s.name} style={{ flexGrow: s.amountMinor, background: `var(--chart-${i + 1})` }} />
        ))}
      </div>
      <ul className="category-legend">
        {slices.map((s, i) => (
          <li key={s.name}>
            <span className="swatch" style={{ background: `var(--chart-${i + 1})` }} />
            {s.name}
            <span className="share">{percent(s) < 1 ? "<1" : percent(s)}%</span>
          </li>
        ))}
      </ul>
    </figure>
  );
}
