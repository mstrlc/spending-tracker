package dev.spendingtracker.android

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.spendingtracker.android.expenses.ExpenseEditorViewModel
import dev.spendingtracker.android.expenses.ExpensesViewModel
import dev.spendingtracker.android.theme.SpendingTrackerTheme
import dev.spendingtracker.android.expenses.ExpenseEditorScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** One Compose UI test: the editor's Save stays disabled until a valid amount is in (the web form's validation). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ExpenseEditorScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

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
    fun saveIsDisabledUntilAnAmountIsEntered() {
        val session = FakeSessionApi()
        val expensesApi = FakeExpensesApi()
        val list = ExpensesViewModel(session, expensesApi, FakeCategoriesApi(), FakeAccountsApi(), FakeHouseholdsApi())
        val vm = ExpenseEditorViewModel(session, expensesApi, FakeCategoriesApi(), FakeAccountsApi(), FakeHouseholdsApi(), list, "2026-11", null)
        dispatcher.scheduler.advanceUntilIdle() // let the editor load

        composeRule.setContent {
            SpendingTrackerTheme {
                ExpenseEditorScreen(
                    month = "2026-11",
                    expenseId = null,
                    onDone = {},
                    sessionApi = session,
                    viewModel = vm,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Save").assertIsNotEnabled()
        composeRule.onNodeWithText("Amount").performTextInput("129.90")
        dispatcher.scheduler.advanceUntilIdle() // let the StateFlow collection deliver the new state
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Save").assertIsEnabled()
    }
}
