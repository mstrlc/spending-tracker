package dev.spendingtracker.android

import app.cash.turbine.test
import dev.spendingtracker.android.account.AccountEvent
import dev.spendingtracker.android.account.AccountViewModel
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

class AccountViewModelTest {
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
    fun `logout ends the session and emits LoggedOut`() = runTest(dispatcher) {
        val session = FakeSessionApi()
        val vm = AccountViewModel(session)
        vm.events.test {
            vm.logout()
            advanceUntilIdle()
            assertTrue(session.loggedOut)
            assertEquals(AccountEvent.LoggedOut, awaitItem())
        }
    }
}
