package dev.spendingtracker.android

import app.cash.turbine.test
import dev.spendingtracker.android.overview.OverviewUiState
import dev.spendingtracker.android.overview.OverviewViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OverviewViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `load fetches the month and becomes Ready`() = runTest(dispatcher) {
        val api = FakeOverviewApi(fakeOverview("2026-11"))
        val vm = OverviewViewModel(FakeSessionApi(), api, FakeCategoriesApi(), "2026-11")
        vm.load("2026-11")
        advanceUntilIdle()
        val state = vm.uiState.value
        assertTrue(state is OverviewUiState.Ready)
        assertEquals("2026-11", (state as OverviewUiState.Ready).overview.month)
    }

    @Test
    fun `changing the month loads the new month`() = runTest(dispatcher) {
        val api = FakeOverviewApi(fakeOverview("2026-10"))
        val vm = OverviewViewModel(FakeSessionApi(), api, FakeCategoriesApi(), "2026-11")
        vm.load("2026-11")
        vm.load("2026-10")
        advanceUntilIdle()
        val state = vm.uiState.value as OverviewUiState.Ready
        assertEquals("2026-10", state.overview.month)
    }

    @Test
    fun `a failed load becomes Error when nothing is shown yet`() = runTest(dispatcher) {
        val api = FakeOverviewApi(null) // errors
        val vm = OverviewViewModel(FakeSessionApi(), api, FakeCategoriesApi(), "2026-11")
        vm.load("2026-11")
        advanceUntilIdle()
        assertTrue(vm.uiState.value is OverviewUiState.Error)
    }

    @Test
    fun `a failed reload keeps the previous month on screen`() = runTest(dispatcher) {
        val api = FakeOverviewApi(fakeOverview("2026-11"))
        val vm = OverviewViewModel(FakeSessionApi(), api, FakeCategoriesApi(), "2026-11")
        vm.load("2026-11")
        advanceUntilIdle()
        api.overview = null // next load fails
        vm.load("2026-10")
        advanceUntilIdle()
        val state = vm.uiState.value as OverviewUiState.Ready
        assertEquals("2026-11", state.overview.month)
    }

    @Test
    fun `paging quickly shows the newest month even when an older one answers last`() = runTest(dispatcher) {
        val api = FakeOverviewApi().apply {
            byMonth = mapOf(
                "2026-11" to (1_000L to fakeOverview("2026-11")),
                "2026-10" to (10L to fakeOverview("2026-10")),
            )
        }
        val vm = OverviewViewModel(FakeSessionApi(), api, FakeCategoriesApi(), "2026-11")
        vm.load("2026-11")
        runCurrent()
        vm.load("2026-10")
        advanceUntilIdle()
        assertEquals("2026-10", (vm.uiState.value as OverviewUiState.Ready).overview.month)
        assertEquals(false, vm.refreshing.value)
    }
}
