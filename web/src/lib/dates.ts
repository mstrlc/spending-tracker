/** A calendar month as "YYYY-MM". */
export type Month = string;

export function currentMonth(today = new Date()): Month {
  return `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, "0")}`;
}

export function shiftMonth(month: Month, delta: number): Month {
  const [year, m] = month.split("-").map(Number);
  const date = new Date(year, m - 1 + delta, 1);
  return currentMonth(date);
}

/** Inclusive first and last day of the month as YYYY-MM-DD. */
export function monthRange(month: Month): { from: string; to: string } {
  const [year, m] = month.split("-").map(Number);
  const lastDay = new Date(year, m, 0).getDate();
  return { from: `${month}-01`, to: `${month}-${String(lastDay).padStart(2, "0")}` };
}

export function formatMonth(month: Month, locale?: string): string {
  const [year, m] = month.split("-").map(Number);
  return new Date(year, m - 1, 1).toLocaleDateString(locale, { month: "long", year: "numeric" });
}

export function todayIso(today = new Date()): string {
  return `${currentMonth(today)}-${String(today.getDate()).padStart(2, "0")}`;
}

export function isMonth(value: string | null): value is Month {
  return value !== null && /^\d{4}-(0[1-9]|1[0-2])$/.test(value);
}
