# new-spending-tracker: architecture and plan

A multi-tenant SaaS rewrite of `../household-spending-tracker`, built from scratch. The old app is a reference for domain rules only (see `FUNCTIONALITY_MAP.md`); its code is not ported.

## Decisions (2026-10-31)

| Area | Decision |
|---|---|
| Environment | Everything runs locally for now; no hosting yet |
| Backend | Kotlin, Ktor |
| Shared code | KMP `shared` module for server + future mobile apps (not used by web) |
| Database | Postgres 17 via Docker Compose, Flyway migrations, Exposed |
| Tenancy | *Household* is the tenant; users join one or more households via invites; every domain row carries `household_id` |
| Auth | Own email + password (argon2), server-side sessions in httpOnly cookies; OAuth / magic link later |
| API contract | Ktor publishes OpenAPI; web generates TS types + client with `openapi-typescript` |
| Web frontend | TypeScript 6 (TS 7 lacks the JS API openapi-typescript needs), React, Vite, TanStack Query, React Router, pnpm |
| Web UI | shadcn/ui (Radix, Nova style) on Tailwind CSS 4, lucide icons; components live in `web/src/components/ui` and are ours to edit. Theme tokens in `web/src/styles.css`; dark mode follows the system |
| Money | Integer minor units + currency code (household default currency) |
| Repo layout | Monorepo: `server/`, `shared/`, `web/`; Gradle Kotlin DSL |
| Tests | Ktor `testApplication` + Testcontainers Postgres; Vitest for web |

## Conventions

- Every domain row has `household_id`; cross-row references are composite `(household_id, id)` foreign keys.
- Routes under `/api/households/{householdId}` go through the `HouseholdAccess` plugin: non-members get 404.
- Editable rows carry `version`; updates send it in the body, deletes as `?version=`. A stale version is a 409 `version_conflict`.
- Rows still referenced can't be deleted (409 `*_in_use`); close accounts and archive categories instead.
- Every insert, update and delete of household data lands in `audit_log` (before/after JSON, actor) via Postgres triggers, so routes don't record anything themselves. The actor comes from `app.actor_id`, which `dbQuery` sets from the request's session user; new household tables need an `audit` trigger in their migration.
- API request/response models live in `shared` (`dev.spendingtracker.shared.api`) so mobile clients can reuse them. Response models have no default values: the JSON leaves out fields equal to their default, which would reach the web as `undefined`. Request models may have defaults.
- New references to a member are `(household_id, user_id) REFERENCES household_members ... ON UPDATE CASCADE`: claiming a member without a login moves the membership to another user id, and everything follows it.

## Data model decisions (2026-11-14)

Settled after reviewing the old app's live data (exported to the gitignored `data/legacy/`, see its README). Most aren't built yet; the roadmap picks them up.

### People, ownership and debts

- ~~**Members don't need a login.**~~ (done 2026-11-14) A household member can exist without a user account and claim it later through an invite.
- **No person means joint**, everywhere a row can belong to someone: expenses, income, accounts, assets. There is no "unknown".
- **Who paid and whose it is are separate.** The account says whose money left; the expense says whose spending it is. A joint 10k IKEA purchase on one person's credit card is one joint 10k expense, never two halves.
- **Joint or personal is filled in** from the account (joint account → joint, personal account → that person), overridden by category or merchant rules (groceries always joint), and editable per expense.
- **Debts between members come from mismatches:**
  - a joint expense paid from a personal account makes the others owe their share to the payer (can be switched off per expense);
  - a personal expense paid from a joint account makes that person owe the others their share of the pot.
- **Split ratio** comes from rules with a valid-from date: a household default (usually by income), per-category overrides (groceries moved to 50/50), and a per-expense override.
- **Repayments** are transfers between members marked as settlements. Debts and settlements add up to one running balance between members; a settlement isn't matched to particular expenses. Settlements are neither income nor spending.
- **Top-ups of a joint account** don't create or settle debts, but each member's contributions to joint accounts are shown, since that's easy to lose track of.
- **No privacy inside a household**: every member sees everything.

### Accounts, bank feeds and transfers

- ~~**Accounts have a kind**~~ (done 2026-11-14): bank, credit card, cash, other. They can carry an IBAN.
- **A bank feed is not an account.** One account can have several feeds (Revolut Joint has CZK, EUR, HUF and DKK pockets), so feeds link many-to-one to accounts.
- **Feed sources sit behind one interface**: GoCardless Bank Account Data and statement-file upload to start, others later.
- **Bank rows keep** the bank's raw JSON, the booking and transaction dates, the native amount and currency, and what was decided about each: expense, income, transfer, settlement, duplicate or skipped, with links to what it became. Several bank rows can point at one expense.
- **Transfers are first-class**: a transfer pairs an outgoing and an incoming row, or one row and an account that isn't read. Paying off a credit card is a transfer; the card's purchases are the expenses, and what's owed on the card is a liability in net worth.
- **Inbox auto-filing**: the app files anything above a confidence threshold, whether from a person's rule or its own guess (transfer pairs, duplicates). Every automatic decision is stored as automatic, so it can be reviewed, counted and undone apart from human ones. The threshold starts strict: the old app's automatic pairings were wrong often enough to matter.
- **One rules table** maps bank rows (counterparty, IBAN, text) to an outcome: merchant, category, person, income source, or transfer/skip. It replaces the old app's separate merchant aliases and income payers.

### Spending

- **Merchants are records** with a name, a default category and the bank texts that map to them, so top merchants and subscriptions don't split on spelling.
- **Split lines**: an expense can have lines with their own category and amount that add up to its total. E-shop orders can fill them in later.
- **Refunds attach to the original expense**, so it shows net in its own category and month.
- ~~**Foreign currency**~~ (done 2026-11-14): an expense can store the original amount and currency next to the household-currency amount everything adds up in.
- ~~**Trips**~~ (done 2026-11-14): a trips table, and an expense can belong to a trip (including a single lump sum booked for the whole trip).
- **Business**: business money counts in the household, but business items are tagged so they can be shown or hidden.
- **Recurring payments** are detected from history. Confirming one makes it a known recurring item with an expected amount and day, and the app flags when it changes or is missed.

### Income

- **Gross lines and deductions**: income is recorded gross; health and social insurance advances (ZPMV, OSSZ) are deductions, so earned, deducted and net are all visible. Neither counts as spending.
- ~~**Two dates**~~ (done 2026-11-14): the month it was earned (what reports use) and the date it arrived (what links it to the bank row). A salary paid on 2 November counts for October.
- **Costs netted against an invoice** (the old "(odpočet z faktury)") are expenses paid out of that income, with no bank account; the income stays gross.
- Income can be joint.

### Assets and net worth

- ~~**Investments become assets**~~ (done 2026-11-14) with a kind: market (fund, stock, bond, crypto; can be priced from closing prices), savings, savings scheme (building savings, pension), receivable, vehicle, property, other (valued by hand). They share contributions, valuations and fees. Still to come: liabilities (loans, what's owed on a credit card) and savings balances read from bank feeds.

### Reporting and planning

- **Saved = income − spending.** Money put into assets is part of saved; Invested shows how much of it went there.
- **The Overview** covers the whole household by default, filterable to joint or one person.
- **Budgets** are per category per month, separately for each person and for joint.
- **The forecast** projects coming months from confirmed recurring items and declared expected income.
- **Goals and the wishlist** are out for now.

### Importing the old app

- **Only expenses and income** already logged, keeping subcategories and trips; members, accounts and categories are created as needed (Sam as a member without login). Bank rows, rules, budgets and net-worth snapshots come later, on demand.
- **The old app's automatic pairings are hints, not facts**: anything it decided as `bank-sync` (all transfer links, internal-transfer and duplicate skips) isn't imported as a decision; it can serve as a test set for the new matcher.
- **Old income** is already gross, so it imports as is (earned month = its month).
- **The old Investments expenses** become contributions to assets (investment funds and a building-savings scheme), one per owner.

## Roadmap

1. ~~**Scaffold**~~ (done 2026-10-31): git repo, Gradle multi-module, Docker Compose Postgres, Ktor health endpoint, Vite app, OpenAPI type generation wired end to end.
2. ~~**MVP slice**~~ (done 2026-10-31): sign up / log in, create household, accounts, expenses with categories.
   Not yet: undo after save/delete, email verification, password reset.
   - ~~**Audit log**~~ (done 2026-10-31): trigger-written log of every create, edit and delete across households, members, invites, accounts, categories, expenses and income; the Activity page lists it by day with field-level before → after.
   - ~~**Overview**~~ (done 2026-10-31): the new landing page. The month's spending, income, saved and invested with month-end pace; a typical month (last 6 complete months, expensive outliers trimmed); 12 months of income vs. spending; unusual spending by category against the prior year; subscription radar; top merchants and income sources. Computed server-side (`/overview`) so mobile apps get the same numbers.
     Not yet: import freshness, net worth, runway and milestones (need net worth and bank import), LLM export.
   - ~~**Household invites**~~ (done 2026-10-31): owners create an email-bound, single-use invite link (7 days); there's no outgoing email yet, so the owner shares the link. Members can leave, owners can remove members, the last owner can't be removed.
   - ~~**Income**~~ (done 2026-10-31): dated income lines with a source (payer), account, recipient member (or shared) and notes; negative amounts are clawbacks. The Income page shows the month's total, spending and what was saved, plus a by-source breakdown.
     Not yet: "import typical month", expected-income plan.
   - ~~**Investments**~~ (done 2026-11-03): market holdings (fund, stock, bond, crypto, other) held by a member or jointly, with dated contributions (negative for withdrawals, optionally from an account) and dated valuations. Each holding shows what went in, what it's worth (latest valuation plus contributions since, at cost), the gain and the gain %. The Overview's Invested is now the month's net contributions; expenses in investment categories still stay out of spending but no longer count as invested, so the same money isn't counted twice.
     Market valuation (2026-11-03): a holding can follow a listed security (search by name, ticker or ISIN) and its contributions record shares; it's then valued daily from closing prices (Yahoo Finance's unofficial keyless API, behind a `MarketData` interface) converted at ČNB fixings, both cached in Postgres and refreshed at most every 6 hours. The Investments page and each holding show worth against money put in over time.
     Fees (2026-11-03): a holding records the fees its provider charged (discounts negative), shown as a running total and in its history. On a holding valued by hand they're informational: the provider already took them out of the value, so they change neither worth nor money put in. On a market-valued holding they're broker commissions paid on top of the shares, so they count as money put in and lower the gain. Each holding breaks them down by year against its average worth (a yearly rate, part years scaled up), and overall against money put in and the gain before fees. Investments show CZK in whole crowns.
     Assets (2026-11-14): investments became assets (tables, API `/assets`, the Assets page). Besides the market kinds (fund, stock, bond, crypto), an asset can be savings, a savings scheme (building savings, pension), money owed to the household, a vehicle, property or other; only market kinds can follow a security, the rest are valued by hand. Past activity entries were renamed with them.
     Not yet: loans and other liabilities, savings balances from bank feeds, money-weighted return, entering a buy in the security's currency.
   - ~~**Model for the import**~~ (done 2026-11-14):
     - Members without a login: owners add them by name, and they can't sign in. An invite can name one to claim, and whoever accepts takes their place, with everything that was theirs moving across through cascading references.
     - Accounts have a kind (bank, credit card, cash, other) and an optional IBAN, checked and unique per household.
     - Trips have optional dates and a Trips page with what each cost. An expense can belong to a trip.
     - An expense can record what it cost in another currency.
     - Income carries the month it was earned, which the Income page and Overview count it in, as well as the date it arrived.
3. **Next**: merchants, the old-app import, debts and split ratios, split lines, income deductions, inbox (bank import), budget.
4. **Later**: net worth, forecast, MCP server, mobile apps, hosting, goals, wishlist.
