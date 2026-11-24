---
name: add-client-endpoint
description: Add one API endpoint to the shared mobile client SDK and wire it into the apps: shared repository method in commonMain, a mock-engine test in commonTest, the app-side contract + Koin binding. Use when a mobile screen needs an endpoint the SDK doesn't wrap yet.
---

# Add a client endpoint

One endpoint, from `shared` to the app, in the same shape every time. Works for Android now and iOS later (keep `commonMain` free of platform types).

## Files it touches

1. `shared/src/commonMain/kotlin/dev/spendingtracker/shared/client/Repositories.kt` — add a thin suspend method to the matching repository (or a new one), e.g. `suspend fun listFoo(householdId: String): List<Foo> = api.get("/api/households/$householdId/foo")`. Models already live in `dev.spendingtracker.shared.api`; add them there if missing (response models: no default values).
2. `shared/src/commonTest/kotlin/dev/spendingtracker/shared/client/ApiClientTest.kt` (or a new test) — a `ktor-client-mock` test: the path and query are right, the bearer header goes out, the body decodes (see the existing tests for the `api` helper and `FakeTokenStore`).
3. `android/src/main/kotlin/dev/spendingtracker/android/data/Contracts.kt` — add the method to the app's interface for that area.
4. `android/src/main/kotlin/dev/spendingtracker/android/data/Repositories.kt` — implement it by delegating to the shared repository (`sessionManager.requireApi()`).
5. `android/src/main/kotlin/dev/spendingtracker/android/di/AppModule.kt` — only if it's a new interface; otherwise nothing.
6. Use it from a ViewModel, and cover it in that ViewModel's test with the fake in `android/src/test/.../Fakes.kt`.

## Rules

- Paths are absolute against the server root (`/api/...`); query params via `url { parameters.append(...) }`.
- Updates/deletes carry `version` (body / `?version=`); a stale one is a 409 `version_conflict` the UI handles.
- After changing server routes or models, restart the API and run `scripts/dev.sh gen-api` (web types); mobile reads the models straight from `shared`.
- Keep signatures platform-free: suspend functions, `kotlinx.datetime` types, no `Flow` unless the UI needs one (Swift interop later via SKIE).
