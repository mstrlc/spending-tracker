import { fractionDigits } from "../lib/money";

/**
 * Round gridline values from 0 up to at least [maxMinor], [count] to [count] + 1 steps of 1, 2, 2.5 or 5 times a power of ten
 * in whole currency units (so a CZK axis reads 0, 500, 1,000 rather than 0, 437.5, 875). Values are minor units.
 */
export function niceTicks(maxMinor: number, currency: string, count = 4): number[] {
  const unit = 10 ** fractionDigits(currency);
  const max = Math.max(maxMinor, unit) / unit;
  const rough = max / (count + 1);
  const power = 10 ** Math.floor(Math.log10(rough));
  const step = [1, 2, 2.5, 5, 10].map((f) => f * power).find((s) => s >= rough) ?? 10 * power;
  const ticks: number[] = [];
  for (let value = 0; value < max + step * 1e-9; value += step) ticks.push(Math.round(value * unit));
  if (ticks[ticks.length - 1] < maxMinor) ticks.push(Math.round((ticks.length * step) * unit));
  return ticks;
}

/** A short amount for labels on a chart: "950", "2.9K", "58K", "1.2M", in whole currency units. */
export function compactAmount(amountMinor: number, currency: string, locale?: string): string {
  const value = amountMinor / 10 ** fractionDigits(currency);
  return new Intl.NumberFormat(locale, { notation: "compact", maximumFractionDigits: Math.abs(value) < 10_000 ? 1 : 0 }).format(value);
}
