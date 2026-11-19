# 4. Transfers by hand and editing them

## Problem

Transfers can only be made from bank rows in the Inbox (`POST /bank-rows/transfer`, `server/.../inbox/TransferRoutes.kt`). There's no way to record one by hand (cash taken out, a top-up from an account the app doesn't read), to change one, and Activity doesn't name them (the `transfers` table has an audit trigger since V20, but `web/src/activity/describe.ts` has no label for it).

## Build

- **API** (`TransferRoutes.kt`, models in `shared/.../Transfers.kt`):
  - `POST /transfers` with a `TransferInput` (date, `amountMinor` in the household currency, `fromAccountId`, `toAccountId`, notes). Both accounts in the household; the amount positive; the same account on both sides only makes sense for an exchange between pockets, so allow it only when that account has more than one feed, or refuse it (pick one and say which in the doc comment).
  - `PUT /transfers/{id}` with `version`. A transfer made from bank rows keeps what the rows decide: only notes can change, and for a one-row transfer the account that isn't read. Date, amount and accounts are editable on a hand-entered one. Return 409 with a clear code (e.g. `transfer_from_bank_rows`) for anything else.
  - `DELETE` already exists; check it works for a transfer without rows.
  - `GET /transfers` already lists; add filters if the page needs them (month, account).
- **No migration expected**: `transfers` doesn't need rows. Check the schema allows it.
- **Web**:
  - A Transfers list. Either its own page in the sidebar or a section on Accounts; pick whichever fits the existing navigation better and keep it small: date, from → to (`AccountLabel`), amount, notes, `KindChip` transfer, row menu to edit or delete.
  - `web/src/inbox/TransferForm.tsx` is the Inbox's form; reuse or extend it for hand entry and editing in the side `Sheet`, rather than a second form.
  - Activity: add `transfer` to `describe.ts` (labels and field names), so transfers show like other records.
- Joint contributions on Balances already read `transfers`, so hand-entered top-ups count there automatically. Check it.

## Tests

`InboxTest` or a new `TransferTest`: create, validation errors (other household's account, zero amount), edit with version conflict, edit of a bank-row transfer limited to notes, delete. Activity lists a transfer change (`ActivityTest`). Web: the form creates and edits; `describe.ts` test for transfers.

## Done when

Transfers can be entered, edited and deleted by hand, Activity shows them, `./gradlew build` and the web tests pass, it's checked in the browser preview, and the roadmap's "Not yet: transfers entered by hand, editing a transfer, Activity for transfers" is struck.
