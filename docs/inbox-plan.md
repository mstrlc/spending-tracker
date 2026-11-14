# Inbox: plan for slices 2–5

Slices 1 (feeds, bank rows, the manual inbox) and 2 (transfers and settlements) are done; see the roadmap in `ARCHITECTURE.md` for what it built. This
file is the working plan for the rest, detailed enough to hand a slice to someone who hasn't seen the code. When a
slice lands, move its summary into the roadmap and delete it here.

Read first: `CLAUDE.md`, `ARCHITECTURE.md` (conventions, "Web UI", the data-model decisions under "Accounts, bank
feeds and transfers"), then the slice 1 code:

- `server/src/main/resources/db/migration/V19__bank_rows.sql`: the tables.
- `server/.../inbox/`: `BankRowStore.kt` (storing, `lockRows`, `fileBankRows`, `undoBankRows`), `Suggestions.kt`
  (`suggestFor`, `FxTable`), `Hints.kt`, `InboxRoutes.kt`, parsers in `Statements.kt` / `GoCardless.kt`.
- `shared/.../api/Inbox.kt`: the API models.
- `web/src/inbox/`: `InboxPage.tsx`, `FeedsPanel.tsx`, `drafts.ts`.
- Tests: `server/src/test/.../InboxTest.kt`, `BankTextTest.kt`, `web/src/inbox/drafts.test.ts`.

General rules for every slice:

- Migrations are append-only: new `V*.sql`, never edit an applied one. New household tables get the `audit` trigger.
- A bank row is decided by exactly one link or a skip reason; every new outcome is a new nullable column on
  `bank_rows`, added to the `num_nonnulls(...) <= 1` check (constraint `bank_rows_check`: drop and re-create it), to
  `BankRowOutcome`, to `outcome()` / `isPending()` and to the pending filters (`InboxRoutes`, `loadFeeds`).
- A record made from rows sets `created_record`; undoing the last of its rows deletes it (extend `undoBankRows`).
  Deleting the record puts its rows back (`ON DELETE SET NULL (column)` on the composite FK).
- Every automatic decision is marked `decided_auto`; nothing is filed automatically before slice 3.
- Tests use made-up data only. `data/legacy/` is real household data: never commit it or copy it into tests.
- Don't commit; leave the work in the tree for review.

---

## Slice 3: rules and auto-filing

- `inbox_rules` (V21): match on any of `counterparty_iban`, `merchant_key` (exact), `text_contains` (normalised,
  case/diacritics-insensitive), optional `feed_id` / `account_id`, optional sign (in/out); outcome `expense`
  (merchant name, category, whose), `income` (source, recipient), `transfer` (other account), `skip` (reason);
  `auto` boolean (file without asking) ; `priority`; audit trigger.
- Applying: on store (upload, import, later sync) and on demand (`POST /bank-rows/apply-rules`). A rule with `auto`
  files the row (`decided_auto = true`, `decided_by` null, a `rule_id` column on `bank_rows`); without `auto` it only
  fills the suggestion and is shown as "Rule: …".
- Matcher auto-filing: transfer pairs (exact amount, same currency, both sides on household accounts, ≤ 2 days) and
  duplicates of hand-entered expenses (exact amount, same account, ≤ 1 day) above a fixed confidence; off by default
  per household (a setting), on once the check below agrees.
- A check against the old app: a script or test fixture-free report (`scripts/legacy-matcher-report.mjs` reading
  `data/legacy/` locally, printing only counts) comparing the matcher's decisions with the old `bank-sync` ones.
- Web: rules page (list, edit, delete), "Make a rule" from a decided row (prefilled from the row and its decision),
  an "Automatic" filter in the Decided tab with bulk undo.

## Slice 4: live GoCardless

Blocked on whether the old app's GoCardless credentials still work (new sign-ups are closed). Then: `bank_connections`
(institution, requisition id, consent expiry, status), connect flow (redirect + callback), listing the bank's
accounts and creating feeds from them (`source = 'gocardless'`, `external_id`), a scheduled sync (the app has no
background jobs yet: add a simple coroutine scheduler in `Application.module`, disabled in tests), "Sync now",
balance readings (`bank_balances`), expiry warnings. Secrets from env, never in the database in plain text.

## Slice 5: refunds and deduction rules

- `expenses.refund_of uuid` (self-FK): a refund (negative expense) attached to the original; category and month
  totals already net negative expenses, so this is mostly the link, the form ("Refund of…" picker of recent expenses
  at that merchant) and the Inbox prefill for refund rows.
- Rule outcome `deduction` (source, whose, month offset): ZPMV and OSSZ advances leaving an account become income
  deductions.
