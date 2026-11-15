import type { Account, AccountKind, BankConnection, BankFeed, BankInstitution, Member } from "../api/types";

/** One account's feeds: one per currency pocket, the household currency first. */
export type AccountFeeds = { account: Account | undefined; accountId: string; feeds: BankFeed[] };

/** One bank within a person's group: a live connection, or statements uploaded for one institution. */
export type BankGroup = { key: string; institution: BankInstitution; connection: BankConnection | undefined; accounts: AccountFeeds[] };

/** Whose accounts these are: a member's, or joint ([userId] null). */
export type PersonGroup = { userId: string | null; banks: BankGroup[] };

const KIND_ORDER: AccountKind[] = ["bank", "credit_card", "savings", "cash", "other"];

/**
 * Feeds grouped by whose account they read (members in household order, joint last), then by bank (live connection or
 * statements from one institution, by name), then by account (open ones first, current accounts before cards and
 * savings). Connections with no feed yet come back separately, as they belong to no one until accounts are picked.
 */
export function groupFeeds(
  feeds: BankFeed[],
  accounts: Account[],
  members: Pick<Member, "userId">[],
  connections: BankConnection[],
  householdCurrency: string,
  institutionLabel: (institution: BankInstitution) => string,
): { people: PersonGroup[]; unlinked: BankConnection[] } {
  const accountById = new Map(accounts.map((a) => [a.id, a]));
  const connectionById = new Map(connections.map((c) => [c.id, c]));
  const bankLabel = (bank: BankGroup) => bank.connection?.institutionName ?? institutionLabel(bank.institution);

  const people = new Map<string | null, Map<string, BankGroup>>();
  for (const feed of feeds) {
    const owner = accountById.get(feed.accountId)?.ownerUserId ?? null;
    const banks = people.get(owner) ?? new Map<string, BankGroup>();
    people.set(owner, banks);
    const connection = feed.connectionId ? connectionById.get(feed.connectionId) : undefined;
    const key = connection ? `connection:${connection.id}` : `statements:${feed.institution}`;
    const bank = banks.get(key) ?? { key, institution: feed.institution, connection, accounts: [] };
    banks.set(key, bank);
    let entry = bank.accounts.find((a) => a.accountId === feed.accountId);
    if (!entry) {
      entry = { account: accountById.get(feed.accountId), accountId: feed.accountId, feeds: [] };
      bank.accounts.push(entry);
    }
    entry.feeds.push(feed);
  }

  const order = [...members.map((m) => m.userId), null];
  const rank = (owner: string | null) => (order.includes(owner) ? order.indexOf(owner) : order.length);
  const accountOrder = (a: AccountFeeds) => [
    a.account?.status === "closed" ? 1 : 0,
    KIND_ORDER.indexOf(a.account?.kind ?? "other"),
  ];

  const grouped = [...people.entries()]
    .sort(([a], [b]) => rank(a) - rank(b))
    .map(([userId, banks]) => ({
      userId,
      banks: [...banks.values()]
        .sort((a, b) => bankLabel(a).localeCompare(bankLabel(b)))
        .map((bank) => ({
          ...bank,
          accounts: bank.accounts
            .map((a) => ({
              ...a,
              feeds: [...a.feeds].sort(
                (x, y) => Number(y.currency === householdCurrency) - Number(x.currency === householdCurrency) || x.currency.localeCompare(y.currency),
              ),
            }))
            .sort((a, b) => {
              const [ca, ka] = accountOrder(a);
              const [cb, kb] = accountOrder(b);
              return ca - cb || ka - kb || (a.account?.name ?? "").localeCompare(b.account?.name ?? "");
            }),
        })),
    }));

  const used = new Set(feeds.map((f) => f.connectionId).filter(Boolean));
  return { people: grouped, unlinked: connections.filter((c) => !used.has(c.id)) };
}
