package dev.spendingtracker.android

import app.cash.turbine.test
import dev.spendingtracker.android.login.LoginEvent
import dev.spendingtracker.android.login.LoginViewModel
import dev.spendingtracker.android.login.LoginUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginViewModelTest {
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
    fun `successful login emits LoggedIn and passes the server url through`() = runTest(dispatcher) {
        val session = FakeSessionApi()
        val vm = LoginViewModel(session)
        vm.events.test {
            vm.onServerUrlChange("http://10.0.2.2:8080")
            vm.onEmailChange("a@b.c")
            vm.onPasswordChange("secret")
            vm.submit(isDebugBuild = true)
            assertEquals(LoginEvent.LoggedIn, awaitItem())
        }
        assertEquals(Triple("http://10.0.2.2:8080", "a@b.c", "secret"), session.lastLogin)
    }

    @Test
    fun `a failed login surfaces the server's message and emits nothing`() = runTest(dispatcher) {
        val session = FakeSessionApi(loginResult = Result.failure(apiException(401, "invalid_credentials", "Wrong email or password")))
        val vm = LoginViewModel(session)
        vm.events.test {
            vm.onEmailChange("a@b.c")
            vm.onPasswordChange("nope")
            vm.submit(isDebugBuild = true)
            expectNoEvents()
        }
        advanceUntilIdle()
        val state = vm.uiState.value
        assertEquals("Wrong email or password", state.error)
        assertEquals(false, state.loading)
    }

    @Test
    fun `an unreachable server surfaces a friendly error and emits nothing`() = runTest(dispatcher) {
        val session = FakeSessionApi(loginResult = Result.failure(java.net.ConnectException("Failed to connect to /192.168.0.8:9090")))
        val vm = LoginViewModel(session)
        vm.events.test {
            vm.onEmailChange("a@b.c")
            vm.onPasswordChange("secret")
            vm.submit(isDebugBuild = true)
            expectNoEvents()
        }
        advanceUntilIdle()
        val state = vm.uiState.value
        assertTrue(state.error!!.contains("Couldn't reach"))
        assertTrue(state.error!!.contains("http://10.0.2.2:8080"))
        assertEquals(false, state.loading)
    }

    @Test
    fun `a 404 from login means the server is too old for the app`() = runTest(dispatcher) {
        val session = FakeSessionApi(loginResult = Result.failure(apiException(404, "not_found")))
        val vm = LoginViewModel(session)
        vm.onEmailChange("a@b.c")
        vm.onPasswordChange("secret")
        vm.submit(isDebugBuild = true)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.error!!.contains("too old"))
    }

    @Test
    fun `a bare server domain is logged in as https`() = runTest(dispatcher) {
        val session = FakeSessionApi()
        val vm = LoginViewModel(session)
        vm.onServerUrlChange("spend.brq.mstrlc.eu")
        vm.onEmailChange("a@b.c")
        vm.onPasswordChange("secret")
        vm.submit(isDebugBuild = true)
        advanceUntilIdle()
        assertEquals(Triple("https://spend.brq.mstrlc.eu", "a@b.c", "secret"), session.lastLogin)
    }

    @Test
    fun `a release build refuses a plain http server url`() = runTest(dispatcher) {
        val session = FakeSessionApi()
        val vm = LoginViewModel(session)
        vm.onEmailChange("a@b.c")
        vm.onPasswordChange("secret")
        vm.submit(isDebugBuild = false) // default url is http://10.0.2.2:8080
        advanceUntilIdle()
        val state = vm.uiState.value as LoginUiState
        assertTrue(state.error!!.contains("https"))
        assertNull(session.lastLogin)
    }

    @Test
    fun `submit is a no-op while a field is blank`() = runTest(dispatcher) {
        val session = FakeSessionApi()
        val vm = LoginViewModel(session)
        vm.submit(isDebugBuild = true)
        assertNull(session.lastLogin)
    }
}
