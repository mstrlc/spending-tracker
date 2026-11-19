# 5. Settlements suggested from bank rows

## Problem

A bank row can already become a settlement (`POST /bank-rows/settle`, the Inbox's `web/src/inbox/SettleForm.tsx`), but nothing suggests it. A payment from one member's account to the other's is suggested as a transfer, or as an expense when the hint names a member, and the user has to know to pick "Settle".

## Build

- **When to suggest** (`server/.../inbox/Suggestions.kt`): a row is probably a settlement when money moves between two members' **personal** accounts (not joint, not the same person's): the row's account belongs to one member and either the counterparty IBAN is an account of the other member, the other side is a pending row on the other member's feed (an existing transfer candidate), or the "transfer to a member by name" hint matches the other member (legal names, V24).
  - Don't suggest it for joint accounts; money into a joint account is a top-up (a transfer).
  - Direction: money out of A's account to B is A paying B (`fromUserId` A, `toUserId` B). Money in from B is B paying A.
  - Bonus signal: the current balance between the two (from the balances calculation) points the same way. Use it only to rank, never to decide.
- **Model** (`shared/.../Inbox.kt`): add `SETTLEMENT` to `SuggestedRecord`, plus `settleFromUserId` / `settleToUserId` on `BankRowSuggestion`. Keep transfer candidates filled, since a two-row settlement uses both rows. Response models have no defaults (see Conventions).
- **Web** (`web/src/inbox/InboxPage.tsx`, `SettleForm.tsx`, `drafts.ts`): a settlement suggestion shows the settlement `KindChip` and opens the settle form prefilled with both members, the amount and the paired row.
- **Rules (optional, ask the user)**: a `settlement` rule outcome (`RuleOutcome`, `inbox_rules` check constraint: migration **V33**) with from/to members, so a regular repayment can be filed automatically. Only if the user wants it; otherwise leave it for later and say so in the roadmap.
- **Auto-filing**: don't auto-file settlements from guesses; they change balances. Only a rule (if built) files them automatically.

## Tests

`InboxTest`: personal → other member's personal by IBAN, by paired row, by name hint; joint accounts don't get it; direction both ways; existing transfer and expense suggestions unchanged for rows that aren't between members. Web: the prefilled settle form.

## Done when

Payments between members show as suggested settlements and file in one click, `./gradlew build` and web tests pass, it's checked against the user's Inbox in the preview (`--copy` data, if there are such rows), and the roadmap's "settlements matched from bank rows" is struck.
