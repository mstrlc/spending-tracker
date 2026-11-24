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

### Android app

Requires the Android SDK (`ANDROID_HOME`). Open the repo in Android Studio and run the `android`
configuration on an emulator, or `./gradlew :android:assembleDebug` for the APK. Run `scripts/dev.sh api`
first and log in with the server URL `http://10.0.2.2:<PORT>` (the emulator's route to the host; `PORT` is in
this checkout's `.env.local`, 8080 for the main one). Plain HTTP works in debug builds only — on a device use
`adb reverse tcp:<PORT> tcp:<PORT>` and `http://localhost:<PORT>`. Worktree slots apply.

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
redeploy (or invite people from the household page). Rerun `scripts/deploy.sh` after changing code: it copies only
the images that changed (`scripts/deploy.sh api` or `web` limits it to one). New migrations run when the API starts. A gitignored `deploy/local.md` is the place for notes on your own deployment.

#### Off-site backups

`backup.sh` can also copy each nightly dump to Google Drive (or any rclone remote), encrypted with
[age](https://age-encryption.org) so the host and Google only ever hold ciphertext. The remote keeps 30 days of
dailies and the 1st of each month for a year.

1. Here: `age-keygen -o spending-backup.key`. Keep that file in a password manager, off the host: without it the
   copies can't be restored. Its `# public key: age1…` line is the recipient.
2. On the host: `sudo apt install age rclone`, then `rclone config --config ~/spending-tracker/rclone.conf` and add a
   Google Drive remote (say `gdrive`). It's headless, so answer "n" to auto config and run the `rclone authorize`
   command it prints on a machine with a browser.
3. In the host's `.env`: `BACKUP_REMOTE=gdrive:spending-tracker` and `BACKUP_AGE_RECIPIENT=age1…`. Test with
   `sudo ~/spending-tracker/backup.sh`.

Restore: `rclone --config rclone.conf cat gdrive:spending-tracker/FILE.age | age -d -i spending-backup.key > FILE`,
then `pg_restore` as in the comment at the top of [deploy/backup.sh](deploy/backup.sh).

After changing API endpoints, with the server running:

```bash
scripts/dev.sh gen-api             # regenerates web/src/api/schema.d.ts from this checkout's API
```

## Importing the old app

The household's Import page (owners only) accepts an import file with people, accounts, categories, trips,
expenses, income and assets (see `POST /import` in the API docs). `data/legacy/` (gitignored) holds the old app's
exported database and the import file made from it; the script that converted it is gone with the cutover.

## Tests

```bash
./gradlew build                    # server tests use Testcontainers (Docker must be running)
pnpm --dir web test
```
