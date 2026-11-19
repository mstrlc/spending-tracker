# 2. Joint top-ups count towards debts

## Problem

Today a joint account is a pot: what it pays for creates no debts, a member's own expense paid from it makes them owe the others their share of the pot (by the household split), and top-ups into it are only shown ("Put into joint accounts", `jointContributions` in `server/.../debt/DebtRoutes.kt`), never counted. That's fair only if both members top up exactly in proportion to the split. If one puts in more, the app doesn't notice.

The decision in `ARCHITECTURE.md` ("Top-ups of a joint account don't create or settle debts") says this is intended. Changing it is a user decision.

## Ask first

Confirm with the user whether they want top-ups counted. If yes, confirm the rule below with a worked example from their own data (a `--copy` database): over a period, what each put in, what the joint accounts paid for, and what each would owe under the new rule compared with today.

## Rule (recommended)

A household setting `joint_funding`: `pot` (today) or `contributions`.

Under `contributions`, a joint account is money the members lent to the household:

- A top-up (transfer from a member's account into a joint account) is a credit for that member; money taken back out (joint → member's account) is a debit.
- Joint spending paid from a joint account is shared by the split rules for that expense (the same weights as a joint expense paid from a personal account, including category overrides and the expense's own split), and each member's share is charged against them.
- A member's own expense paid from a joint account is charged in full to that member.
- The difference between each member's credits and charges, netted across members, becomes debts: whoever has put in more than their share is owed by the other.
- Money left in the joint accounts is everyone's, not owed. So only count what the joint accounts **spent**, not what's still in them: credits are capped, or more simply the balance is computed on spending and the leftover is shown as "still in joint accounts" by member. Work out which formulation gives zero debts when both topped up exactly by the split and spending used it all, and write that down in the code's doc comment.

If the user prefers something simpler (e.g. top-ups settle debts one for one, like a settlement), build that instead; the structure below still applies.

## Build

- **Migration V31**: `households.joint_funding text NOT NULL DEFAULT 'pot' CHECK (...)`.
- **Shared**: the setting on the household model and update input; a `BalanceEntryKind` for top-ups (`TOP_UP`) so the Balances page can list them among the entries.
- **Server**: the calculation lives with the rest of debt logic (`server/.../debt/SplitBook.kt`, `Shares.kt` for largest-remainder rounding). `getBalances` adds top-up entries from `transfers` (owner lookups as in `jointContributions`) and switches how joint-account expenses are treated when the setting is `contributions`. Keep `pot` behaviour unchanged and tested.
- **Expense debts**: an expense's own `debts` (shown in the expense form) must agree with what `/balances` counts for it under either setting.
- **Web** (`web/src/debts/BalancesPage.tsx`): a switch for owners between the two ways, explained in one sentence each; top-ups listed among the month's entries; "Put into joint accounts" stays.
- If plan 1 (`debts_from`) has landed, top-ups before that date don't count either.

## Tests

`DebtTest`: both members top up exactly by the split and spend it all → no debt; one tops up more → owed the difference times the other's share; withdrawal back to a member; member's own expense from the joint account; category override; `pot` unchanged; rounding by largest remainder.

## Done when

The user has confirmed the rule, both settings give the expected numbers on their data, `./gradlew build` and web tests pass, and `ARCHITECTURE.md` is updated (the data-model decision about top-ups and the roadmap's "top-ups of joint accounts" item).
