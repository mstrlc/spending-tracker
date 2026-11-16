import type { Tone } from "./chips";

export type Slice = { name: string; amountMinor: number; tone?: Tone };

/**
 * Named totals as bar slices, largest first. Totals at zero or below are dropped,
 * and anything past the fifth slice becomes "Other".
 */
export function rankSlices(sums: Map<string, number>, tones?: Map<string, Tone>): Slice[] {
  const slices = [...sums]
    .map(([name, amountMinor]): Slice => {
      const tone = tones?.get(name);
      return tone ? { name, amountMinor, tone } : { name, amountMinor };
    })
    .filter((s) => s.amountMinor > 0)
    .sort((a, b) => b.amountMinor - a.amountMinor);
  if (slices.length <= 6) return slices;
  const rest = slices.slice(5).reduce((sum, s) => sum + s.amountMinor, 0);
  return [...slices.slice(0, 5), tones ? { name: "Other", amountMinor: rest, tone: "neutral" } : { name: "Other", amountMinor: rest }];
}

function sliceBackground(tone: Tone | undefined, index: number): string {
  if (!tone) return `var(--chart-${index + 1})`;
  if (tone === "neutral") return "var(--chart-4)";
  return `var(--${tone.startsWith("hue-") ? tone : `tone-${tone}`})`;
}

/** One bar split into slices, then a legend with each share. Uses slice tones when given, else rank shades. */
export function ShareBar({ slices, label }: { slices: Slice[]; label: string }) {
  const total = slices.reduce((sum, s) => sum + s.amountMinor, 0);
  if (total === 0) return null;
  const percent = (s: Slice) => Math.round((s.amountMinor / total) * 100);
  return (
    <figure className="flex flex-col gap-3" aria-label={label}>
      <div className="flex h-2.5 gap-0.5 overflow-hidden rounded-full" aria-hidden="true">
        {slices.map((s, i) => (
          <span key={s.name} className="min-w-1" style={{ flexGrow: s.amountMinor, background: sliceBackground(s.tone, i) }} />
        ))}
      </div>
      <ul className="flex flex-wrap gap-x-5 gap-y-1.5 text-sm">
        {slices.map((s, i) => (
          // Phones show the top three.
          <li key={s.name} className={i >= 3 ? "hidden items-center gap-2 sm:flex" : "flex items-center gap-2"}>
            <span className="size-2.5 rounded-full" style={{ background: sliceBackground(s.tone, i) }} />
            {s.name}
            <span className="amount text-muted-foreground">{percent(s) < 1 ? "<1" : percent(s)}%</span>
          </li>
        ))}
      </ul>
    </figure>
  );
}
