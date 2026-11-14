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
| Money | Integer minor units + currency code (household default currency) |
| Repo layout | Monorepo: `server/`, `shared/`, `web/`; Gradle Kotlin DSL |
| Tests | Ktor `testApplication` + Testcontainers Postgres; Vitest for web |

## Conventions

- Every domain row has `household_id`; cross-row references are composite `(household_id, id)` foreign keys.
- Routes under `/api/households/{householdId}` go through the `HouseholdAccess` plugin: non-members get 404.
- Editable rows carry `version`; updates send it in the body, deletes as `?version=`. A stale version is a 409 `version_conflict`.
- Rows still referenced can't be deleted (409 `*_in_use`); close accounts and archive categories instead.
- Every insert, update and delete of household data lands in `audit_log` (before/after JSON, actor) via Postgres triggers, so routes don't record anything themselves. The actor comes from `app.actor_id`, which `dbQuery` sets from the request's session user; new household tables need an `audit` trigger in their migration.
- API request/response models live in `shared` (`dev.spendingtracker.shared.api`) so mobile clients can reuse them.

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
     Not yet: other net-worth assets (property, cars, loans), money-weighted return, entering a buy in the security's currency.
3. **Next**: inbox (bank import), budget.
4. **Later**: net worth, forecast, goals, wishlist, trips, MCP server, mobile apps, hosting.
