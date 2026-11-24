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

### Self-hosting

`scripts/deploy.sh` runs the app on any Docker host you can reach over ssh, behind a reverse proxy that terminates
TLS (Caddy, Traefik, nginx…) and sits on a Docker network. It builds the images here, so the host doesn't need the
JDK or Node, and it works on a Raspberry Pi.

1. On the host, create `~/spending-tracker/.env` from [deploy/server.env.example](deploy/server.env.example): the
   domain, the proxy's Docker network, a backup directory and a database password.
2. Here, create `deploy/local.env` (gitignored) with `DEPLOY_HOST=<ssh destination>`; optionally `DEPLOY_DIR` and
   `DEPLOY_PLATFORM` (default `linux/arm64`).
3. Run `scripts/deploy.sh`. It loads the images on the host, starts [deploy/compose.prod.yml](deploy/compose.prod.yml)
   (Postgres, API, web; no host ports), and installs a nightly `pg_dump` into `BACKUP_DIR` in root's crontab.
4. Point the proxy at `spending-web:80` for your domain. Limit who can reach it there if it's only for you (an IP
   allowlist, a VPN like Tailscale).

Signup is off by default: start once with `SIGNUP=on` in the host's `.env` to create the accounts, then remove it and
redeploy (or invite people from the household page). Rerun `scripts/deploy.sh` after changing code; new migrations
run when the API starts. A gitignored `deploy/local.md` is the place for notes on your own deployment.

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
