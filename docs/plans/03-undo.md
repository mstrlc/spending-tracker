# 3. Undo after save or delete

## Problem

From the MVP: after saving or deleting there's no undo. Deletes ask for confirmation (`useConfirm()` in `web/src/dialogs.tsx`) and Activity shows what changed, but a wrong save or delete has to be fixed by hand.

## Approach

A toast after each save or delete with an **Undo** button, for about 6 seconds. Web-only; no server changes unless a case below needs one.

- **Create** → Undo deletes the new record (with its returned `version`).
- **Edit** → Undo puts the previous values back with a `PUT` using the new `version`. Keep the record as it was before the edit from the query cache or the form's initial values.
- **Delete** → don't send the delete straight away: hide the row, show the toast, and send the delete when the toast closes, the user navigates away, or the page unloads (flush pending deletes in `pagehide`). Undo just shows the row again. This avoids having to re-create records with their links (bank rows, refunds, deductions, lines), which a re-`POST` would lose.
  - Because the deletion is delayed, the delete confirmation dialog can go for these records; decide per page whether to keep it for large deletions (anything that also deletes other records, such as an earning with linked deductions).
  - If the delayed delete fails (409 `version_conflict` or `*_in_use`), show the row again with the error.

Cover expenses, income, settlements, transfers (once plan 4 lands, its form too), assets' contributions/valuations/fees, merchants, categories, trips and accounts where they have save/delete. Inbox actions already have "put back" and can stay as they are.

## Build

- Add shadcn's `sonner` (`pnpm --dir web dlx shadcn@latest add sonner`, then fix the `cn` import per `AGENTS.md`) and mount the `Toaster` once in the layout.
- A small helper in `web/src/ui.tsx` or a new `web/src/undo.tsx`: `useUndoableSave()` / `useUndoableDelete()` wrapping the existing React Query mutations (`web/src/api/hooks.ts`), handling the toast, the cache update and the flush. Pages call it instead of re-implementing the pattern.
- Keep the toast text short: "Expense saved · Undo", "Expense deleted · Undo".
- Phones: the toast must not cover the bottom of the editor sheet.

## Tests

Vitest with Testing Library (`web/src/test-utils.tsx`): create then undo calls delete; edit then undo sends the old values; delete is not sent until the toast closes, undo cancels it, navigating away flushes it; a failed delayed delete brings the row back with the error.

## Done when

Undo works on the listed pages, `pnpm --dir web exec tsc --noEmit` and `pnpm --dir web test` pass, it's checked in the browser preview (desktop and phone width), "Web UI" in `ARCHITECTURE.md` mentions the helper, and the roadmap's "Not yet: undo after save/delete" is struck.
