# Spending Tracker

Multi-tenant spending tracker. See [ARCHITECTURE.md](ARCHITECTURE.md) for decisions and roadmap.

## Layout

- `server/`: Ktor API (Kotlin, JVM 21 bytecode), Flyway migrations in `src/main/resources/db/migration`
- `shared/`: Kotlin Multiplatform domain code for server and future mobile apps
- `web/`: React + Vite frontend; API types generated from the server's OpenAPI spec

## Running locally

Requires a JDK (21+), Docker, Node and pnpm.

```bash
docker compose up -d --wait        # Postgres on localhost:5433
./gradlew :server:run              # API on http://localhost:8080 (docs at /api/docs)
pnpm --dir web install
pnpm --dir web dev                 # web on http://localhost:5173, proxies /api to the server
```

Local demo account (created by hand during development, lives only in your local database):
`demo@example.test` / `demo-password-123`. Or sign up with any email at http://localhost:5173/signup.

After changing API endpoints, with the server running:

```bash
pnpm --dir web gen:api             # regenerates web/src/api/schema.d.ts
```

## Tests

```bash
./gradlew build                    # server tests use Testcontainers (Docker must be running)
pnpm --dir web test
```
