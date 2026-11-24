---
name: add-mobile-screen
description: Add one screen to the Android app (or, later, the iOS app) the same way every time: route, ViewModel + UiState + events, screen, Koin binding, and a ViewModel test with fakes. Use when adding a screen to android/ (or ios/).
---

# Add a mobile screen

One screen, end to end, in the same shape as the existing ones. Works for `android/` now and `ios/` later.

## Files it touches (Android)

1. `android/src/main/kotlin/dev/spendingtracker/android/navigation/Routes.kt` — add a `@Parcelize` route (`NavKey` + `Parcelable`), e.g. `data class Foo(val id: String) : Route`.
2. `navigation/AppNav.kt` — add an `is Foo -> NavEntry(key) { _ -> FooScreen(...) }` branch to `entryFor(...)` (and `else -> error(...)` stays last).
3. New package `android/src/main/kotlin/dev/spendingtracker/android/foo/`:
   - `FooViewModel.kt` — constructor takes the `data/Contracts.kt` interfaces it needs (plus `SessionApi`) and route params; exposes `StateFlow<FooUiState>` (`Loading` / `Ready` / `Error`) and a `Channel<FooEvent>` for one-shot things (saved, deleted, navigate). All loading goes through `viewModelScope.launch`.
   - `FooScreen.kt` — composable taking the route params, callbacks, and the injected deps (`koinViewModel(parameters = { parametersOf(...) })` / `koinInject()`); collects events in a `LaunchedEffect`; Material 3 layout, `SpendingTrackerTheme` already applied by the activity.
4. `di/AppModule.kt` — bind the new API interface if the screen needs data the shared SDK doesn't wrap yet (see `add-client-endpoint`); screen VMs are `factory`/`single` as appropriate.
5. `android/src/test/kotlin/dev/spendingtracker/android/FooViewModelTest.kt` — fake the contracts (extend `Fakes.kt`), `Dispatchers.setMain(StandardTestDispatcher())` in `@Before`, `runTest` + `advanceUntilIdle()`, assert state and events with Turbine.

## Rules

- Every screen gets a ViewModel test. No logic in the composable beyond collecting state.
- Money goes through `util/Money.kt` (`formatMoney`/`parseMoney`), category/member colours through `theme/Hues.kt` — never invent a colour or format inline.
- Look: `SpendingTrackerTheme` only; neutral chrome, colour in small doses (dots, avatars, signed amounts), Geist Mono for amounts.
- If the screen needs an endpoint the shared SDK lacks, use the `add-client-endpoint` skill first.
