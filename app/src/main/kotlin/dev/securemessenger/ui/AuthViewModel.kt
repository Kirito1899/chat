package dev.securemessenger.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.securemessenger.data.AccountRepository
import dev.securemessenger.data.remote.ApiException
import dev.securemessenger.shared.RegisterResponse
import dev.securemessenger.shared.TokenResponse
import dev.securemessenger.shared.ValidationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

enum class AuthDestination {
    LOGIN,
    REGISTER,
    VERIFY_EMAIL,
    RECOVERY,
    RESET_PASSWORD,
    CHATS,
}

data class AuthUiState(
    val destination: AuthDestination = AuthDestination.LOGIN,
    val email: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val info: String? = null,
    val session: TokenResponse? = null,
    val restoreComplete: Boolean = false,
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val accounts: AccountRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        AuthUiState(
            destination = savedDestination(),
            email = savedState[EMAIL] ?: "",
        ),
    )
    val state: StateFlow<AuthUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            val session = accounts.restore()
            if (session != null) {
                mutableState.value = mutableState.value.copy(session = session, restoreComplete = true)
                navigate(AuthDestination.CHATS)
            } else {
                mutableState.value = mutableState.value.copy(restoreComplete = true)
                if (mutableState.value.destination == AuthDestination.CHATS) {
                    navigate(AuthDestination.LOGIN)
                }
            }
        }
    }

    fun goToRegistration() = navigate(AuthDestination.REGISTER)
    fun goToLogin() = navigate(AuthDestination.LOGIN)
    fun goToRecovery() = navigate(AuthDestination.RECOVERY)

    fun register(invite: String, email: String, username: String, password: String) = launch {
        val response = accounts.register(invite, email, username, password)
        mutableState.value = mutableState.value.copy(email = response.email, info = "VERIFICATION_SENT")
        navigate(AuthDestination.VERIFY_EMAIL)
    }

    fun verifyEmail(code: String) = launch {
        val email = mutableState.value.email
        accounts.verifyEmail(email, code)
        mutableState.value = mutableState.value.copy(info = "EMAIL_VERIFIED")
        navigate(AuthDestination.LOGIN)
    }

    fun resendVerification() = launch {
        accounts.resendVerification(mutableState.value.email)
        mutableState.value = mutableState.value.copy(info = "VERIFICATION_SENT")
    }

    fun login(email: String, password: String) = launch {
        val session = accounts.login(email, password)
        mutableState.value = mutableState.value.copy(session = session, email = email)
        navigate(AuthDestination.CHATS)
    }

    fun recover(email: String) = launch {
        accounts.recover(email)
        mutableState.value = mutableState.value.copy(email = email, info = "RECOVERY_SENT")
        navigate(AuthDestination.RESET_PASSWORD)
    }

    fun resetPassword(code: String, newPassword: String) = launch {
        accounts.resetPassword(mutableState.value.email, code, newPassword)
        mutableState.value = mutableState.value.copy(info = "PASSWORD_CHANGED")
        navigate(AuthDestination.LOGIN)
    }

    fun logout() = launch {
        accounts.logout()
        mutableState.value = AuthUiState(restoreComplete = true)
        navigate(AuthDestination.LOGIN)
    }

    fun clearMessages() {
        mutableState.value = mutableState.value.copy(error = null, info = null)
    }

    private fun navigate(destination: AuthDestination) {
        savedState[DESTINATION] = destination.name
        mutableState.value = mutableState.value.copy(destination = destination, error = null)
    }

    private fun launch(block: suspend () -> Unit) {
        if (mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(busy = true, error = null, info = null)
        viewModelScope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: ApiException) {
                mutableState.value = mutableState.value.copy(error = error.code)
            } catch (error: ValidationException) {
                mutableState.value = mutableState.value.copy(error = error.code)
            } catch (_: IOException) {
                mutableState.value = mutableState.value.copy(error = "NETWORK_ERROR")
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(error = "INTERNAL_ERROR")
            } finally {
                mutableState.value = mutableState.value.copy(busy = false)
            }
        }
    }

    private fun savedDestination(): AuthDestination {
        val value = savedState.get<String>(DESTINATION)
        return value?.let { runCatching { AuthDestination.valueOf(it) }.getOrNull() }
            ?.takeUnless { it == AuthDestination.CHATS }
            ?: AuthDestination.LOGIN
    }

    private companion object {
        const val DESTINATION = "auth_destination"
        const val EMAIL = "auth_email"
    }
}
