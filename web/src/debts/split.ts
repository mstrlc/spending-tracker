import type { Debt, Member, Share } from "../api/types";

/** Weights as typed into the split editor, by member. */
export type WeightDraft = Record<string, string>;

export function draftFrom(shares: Share[] | null | undefined, members: Member[]): WeightDraft {
  return Object.fromEntries(members.map((m) => [m.userId, String(shares?.find((s) => s.userId === m.userId)?.weight ?? 0)]));
}

/** The shares a draft stands for, or null while it has a weight that isn't a whole number, or none above zero. */
export function sharesFrom(draft: WeightDraft): Share[] | null {
  const shares: Share[] = [];
  for (const [userId, text] of Object.entries(draft)) {
    const trimmed = text.trim();
    if (trimmed === "") continue;
    if (!/^\d+$/.test(trimmed)) return null;
    const weight = Number(trimmed);
    if (weight > 0) shares.push({ userId, weight });
  }
  return shares.length > 0 ? shares : null;
}

/** Each member's part in whole percent, for showing next to the weights. */
export function percents(shares: Share[]): Map<string, number> {
  const total = shares.reduce((sum, s) => sum + s.weight, 0);
  return new Map(shares.map((s) => [s.userId, total > 0 ? Math.round((s.weight / total) * 100) : 0]));
}

/** "Alice 60 % · Bob 40 %", members in the household's order. */
export function describeShares(shares: Share[], members: Member[]): string {
  const parts = percents(shares);
  return members
    .filter((m) => parts.has(m.userId))
    .map((m) => `${m.displayName} ${parts.get(m.userId)} %`)
    .join(" · ");
}

/**
 * Weights in proportion to what each member earned: whole percent that add up to 100. Joint income doesn't count
 * towards anyone. Null when nobody earned anything.
 */
export function weightsFromIncome(income: { amountMinor: number; recipientUserId?: string | null }[], members: Member[]): Share[] | null {
  const earned = new Map<string, number>();
  for (const i of income) {
    if (!i.recipientUserId) continue;
    earned.set(i.recipientUserId, (earned.get(i.recipientUserId) ?? 0) + i.amountMinor);
  }
  const positive = members.map((m) => ({ userId: m.userId, amount: Math.max(0, earned.get(m.userId) ?? 0) }));
  const total = positive.reduce((sum, p) => sum + p.amount, 0);
  if (total <= 0) return null;
  // Largest remainder, so the percent add up to exactly 100.
  const exact = positive.map((p) => ({ ...p, share: (p.amount / total) * 100 }));
  const floors = exact.map((p) => ({ ...p, weight: Math.floor(p.share) }));
  let left = 100 - floors.reduce((sum, p) => sum + p.weight, 0);
  for (const p of [...floors].sort((a, b) => b.share - Math.floor(b.share) - (a.share - Math.floor(a.share)))) {
    if (left <= 0) break;
    p.weight += 1;
    left -= 1;
  }
  return floors.filter((p) => p.weight > 0).map(({ userId, weight }) => ({ userId, weight }));
}

export const memberName = (members: Member[], userId: string) => members.find((m) => m.userId === userId)?.displayName ?? "Former member";

/** "Bob owes Alice". */
export function owesLine(debt: Debt, members: Member[]): string {
  return `${memberName(members, debt.fromUserId)} owes ${memberName(members, debt.toUserId)}`;
}
