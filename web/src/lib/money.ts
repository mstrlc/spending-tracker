/** Number of minor-unit digits for a currency (2 for EUR, 0 for JPY). */
export function fractionDigits(currency: string): number {
  return new Intl.NumberFormat("en", { style: "currency", currency }).resolvedOptions().maximumFractionDigits ?? 2;
}

export function formatMoney(amountMinor: number, currency: string, locale?: string): string {
  const digits = fractionDigits(currency);
  return new Intl.NumberFormat(locale, { style: "currency", currency }).format(amountMinor / 10 ** digits);
}

/**
 * Parses user input like "129.90", "129,9", "-45" or "1 299,50" into minor units.
 * Returns null when the input isn't a number or has more decimals than the currency allows.
 */
export function parseMoney(input: string, currency: string): number | null {
  const digits = fractionDigits(currency);
  const compact = input.replace(/[\s ']/g, "");
  const match = /^(-?)(\d+)(?:[.,](\d*))?$/.exec(compact);
  if (!match) return null;
  const [, sign, whole, fraction = ""] = match;
  if (fraction.length > digits) return null;
  const minor = Number(whole) * 10 ** digits + Number(fraction.padEnd(digits, "0") || "0");
  if (!Number.isSafeInteger(minor)) return null;
  return sign ? -minor : minor;
}

/** Minor units back to an editable string, e.g. 12990 CZK → "129.90". */
export function toInputValue(amountMinor: number, currency: string): string {
  const digits = fractionDigits(currency);
  const sign = amountMinor < 0 ? "-" : "";
  const abs = Math.abs(amountMinor);
  if (digits === 0) return `${sign}${abs}`;
  const whole = Math.floor(abs / 10 ** digits);
  const fraction = String(abs % 10 ** digits).padStart(digits, "0");
  return `${sign}${whole}.${fraction}`;
}
