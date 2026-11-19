# Plans: unfinished pieces (2026-11-15)

One plan per agent, each in its own worktree (`scripts/dev.sh setup`, see `AGENTS.md`). Each picks up a "Not yet" item from the roadmap in `ARCHITECTURE.md`.

| # | Plan | Priority | Migration | Main files |
|---|---|---|---|---|
| 1 | [Old history and balances](01-legacy-balances-cutover.md) | before relying on Balances | V30 | `HouseholdRoutes.kt`, `DebtRoutes.kt`, `BalancesPage.tsx`, `scripts/legacy-import.mjs` |
| 2 | [Joint top-ups count towards debts](02-joint-topups.md) | before relying on Balances | V31 | `SplitBook.kt`, `DebtRoutes.kt`, `BalancesPage.tsx` |
| 3 | [Undo after save or delete](03-undo.md) | nice to have | none | web forms and list pages, `dialogs.tsx`, new toast |
| 4 | [Transfers by hand and editing them](04-manual-transfers.md) | nice to have | none | `TransferRoutes.kt`, `Transfers.kt`, `TransferForm.tsx`, `activity/describe.ts` |
| 5 | [Settlements suggested from bank rows](05-settlement-suggestions.md) | nice to have | none (V33 if rules get a settlement outcome) | `Suggestions.kt`, `Inbox.kt`, `Rules.kt`, `InboxPage.tsx`, `SettleForm.tsx` |
| 6 | [Liability entries from bank transfers](06-liability-from-transfer.md) | nice to have | V32 | `TransferRoutes.kt`, `AssetRoutes.kt`, `InboxPage.tsx`, `TransferForm.tsx` |

## Running them side by side

- **Migration numbers are assigned above** so parallel branches don't both add a `V30`. If one isn't needed after all, leave the gap (Flyway allows it as long as nothing out of order has run; set `outOfOrder` only if a merge needs it).
- **Overlaps**, so merge these one after another, not at once:
  - 1 and 2 both change how `/balances` is computed (`DebtRoutes.kt`, `BalancesPage.tsx`). Do 1 first, or have 2 rebase on it.
  - 4 and 6 both touch `TransferRoutes.kt` and `TransferForm.tsx`. Do 4 first; 6 builds on hand-entered transfers too.
  - 5 touches the Inbox's suggestion code that 6 also reads; small overlap.
  - 3 touches many web forms lightly; merge it last, or early and the others add undo to anything new they add.
- Everyone regenerates `web/src/api/schema.d.ts` (`scripts/dev.sh gen-api`); on a merge conflict there, regenerate it instead of merging by hand.
- Everyone updates the roadmap entry in `ARCHITECTURE.md` (strike the "Not yet" item, describe what was built in the roadmap's style).
- Plans 1 and 2 have a decision to confirm with the user before building; it's marked **Ask first**.
- Real household data stays on this machine (`AGENTS.md`). These plans quote no figures from it on purpose.
