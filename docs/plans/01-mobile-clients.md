# 1. Mobile clients: Android first, iOS ready

## Problem

The decisions table promises "KMP `shared` module for server + future mobile apps", and `shared/build.gradle.kts` says mobile targets are added "when the apps start". There is no mobile app yet. The API models already live in `dev.spendingtracker.shared.api`, so a native client can reuse them as they are.

Goal: an Android app (Jetpack Compose) that covers the everyday loop: log in, see the month, list expenses, add/edit/delete one. Built so an iOS app (SwiftUI) later adds UI and little else.

Not a goal: parity with the web. The Inbox, bank feeds, rules, assets, debts, trips, reports and settings stay web-only.

## Approach

- **Same repo, new Gradle module `android/`**, next to `server/`, `shared/` and `web/`. It depends on `project(":shared")`, so models and client code compile once; a change that touches server, shared and app lands in one commit.
- **Share the data layer, keep the UI native.** `shared` gains the API client, repositories and session handling in `commonMain`. Screens and screen state stay per platform: Compose + `ViewModel` on Android, SwiftUI + `@Observable` models on iOS. No Compose Multiplatform for iOS: the iOS app should be idiomatic SwiftUI.
- **Bearer tokens for mobile, cookies stay for the web.** Sessions are already random tokens stored hashed (`Tokens`, `SessionStore`); only the transport changes. A header can't be sent cross-site by a browser, so it adds no CSRF surface.
- **Self-hosted first.** The app asks for the server URL on the login screen (defaults to `http://10.0.2.2:8080`, the host's API from the Android emulator). No hosted backend.

## Phases

### Phase 0: CI and bearer auth (small)

- CI first: `.github/workflows/ci.yml` with the **server** and **web** jobs ("Build and CI" below). The **android** job comes in phase 1, once there's something for it to build.
- `SessionAuth.kt`: the `session` provider reads `Authorization: Bearer <token>` first, else the `sid` cookie. When the token came from the header, don't call `setSessionCookie` on renewal (the expiry still extends in `SessionStore.find`).
- `AuthRoutes.kt`: `POST /api/auth/token` takes `LoginRequest`, returns `TokenResponse(token, me)` and sets no cookie. Same rate limit and same wrong-password response as `/login`. `POST /api/auth/logout` deletes the session from either the header or the cookie.
- `shared/api/Auth.kt`: add `TokenResponse(token: String, me: Me)`.
- OpenAPI: document the bearer scheme next to the cookie one; `scripts/dev.sh gen-api` afterwards (the web types change only by the new endpoint).
- Tests (`server/src/test`, Testcontainers like the rest): token login works and sets no cookie; header-authenticated `GET /auth/me` works; a bad or expired bearer gets 401 `unauthorized`; logout with the header kills the session; the cookie flow is unchanged.
- No migration.

### Phase 1: `shared` becomes a client SDK

- Targets: keep `jvm()` and add Android through the **Android-KMP library plugin** (`com.android.kotlin.multiplatform.library`, configured in an `androidLibrary {}` block inside `kotlin {}`), not the older `com.android.library` + `androidTarget()` pair. Add it and the Android Gradle Plugin to the root `build.gradle.kts` (`apply false`) and `libs.versions.toml`; pick the newest AGP that supports the repo's Kotlin (2.4.21) and Gradle versions, and check that before writing code. Leave `iosArm64()` / `iosSimulatorArm64()` for phase 3 so the Android build doesn't need Xcode, but keep `commonMain` free of JVM-only APIs from the start (check: `kotlin.uuid` and `kotlinx.datetime` are fine; no `java.*`).
- One `Json` config in `shared` (`ApiJson`, today `apiJson = Json { ignoreUnknownKeys = true }` in `Application.kt`), used by the server and the client so they can't drift. Response models have no defaults (Conventions), so a missing field fails loudly in the client too.
- `dev.spendingtracker.shared.client` (new package, `commonMain`):
  - `ApiClient(baseUrl, tokenStore)`: a Ktor `HttpClient` with content negotiation, the bearer header from `TokenStore`, `ApiError` decoded from non-2xx into a typed `ApiException(status, error)`, and 401 clearing the token.
  - Engines per platform: OkHttp in `androidMain`, Darwin in `iosMain` later, Java (already used by the server) in `jvmMain` for tests.
  - `TokenStore` as an interface in common; the platform supplies it (`androidMain`: DataStore, encrypted with an Android Keystore key; iOS: Keychain).
  - Thin repositories, one per area the app uses: `AuthRepository` (token login, me, logout), `HouseholdRepository` (`listHouseholds`, `listMembers`), `OverviewRepository` (`/overview?month=`), `ExpenseRepository` (list by `from`/`to`, create, update with `version`, delete with `version`), `CategoryRepository`, `AccountRepository`. Suspend functions and `Flow`s, no platform types in signatures (keeps the Swift interop clean later).
- `shared` stays free of DI frameworks; the app wires it by hand or with Koin (below).
- Tests in `commonTest` with `ktor-client-mock` (already in the catalog): header sent, `ApiError` mapping, 401 clears the token, `version_conflict` surfaces as its own error.
- CI: add the **android** job, running `:shared:allTests` for now; phase 2 extends it.

### Phase 2: the Android app

- Module `android/` (`com.android.application`), package `dev.spendingtracker.android`, min SDK 26, target the latest.
- Plain HTTP for local development only: Android blocks cleartext traffic by default, so a login to `http://10.0.2.2:<PORT>` would fail with an unclear error. Add a network security config in the **debug** source set that allows cleartext for `10.0.2.2` and `localhost` only; release builds stay HTTPS-only. The login screen says so when a release build is given an `http://` URL.
- Stack: Kotlin (same version as the rest), Jetpack Compose with the Compose BOM, Material 3, Navigation 3, `lifecycle-viewmodel-compose`, Koin for DI (works in KMP, so iOS can reuse the module graph later; Hilt is Android-only).
- Screens (each a `ViewModel` exposing one `StateFlow<UiState>`, unidirectional events):
  1. **Login**: server URL, email, password. Remembers the URL; token goes to `TokenStore`.
  2. **Household picker**: skipped when there's one household.
  3. **Overview**: the month's `thisMonth` (spending, income, saved, invested with pace), top categories and merchants, previous/next month. Read-only.
  4. **Expenses**: the month's list grouped by day, pull to refresh, merchant, category dot, amount (`Money.kt` formatting, tabular figures like the web's `amount`).
  5. **Expense editor** (sheet or full screen): date, amount, merchant (free text, like the API), category, account, who paid, notes. Create and edit with `version`; delete with `version` and an Undo snackbar (mirrors `useUndoableDelete`: delay the `DELETE` until the snackbar closes). A `409 version_conflict` reloads the expense and says so.
- Look: follow the web's restraint (neutral chrome, colour only for meaning, dark mode follows the system). Category hues from the same `--hue-1..8` order as the web, so a category looks the same everywhere.
- Out of scope for the first pass: splits and lines (show "Edit on the web" for expenses with `lines` or a custom `split`), refunds, foreign currency, offline writes. Lists can cache in memory; no Room yet.
- Tests: ViewModel unit tests with fake repositories and Turbine (login errors, month paging, save, conflict, undo delete); one Compose UI test for the editor's validation.

### Phase 3: iOS (later, separate plan when it starts)

- `shared`: add `iosArm64()` and `iosSimulatorArm64()`, Darwin engine, Keychain `TokenStore`, and export an XCFramework (or SPM via KMMBridge). Use SKIE so `suspend` becomes `async` and `Flow` becomes `AsyncSequence`.
- `ios/`: an Xcode project with the same five screens in SwiftUI, `@Observable` screen models calling the shared repositories.
- CI: macOS runners count 10× against private-repo minutes, so an iOS job runs only on `workflow_dispatch` and when the PR is ready for review, never per push. Check SKIE's support for the repo's Kotlin version first.
- Expected size: mostly SwiftUI, if phase 1 kept platform types out of `commonMain`.

## Build and CI

- `settings.gradle.kts`: `include(":android")`. `./gradlew build` then needs the Android SDK; keep `./gradlew :server:build :shared:jvmTest` working without it and say so in `AGENTS.md` ("Working notes").
- GitHub Actions (`.github/workflows/ci.yml`; the repo has no CI yet), three parallel jobs:
  - **server** (phase 0): JDK 21, `./gradlew :server:build :shared:jvmTest` (Testcontainers uses the runner's Docker).
  - **web** (phase 0): pnpm, `pnpm --dir web install --frozen-lockfile`, `pnpm --dir web exec tsc --noEmit`, `pnpm --dir web test`.
  - **android** (phase 1, extended in phase 2): JDK 21 + Android SDK, `./gradlew :shared:allTests`, then also `:android:testDebugUnitTest :android:lintDebug :android:assembleDebug`.
  - Gradle cache via `gradle/actions/setup-gradle`, pnpm cache via `actions/setup-node`; no secrets needed (GoCardless keys aren't used by tests).
- **Keep it cheap.** The repo is private, so every run counts against the account's Actions minutes (a full run is roughly 15–20 Linux minutes):
  - Triggers: `pull_request` with `types: [opened, synchronize, reopened, ready_for_review]`, pushes to `main`, and `workflow_dispatch` for a run by hand. Every job has `if: github.event_name != 'pull_request' || !github.event.pull_request.draft`, so **draft PRs don't run CI**.
  - `concurrency: { group: ci-${{ github.ref }}, cancel-in-progress: true }`: a new push cancels the run still going.
  - Path filters per job (a small `changes` job with `dorny/paths-filter`): **server** on `server/**`, `shared/**`, Gradle files; **web** on `web/**`; **android** on `android/**`, `shared/**`, Gradle files. The workflow file itself triggers all of them.
  - `timeout-minutes` on every job (server 20, android 25, web 10), so a hung Testcontainers or emulator setup can't burn an hour.
  - While a phase is in progress, check locally (`./gradlew build`, the web commands above) before pushing; run CI once per phase by marking the PR ready for review, then back to draft if more work follows. (`workflow_dispatch` only works once the workflow is on `main`, so it can't run this branch before the PR merges.) The workflow needs `pull-requests: read` for `paths-filter` to list a PR's files.
  - If the repo goes public, standard Linux runners are free and these limits matter less; keep them anyway.
- `README.md` "Running locally": add "Android: open the repo in Android Studio, run `scripts/dev.sh api`, run the `android` configuration on an emulator, log in with the server URL `http://10.0.2.2:<PORT>`". Worktree slots apply (`PORT` from `.env.local`).

## Agent setup (part of the work, not an afterthought)

- `AGENTS.md`: an "Android" section (module layout, where screen state lives, "every screen gets a ViewModel test", how to run on the emulator against this checkout's slot).
- Two reusable skills under `.agents/`: `add-mobile-screen` (route, ViewModel + UiState, screen, test) and `add-client-endpoint` (shared repository method, mock-engine test, wire into Koin). Each lists the files it touches so it works the same for iOS later.

## Decided

- Min SDK 26, Koin, GitHub Actions CI in this work, no CI runs on draft PRs (above).
- Work happens on the `android-client` branch, one PR into `main`, with a commit per phase.
- Demo data comes later, in its own plan: a `scripts/dev.sh seed-demo` with a fake household (members, accounts, categories, a few months of expenses), needed before the repo goes public. Until then, test against a fresh signup or a `setup --copy` database (real data: keep screenshots of it off GitHub).
- Open-sourcing is a separate decision (license, the example card digits in docs and tests, the private `origin`); this plan doesn't depend on it.

## Done when

- Phase 0: one CI run on the PR (marked ready for review) is green (server and web jobs), and a push to the draft PR starts no run; token login and header auth pass their tests; the web is unchanged; `./gradlew build` passes.
- Phase 1: `shared` builds for JVM and Android with the Android-KMP library plugin; `commonTest` passes; the server uses the shared `Json`; the android CI job is green.
- Phase 2: on an emulator against a local API over plain HTTP (debug build), you can log in, page through months on the Overview, list expenses, add, edit and delete one (with undo and a handled conflict); unit and UI tests pass; `README.md`, `AGENTS.md` and the two skills are in; the roadmap in `ARCHITECTURE.md` gets a "Mobile clients" entry in its usual style, with iOS as "Not yet".
