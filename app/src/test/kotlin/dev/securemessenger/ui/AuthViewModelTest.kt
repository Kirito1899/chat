package dev.securemessenger.ui

import androidx.lifecycle.SavedStateHandle
import dev.securemessenger.data.AccountRepository
import dev.securemessenger.data.remote.ApiException
import dev.securemessenger.shared.RegisterResponse
import dev.securemessenger.shared.TokenResponse
import dev.securemessenger.shared.UserResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var accounts: FakeAccountRepository
    private lateinit var viewModel: AuthViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        accounts = FakeAccountRepository()
        viewModel = AuthViewModel(accounts, SavedStateHandle())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `existing session restores chats destination`() = runTest {
        accounts.session = tokens()
        viewModel = AuthViewModel(accounts, SavedStateHandle())
        assertFalse(viewModel.state.value.restoreComplete)
        advanceUntilIdle()
        assertEquals(AuthDestination.CHATS, viewModel.state.value.destination)
        assertEquals(true, viewModel.state.value.restoreComplete)
    }

    @Test
    fun `login success enters chats and failure exposes safe code`() = runTest {
        viewModel.login("user@example.com", "password-12345")
        advanceUntilIdle()
        assertEquals(AuthDestination.CHATS, viewModel.state.value.destination)
        assertFalse(viewModel.state.value.busy)

        accounts.failLogin = true
        viewModel.logout()
        advanceUntilIdle()
        viewModel.login("user@example.com", "wrong-password-1")
        advanceUntilIdle()
        assertEquals(AuthDestination.LOGIN, viewModel.state.value.destination)
        assertEquals("INVALID_CREDENTIALS", viewModel.state.value.error)
    }

    @Test
    fun `register moves to verification and successful verification returns to login`() = runTest {
        viewModel.register("invite", "user@example.com", "test_user", "password-12345")
        advanceUntilIdle()
        assertEquals(AuthDestination.VERIFY_EMAIL, viewModel.state.value.destination)
        assertEquals("user@example.com", viewModel.state.value.email)

        viewModel.verifyEmail("12345678")
        advanceUntilIdle()
        assertEquals(AuthDestination.LOGIN, viewModel.state.value.destination)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `recovery always advances to reset screen`() = runTest {
        viewModel.recover("unknown@example.com")
        advanceUntilIdle()
        assertEquals(AuthDestination.RESET_PASSWORD, viewModel.state.value.destination)
        assertEquals("unknown@example.com", viewModel.state.value.email)
    }

    private fun tokens() = TokenResponse(
        "access",
        "refresh",
        1,
        2,
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString(),
        "test_user",
    )
}

private class FakeAccountRepository : AccountRepository {
    var session: TokenResponse? = null
    var failLogin = false

    override suspend fun restore(): TokenResponse? = session

    override suspend fun register(invite: String, email: String, username: String, password: String): RegisterResponse =
        RegisterResponse(UUID.randomUUID().toString(), email)

    override suspend fun verifyEmail(email: String, code: String) = Unit

    override suspend fun resendVerification(email: String) = Unit

    override suspend fun login(email: String, password: String): TokenResponse {
        if (failLogin) throw ApiException("INVALID_CREDENTIALS", 401)
        return TokenResponse("access", "refresh", 1, 2, UUID.randomUUID().toString(), UUID.randomUUID().toString(), "test_user")
            .also { session = it }
    }

    override suspend fun recover(email: String) = Unit

    override suspend fun resetPassword(email: String, code: String, newPassword: String) = Unit

    override suspend fun searchByUsername(username: String): UserResponse =
        UserResponse(UUID.randomUUID().toString(), username)

    override suspend fun logout() {
        session = null
    }
}
