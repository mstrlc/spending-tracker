# new-spending-tracker

Read `ARCHITECTURE.md` first: stack, conventions, the data-model decisions and the roadmap. "Next" in the roadmap is where work continues. `README.md` has the run commands.

- `../household-spending-tracker` is the old app: a reference for domain rules only, its code isn't ported.
- `data/legacy/` (gitignored, real household data) holds the old app's exported D1 database; its README describes the tables. Never commit it or paste its contents anywhere outside this machine.
- `data/order-emails/<shop>/` (gitignored, real emails) holds e-shop order emails, the order parsers' test fixtures (`orderEmailFixtures()` in the server tests). Never commit them or quote them outside this machine.
- `deploy/local.md` (gitignored, when present) describes this household's own deployment: where it runs and how it's reached. Keep host names, domains and paths out of committed files.
- The old app's automatic pairings (anything decided by `bank-sync`) are hints, not facts.

## Working notes

- Commit messages and PR titles follow [Conventional Commits](https://www.conventionalcommits.org): `type(scope): summary`, with `feat`, `fix`, `docs`, `test`, `refactor`, `chore`, `ci`, `build` or `perf`, and the area as the scope (`server`, `web`, `shared`, `android`, `plans`…). Older history predates this.

- Run `scripts/dev.sh setup` once per checkout (see "Worktrees" below); it starts Postgres and writes `.claude/launch.json`. Then run the app with its `api` and `web` servers. The API doesn't reload: restart it after server changes. New migrations run on start.
- After changing API endpoints or models, restart the API, then `scripts/dev.sh gen-api` to regenerate `web/src/api/schema.d.ts` from this checkout's API.
- Typecheck the web app with `pnpm --dir web exec tsc --noEmit`. Plain `tsc -b` writes `.js` files next to the sources.
- Tests: `./gradlew build` (Testcontainers, needs Docker) and `pnpm --dir web test`. `./gradlew build` needs the Android SDK (set `ANDROID_HOME`); without it, `./gradlew :server:build :shared:jvmTest` still works. The CI **android** job runs `:shared:allTests` until the `android/` app module exists.
- Migrations are append-only (`server/src/main/resources/db/migration/V*.sql`); new household tables need an `audit` trigger.
- Add shadcn components with `pnpm --dir web dlx shadcn@latest add <name>`. Then check the new files' imports: the CLI has written `import { cn } from "cn"` (and installed an unrelated `cn` package) instead of `@/lib/utils`. Fix the imports and `pnpm --dir web remove cn` if it reappears.

## Android

- The app lives in `android/` (package `dev.spendingtracker.android`), a `com.android.application` module depending on `project(":shared")` for the API models and client SDK (`dev.spendingtracker.shared.client`). AGP 9 applies Kotlin itself; only the Compose and serialization plugins are declared. Build with `ANDROID_HOME` set: `./gradlew :android:assembleDebug :android:testDebugUnitTest :android:lintDebug`. CI's **android** job runs those plus `:shared:allTests`.
- Stack: Jetpack Compose + Material 3, Navigation 3, Koin. The look follows the web: `theme/Theme.kt` holds the web's oklch palette converted to sRGB (light/dark follow the system) and bundles Geist/Geist Mono in `res/font` (amounts in mono, like the web's `amount`); `theme/Hues.kt` assigns category/member hues exactly like `web/src/chips.tsx`, so a category looks the same in both apps.
- Structure: one package per screen (`login/`, `households/`, `overview/`, `expenses/`, `navigation/`), each screen a `ViewModel` exposing one `StateFlow<UiState>` plus a `Channel` of events. The data layer is interfaces (`data/Contracts.kt`) over the shared repositories, so tests fake them (`android/src/test/.../Fakes.kt`). Every screen gets a ViewModel test; Compose UI tests run on Robolectric.
- The bearer token sits in DataStore encrypted with a Tink key from the Android Keystore (`data/EncryptedTokenStore.kt`); the server URL is remembered in `data/ServerUrlStore.kt` (default `http://10.0.2.2:8080`). Debug builds allow cleartext to 10.0.2.2/localhost via `src/debug/res/xml/network_security_config.xml`; release is HTTPS-only and the login screen refuses `http://`.
- Web UI conventions (which components, `ui.tsx` helpers, confirm dialogs, the side panel) are under "Web UI" in `ARCHITECTURE.md`.

## Worktrees

Several agents can work at once, each in its own git worktree, with their own running app. Nothing is shared between them except the Postgres container and the Gradle and pnpm caches.

- Each checkout has a slot. The main checkout is slot 0: API 8080, web 5173, database `spending_tracker`. A worktree gets the lowest free slot from 1 up: API `8100 + n`, web `5300 + n`, database `spending_tracker_wt<n>` in the same container (Postgres on 5433; `docker-compose.yml` pins the Compose project name so every worktree uses that one container).
- First thing in a new worktree, run `scripts/dev.sh setup`. It claims the slot, creates the database (empty; migrations fill the schema on the API's first start, so sign up afresh), installs `web/node_modules` if missing, and writes two gitignored files: `.env.local` (`PORT`, `WEB_PORT`, `DB_URL`, `APP_URL`, `API_URL`) and `.claude/launch.json` (the `api` and `web` servers on this slot's ports, with `autoPort: false`, so a busy port fails loudly instead of drifting onto another checkout's). `setup --copy` fills the new database from the main checkout's instead, with its households and logins; that's real household data, so keep it on this machine.
- Run servers only through `.claude/launch.json` or `scripts/dev.sh api|web`; both read `.env.local`. Never pass ports by hand or point at another checkout's ports: the web app's `/api` proxy and the API's database both come from `.env.local`. The main checkout's `.env` (GoCardless keys) is loaded too.
- Regenerate API types with `scripts/dev.sh gen-api`, which reads this checkout's API, not whatever runs on 8080.
- `./gradlew build` and `pnpm --dir web test` need no slot: the server tests start their own Testcontainers database.
- `scripts/dev.sh status` lists every slot with its ports (`*` when listening). Before removing a worktree, run `scripts/dev.sh teardown` in it: it drops the database and frees the slot. Slots of worktrees deleted without it are freed by the next `setup` or `status`, but their databases stay until dropped by hand.
- The slot registry lives in the shared git directory (`.git/dev-slots`), so every worktree sees it.
- Claude Code's preview tool only reads the `launch.json` of the folder the session was started in. A session started in the main checkout that needs a worktree's servers can add a server there that runs `sh -c "cd <worktree> && exec scripts/dev.sh api"` on that slot's port.
