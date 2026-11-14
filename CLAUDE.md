# new-spending-tracker

Read `ARCHITECTURE.md` first: stack, conventions, the data-model decisions and the roadmap. "Next" in the roadmap is where work continues. `README.md` has the run commands.

- `../household-spending-tracker` is the old app: a reference for domain rules only, its code isn't ported.
- `data/legacy/` (gitignored, real household data) holds the old app's exported D1 database; its README describes the tables. Never commit it or paste its contents anywhere outside this machine.
- The old app's automatic pairings (anything decided by `bank-sync`) are hints, not facts.

## Working notes

- Run the app with the `api` and `web` servers in `.claude/launch.json` (Postgres: `docker compose up -d --wait`). The API doesn't reload: restart it after server changes. New migrations run on start.
- After changing API endpoints or models, restart the API, then `pnpm --dir web gen:api` to regenerate `web/src/api/schema.d.ts`.
- Typecheck the web app with `pnpm --dir web exec tsc --noEmit`. Plain `tsc -b` writes `.js` files next to the sources.
- Tests: `./gradlew build` (Testcontainers, needs Docker) and `pnpm --dir web test`.
- Migrations are append-only (`server/src/main/resources/db/migration/V*.sql`); new household tables need an `audit` trigger.
