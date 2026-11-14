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

Decisions (2026-11-14):

- Matching: `text_contains` is checked against `normalizeText` (`BankText.kt`) of text, counterparty name and message
  together. Every condition a rule sets must hold, and a rule needs at least one of IBAN, merchant key or text. The
  first match wins: highest `priority`, then the most conditions set, then oldest.
- Creating records: expense and income creation are written inline in `ExpenseRoutes` / `IncomeRoutes`, and transfer
  creation in `TransferRoutes`. Extract them into functions (validation, merchant find-or-create, lines, splits) that
  the HTTP routes and the rules both call, so a rule-made record is exactly what the form would have made. A rule-made
  expense or income line uses the same prefill as `suggestFor`: the row's date, the feed's account, the amount in the
  household currency at the ČNB fixing with the original amount and currency kept when foreign, and whose from the
  account when the rule doesn't name a person. The category comes from the rule, else the merchant's default.
- Never count money twice: when `suggestFor` finds a hand-entered duplicate for a row, an auto rule links the row to
  that record (`created_record = false`) instead of creating a new one. A transfer rule pairs the row with its pending
  other side when there's exactly one candidate; otherwise it files the row alone against the rule's other account.
- Who did it: auto-filed rows get `decided_by` null, `decided_auto` true and `rule_id` set. The matcher's own
  decisions have `rule_id` null. The audit actor is whoever made the request that triggered them (upload, import or
  apply-rules). Undo clears `rule_id` too. Bulk undo is the existing `/bank-rows/undo` with many refs.
- Setting: `households.auto_match boolean NOT NULL DEFAULT false`, editable by owners through the household update
  route, shown on the rules page as "File transfers and duplicates automatically". Rules with `auto` file whatever
  the setting says.
- Suggestions: a matching rule without `auto` takes precedence over the history-based guess, and
  `BankRowSuggestion` names it (`ruleId`, `ruleName`). The Inbox shows "Rule: …" on such rows.
- `rule_id` on `bank_rows` references `inbox_rules` `ON DELETE SET NULL (rule_id)`: deleting a rule leaves what it
  filed in place.
- The legacy report prints counts only (agree, disagree, matcher-only, old-only per decision type). It never prints
  names, amounts, IBANs or texts.

## Slice 4: live GoCardless

Blocked on whether the old app's GoCardless credentials still work (new sign-ups are closed). Then: `bank_connections`
(institution, requisition id, consent expiry, status), connect flow (redirect + callback), listing the bank's
accounts and creating feeds from them (`source = 'gocardless'`, `external_id`), a scheduled sync (the app has no
background jobs yet: add a simple coroutine scheduler in `Application.module`, disabled in tests), "Sync now",
balance readings (`bank_balances`), expiry warnings. Secrets from env, never in the database in plain text.

Decisions (2026-11-14):

- The old app (`../household-spending-tracker/worker`) has working GoCardless code (token, requisitions, accounts,
  transactions, its daily cron): port its rules, not its code. Its `.env` has `GOCARDLESS_SECRET_ID` and
  `GOCARDLESS_SECRET_KEY`. They may be copied into this repo's gitignored root `.env` to request a token and confirm
  they work. Never make a bank connection, print the keys, or put them in a committed file. `Config.fromEnv` reads them,
  and `:server:run` loads the root `.env` when it exists.
- The GoCardless client sits behind an interface (as `MarketData` does), and tests use a fake. With no keys, the
  connect UI says bank connections aren't set up, and nothing else changes.
- Synced rows go through `storeRows` and then the rules from slice 3, exactly as an upload does. A sync asks for rows
  from the last booked date minus 7 days (overlap is harmless: external ids dedupe), and never before the feed's
  `import_from`.
- The scheduler runs once a day and on "Sync now". Rate limits (GoCardless allows a few calls per account per day) are
  respected by remembering each feed's last sync and refusing more than 4 a day.

## Slice 5: refunds and deduction rules

- `expenses.refund_of uuid` (self-FK): a refund (negative expense) attached to the original; category and month
  totals already net negative expenses, so this is mostly the link, the form ("Refund of…" picker of recent expenses
  at that merchant) and the Inbox prefill for refund rows.
- Rule outcome `deduction` (source, whose, month offset): ZPMV and OSSZ advances leaving an account become income
  deductions.

Decisions (2026-11-14):

- A refund is a negative expense, and its original is a positive expense in the same household that isn't itself a
  refund. `refund_of` is `ON DELETE SET NULL`. The form fills the refund's category (or lines) and whose from the
  original.
- "Net in its own category and month" means reports count a refund in its original's month and category: the
  Overview (month totals, typical month, anomalies, category totals, saved) and the Expenses page's category
  breakdown. Lists still show the refund on its own date. An `Expense` carries `refundOfId`, and the original carries
  `refundedMinor` (the sum of its refunds).
- Debts follow on their own: a refund on the same account with the same whose reverses its share of the debt.
- Inbox: for a positive row, `suggestFor` offers originals at the same merchant key or merchant from the past 120
  days with an amount at least the refund's, newest first. Adding the row as an expense prefills "Refund of".
- Deduction rule outcome: `deduction` with source, whose and month offset (default -1, the previous month). It makes
  an income line of kind deduction for minus the row's amount, earned in the row's month plus the offset, arrived on
  the row's date.
