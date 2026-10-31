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
- API request/response models live in `shared` (`dev.spendingtracker.shared.api`) so mobile clients can reuse them.

## Roadmap

1. ~~**Scaffold**~~ (done 2026-10-31): git repo, Gradle multi-module, Docker Compose Postgres, Ktor health endpoint, Vite app, OpenAPI type generation wired end to end.
2. ~~**MVP slice**~~ (done 2026-10-31): sign up / log in, create household, accounts, expenses with categories.
   Not yet: audit log, undo after save/delete, email verification, password reset.
   - ~~**Household invites**~~ (done 2026-10-31): owners create an email-bound, single-use invite link (7 days); there's no outgoing email yet, so the owner shares the link. Members can leave, owners can remove members, the last owner can't be removed.
   - ~~**Income**~~ (done 2026-10-31): dated income lines with a source (payer), account, recipient member (or shared) and notes; negative amounts are clawbacks. The Income page shows the month's total, spending and what was saved, plus a by-source breakdown.
     Not yet: "import typical month", expected-income plan.
3. **Next**: inbox (bank import), budget.
4. **Later**: net worth, forecast, goals, wishlist, trips, MCP server, mobile apps, hosting.
