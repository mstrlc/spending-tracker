# Spending Tracker

Multi-tenant spending tracker. See [ARCHITECTURE.md](ARCHITECTURE.md) for decisions and roadmap.

## Layout

- `server/`: Ktor API (Kotlin, JVM 21 bytecode), Flyway migrations in `src/main/resources/db/migration`
- `shared/`: Kotlin Multiplatform domain code for server and future mobile apps
- `web/`: React + Vite frontend with shadcn/ui and Tailwind CSS; API types generated from the server's OpenAPI spec

## Running locally

Requires a JDK (21+), Docker, Node and pnpm.

```bash
scripts/dev.sh setup               # once per checkout: Postgres on localhost:5433, this checkout's ports and database
scripts/dev.sh api                 # API on http://localhost:8080 (docs at /api/docs)
scripts/dev.sh web                 # web on http://localhost:5173, proxies /api to the server
```

That's for the main checkout. A git worktree gets its own ports and database, so several can run side by side;
see "Worktrees" in [AGENTS.md](AGENTS.md). `scripts/dev.sh status` lists them.

Local demo account (created by hand during development, lives only in your local database):
`demo@example.test` / `demo-password-123`. Or sign up with any email at http://localhost:5173/signup.

### Production build, all in Docker

Only Docker needed. Builds the API and the web app and serves both on one origin through Caddy, against the same database as dev:

```bash
docker compose --profile app up -d --build --wait   # app on http://localhost:8000
docker compose --profile app down                   # stop it (data stays in the pgdata volume)
```

Rerun the first command after pulling or changing code to rebuild.

After changing API endpoints, with the server running:

```bash
scripts/dev.sh gen-api             # regenerates web/src/api/schema.d.ts from this checkout's API
```

## Importing the old app

The household's Import page (owners only) accepts an import file with people, accounts, categories, trips,
expenses, income and assets (see `POST /import` in the API docs). `data/legacy/` (gitignored) holds the old app's
exported database; convert it to an import file with Node 22.5+ (built-in `node:sqlite`).

## Tests

```bash
./gradlew build                    # server tests use Testcontainers (Docker must be running)
pnpm --dir web test
```
