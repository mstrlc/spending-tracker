package dev.spendingtracker.android

import app.cash.turbine.test
import dev.spendingtracker.android.expenses.ExpenseEditorViewModel
import dev.spendingtracker.android.expenses.EditorEvent
import dev.spendingtracker.android.expenses.EditorUiState
import dev.spendingtracker.android.expenses.ExpensesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpenseEditorViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun vms(expenseId: String? = null, expensesApi: FakeExpensesApi = FakeExpensesApi()): Pair<ExpenseEditorViewModel, FakeExpensesApi> {
        val session = FakeSessionApi()
        val list = ExpensesViewModel(session, expensesApi, FakeCategoriesApi(), FakeAccountsApi(), FakeHouseholdsApi())
        val vm = ExpenseEditorViewModel(session, expensesApi, FakeCategoriesApi(), FakeAccountsApi(), FakeHouseholdsApi(), list, "2026-11", expenseId)
        return vm to expensesApi
    }

    @Test
    fun `an invalid amount shows an error and saves nothing`() = runTest(dispatcher) {
        val (vm, api) = vms()
        advanceUntilIdle()
        vm.onAmountChange("abc")
        vm.save()
        advanceUntilIdle()
        val state = vm.uiState.value as EditorUiState.Ready
        assertNotNull(state.editor.error)
        assertNull(api.lastInput)
    }

    @Test
    fun `saving a new expense posts the input and emits Saved`() = runTest(dispatcher) {
        val (vm, api) = vms()
        advanceUntilIdle()
        vm.events.test {
            vm.onAmountChange("129.90")
            vm.onMerchantChange("Alza")
            vm.onCategoryChange("c1")
            vm.save()
            advanceUntilIdle()
            val event = awaitItem() as EditorEvent.Saved
            assertEquals(-12990L, event.expense.amountMinor) // expenses are negative
            assertEquals(-12990L, api.lastInput!!.amountMinor)
            assertNull(api.lastInput!!.version) // create carries no version
        }
    }

    @Test
    fun `saving an existing expense sends its version`() = runTest(dispatcher) {
        val existing = fakeExpense("e1", version = 3, amountMinor = -500)
        val api = FakeExpensesApi(mutableListOf(existing))
        val (vm, _) = vms("e1", api)
        advanceUntilIdle()
        vm.events.test {
            vm.onAmountChange("5")
            vm.save()
            advanceUntilIdle()
            awaitItem()
        }
        assertEquals(3, api.lastInput!!.version)
    }

    @Test
    fun `a version conflict reloads the expense and says so`() = runTest(dispatcher) {
        val existing = fakeExpense("e1", version = 3)
        val api = FakeExpensesApi(mutableListOf(existing), failUpdateWith = apiException(409, "version_conflict", "Changed elsewhere"))
        val session = FakeSessionApi()
        val list = ExpensesViewModel(session, api, FakeCategoriesApi(), FakeAccountsApi(), FakeHouseholdsApi())
        val vm = ExpenseEditorViewModel(session, api, FakeCategoriesApi(), FakeAccountsApi(), FakeHouseholdsApi(), list, "2026-11", "e1")
        advanceUntilIdle()
        vm.onAmountChange("9.99")
        vm.save()
        advanceUntilIdle()
        val state = vm.uiState.value as EditorUiState.Ready
        assertTrue(state.editor.error!!.contains("another device"))
    }
}
