package dev.spendingtracker.android

import app.cash.turbine.test
import dev.spendingtracker.android.expenses.ExpensesEvent
import dev.spendingtracker.android.expenses.ExpensesUiState
import dev.spendingtracker.android.expenses.ExpensesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import dev.spendingtracker.android.expenses.DELETE_UNDO_MILLIS
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpensesViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun vm(expensesApi: FakeExpensesApi = FakeExpensesApi(), session: FakeSessionApi = FakeSessionApi()) =
        ExpensesViewModel(session, expensesApi, FakeCategoriesApi(), FakeAccountsApi(), FakeHouseholdsApi())

    @Test
    fun `loadMonth lists the month sorted by day descending`() = runTest(dispatcher) {
        val api = FakeExpensesApi(
            mutableListOf(
                fakeExpense("e1", date = LocalDate(2026, 11, 2)),
                fakeExpense("e2", date = LocalDate(2026, 11, 20)),
                fakeExpense("e3", date = LocalDate(2026, 11, 20)),
            )
        )
        val vm = vm(api)
        vm.loadMonth("2026-11")
        advanceUntilIdle()
        val state = vm.uiState.value as ExpensesUiState.Ready
        assertEquals(listOf("e3", "e2", "e1"), state.expenses.map { it.id })
    }

    @Test
    fun `delete removes the row at once and sends the DELETE only after the undo window`() = runTest(dispatcher) {
        val api = FakeExpensesApi(mutableListOf(fakeExpense("e1", version = 7)))
        val vm = vm(api)
        vm.loadMonth("2026-11")
        advanceUntilIdle()

        vm.events.test {
            vm.deleteWithUndo(fakeExpense("e1", version = 7))
            // Row gone from the list immediately, event emitted, but no DELETE yet.
            assertEquals(ExpensesEvent.Deleted(fakeExpense("e1", version = 7)), awaitItem())
            assertTrue((vm.uiState.value as ExpensesUiState.Ready).expenses.isEmpty())
            assertTrue(api.deleted.isEmpty())
            advanceTimeBy(6000)
            advanceUntilIdle()
            assertEquals(listOf("e1" to 7), api.deleted)
        }
    }

    @Test
    fun `undo restores the row and cancels the DELETE`() = runTest(dispatcher) {
        val api = FakeExpensesApi(mutableListOf(fakeExpense("e1")))
        val vm = vm(api)
        vm.loadMonth("2026-11")
        advanceUntilIdle()

        vm.events.test {
            vm.deleteWithUndo(fakeExpense("e1"))
            awaitItem() // Deleted
            vm.undoDelete(fakeExpense("e1"))
            assertEquals(ExpensesEvent.Restored(fakeExpense("e1")), awaitItem())
            advanceTimeBy(10_000)
            assertTrue(api.deleted.isEmpty())
            assertEquals(1, (vm.uiState.value as ExpensesUiState.Ready).expenses.size)
        }
    }

    @Test
    fun `a failed DELETE puts the row back and reports it`() = runTest(dispatcher) {
        val api = FakeExpensesApi(
            mutableListOf(fakeExpense("e1")),
            failDeleteWith = apiException(409, "expense_in_use", "Still referenced"),
        )
        val vm = vm(api)
        vm.loadMonth("2026-11")
        advanceUntilIdle()

        vm.events.test {
            vm.deleteWithUndo(fakeExpense("e1"))
            awaitItem() // Deleted
            advanceTimeBy(6000)
            assertEquals(ExpensesEvent.DeleteFailed("Still referenced"), awaitItem())
            assertEquals(1, (vm.uiState.value as ExpensesUiState.Ready).expenses.size)
        }
    }

    @Test
    fun `paging quickly shows the newest month even when an older one answers last`() = runTest(dispatcher) {
        val api = FakeExpensesApi(
            mutableListOf(
                fakeExpense("nov", date = LocalDate(2026, 11, 20)),
                fakeExpense("oct", date = LocalDate(2026, 10, 20)),
            ),
        )
        api.listDelayMillis = { from -> if (from.monthNumber == 11) 1_000L else 10L }
        val vm = vm(api)
        vm.loadMonth("2026-11")
        runCurrent()
        vm.loadMonth("2026-10")
        advanceUntilIdle()
        val state = vm.uiState.value as ExpensesUiState.Ready
        assertEquals("2026-10", state.month)
        assertEquals(listOf("oct"), state.expenses.map { it.id })
        assertEquals(false, vm.refreshing.value)
    }

    @Test
    fun `logging out drops the list and the pending delete, and the next login reloads`() = runTest(dispatcher) {
        val session = FakeSessionApi().apply { sessionFlow.value = fakeSession() }
        val api = FakeExpensesApi(mutableListOf(fakeExpense("e1")))
        val vm = vm(api, session)
        vm.loadMonth("2026-11")
        advanceUntilIdle()
        vm.deleteWithUndo(fakeExpense("e1"))
        runCurrent()

        session.sessionFlow.value = null
        runCurrent()
        assertTrue(vm.uiState.value is ExpensesUiState.Loading)

        advanceTimeBy(DELETE_UNDO_MILLIS + 1)
        assertTrue("the DELETE must not go out after logout", api.deleted.isEmpty())

        session.sessionFlow.value = fakeSession()
        vm.loadMonth("2026-11") // same month as before logout: must fetch, not return early
        advanceUntilIdle()
        assertEquals(listOf("e1"), (vm.uiState.value as ExpensesUiState.Ready).expenses.map { it.id })
    }
}
