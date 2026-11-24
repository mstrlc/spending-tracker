package dev.spendingtracker.android

import app.cash.turbine.test
import dev.spendingtracker.android.households.HouseholdsEvent
import dev.spendingtracker.android.households.HouseholdsUiState
import dev.spendingtracker.android.households.HouseholdsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HouseholdsViewModelTest {
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
    fun `a single household is picked automatically and emitted`() = runTest(dispatcher) {
        val session = FakeSessionApi()
        val vm = HouseholdsViewModel(session, FakeHouseholdsApi(listOf(fakeHousehold)))
        advanceUntilIdle()
        vm.events.test {
            assertEquals(HouseholdsEvent.Open(fakeHousehold), awaitItem())
        }
        assertEquals(fakeHousehold, session.household.value)
    }

    @Test
    fun `multiple households become a list and pick emits Open`() = runTest(dispatcher) {
        val other = fakeHousehold.copy(id = "h2", name = "Cabin")
        val session = FakeSessionApi()
        val vm = HouseholdsViewModel(session, FakeHouseholdsApi(listOf(fakeHousehold, other)))
        advanceUntilIdle()
        val state = vm.uiState.value as HouseholdsUiState.Ready
        assertEquals(2, state.households.size)
        vm.events.test {
            vm.pick(other)
            advanceUntilIdle()
            assertEquals(HouseholdsEvent.Open(other), awaitItem())
        }
        assertEquals(other, session.household.value)
    }
}
