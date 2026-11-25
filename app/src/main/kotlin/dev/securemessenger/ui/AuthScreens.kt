package dev.securemessenger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun AuthScreens(state: AuthUiState, viewModel: AuthViewModel) {
    when (state.destination) {
        AuthDestination.LOGIN -> LoginScreen(state, viewModel)
        AuthDestination.REGISTER -> RegisterScreen(state, viewModel)
        AuthDestination.VERIFY_EMAIL -> VerifyEmailScreen(state, viewModel)
        AuthDestination.RECOVERY -> RecoveryScreen(state, viewModel)
        AuthDestination.RESET_PASSWORD -> ResetPasswordScreen(state, viewModel)
        AuthDestination.CHATS -> Unit
    }
}

@Composable
private fun LoginScreen(state: AuthUiState, viewModel: AuthViewModel) {
    var email by rememberSaveable { mutableStateOf(state.email) }
    var password by remember { mutableStateOf("") }
    AuthFrame("Вход", state) {
        EmailField(email, { email = it })
        PasswordField(password, { password = it }, "Пароль")
        PrimaryButton("Войти", state.busy) { viewModel.login(email, password) }
        TextButton(onClick = viewModel::goToRecovery) { Text("Восстановить доступ") }
        TextButton(onClick = viewModel::goToRegistration) { Text("Регистрация по приглашению") }
    }
}

@Composable
private fun RegisterScreen(state: AuthUiState, viewModel: AuthViewModel) {
    var invite by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    AuthFrame("Создать аккаунт", state) {
        OutlinedTextField(
            value = invite,
            onValueChange = { invite = it },
            label = { Text("Код приглашения") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        EmailField(email, { email = it })
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Юзернейм: a-z, 0-9, _") },
            prefix = { Text("@") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        PasswordField(password, { password = it }, "Пароль (минимум 12 символов)")
        PrimaryButton("Зарегистрироваться", state.busy) {
            viewModel.register(invite, email, username, password)
        }
        TextButton(onClick = viewModel::goToLogin) { Text("У меня уже есть аккаунт") }
    }
}

@Composable
private fun VerifyEmailScreen(state: AuthUiState, viewModel: AuthViewModel) {
    var code by rememberSaveable { mutableStateOf("") }
    AuthFrame("Подтвердите почту", state) {
        Text("Код отправлен на ${state.email}")
        OutlinedTextField(
            value = code,
            onValueChange = { if (it.length <= 8 && it.all(Char::isDigit)) code = it },
            label = { Text("8-значный код") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        PrimaryButton("Подтвердить", state.busy, code.length == 8) { viewModel.verifyEmail(code) }
        TextButton(onClick = viewModel::resendVerification) { Text("Отправить код повторно") }
        TextButton(onClick = viewModel::goToLogin) { Text("Вернуться ко входу") }
    }
}

@Composable
private fun RecoveryScreen(state: AuthUiState, viewModel: AuthViewModel) {
    var email by rememberSaveable { mutableStateOf("") }
    AuthFrame("Восстановление", state) {
        Text("Если аккаунт существует, код будет отправлен на указанную почту.")
        EmailField(email, { email = it })
        PrimaryButton("Продолжить", state.busy) { viewModel.recover(email) }
        TextButton(onClick = viewModel::goToLogin) { Text("Вернуться ко входу") }
    }
}

@Composable
private fun ResetPasswordScreen(state: AuthUiState, viewModel: AuthViewModel) {
    var code by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    AuthFrame("Новый пароль", state) {
        Text(state.email)
        OutlinedTextField(
            value = code,
            onValueChange = { if (it.length <= 8 && it.all(Char::isDigit)) code = it },
            label = { Text("Код из письма") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        PasswordField(password, { password = it }, "Новый пароль")
        PrimaryButton("Сменить пароль", state.busy, code.length == 8) {
            viewModel.resetPassword(code, password)
        }
        Text("После смены пароля старые сессии будут отозваны. Старая E2EE-история на новом устройстве не восстанавливается.")
        TextButton(onClick = viewModel::goToLogin) { Text("Вернуться ко входу") }
    }
}

@Composable
private fun AuthFrame(title: String, state: AuthUiState, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Shield, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(
                "Личные и групповые разговоры со сквозным шифрованием",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large, shadowElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    state.error?.let { Text(errorText(it), color = MaterialTheme.colorScheme.error) }
                    state.info?.let { Text(infoText(it), color = MaterialTheme.colorScheme.primary) }
                    if (state.busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                    content()
                }
            }
            Text(
                "Сообщения и медиа защищены сквозным шифрованием.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun EmailField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text("Электронная почта") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().testTag("email_input"),
    )
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            TextButton(onClick = { visible = !visible }) { Text(if (visible) "Скрыть" else "Показать") }
        },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().testTag("password_input"),
    )
}

@Composable
private fun PrimaryButton(text: String, busy: Boolean, valid: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = !busy && valid, modifier = Modifier.fillMaxWidth().height(52.dp).testTag("primary_action")) {
        Text(text)
    }
}

private fun errorText(code: String): String = when (code) {
    "INVALID_CREDENTIALS" -> "Неверные данные или истёкшая сессия"
    "EMAIL_NOT_VERIFIED" -> "Сначала подтвердите почту"
    "INVALID_INVITE" -> "Приглашение недействительно или уже использовано"
    "ACCOUNT_EXISTS" -> "Аккаунт с такой почтой или юзернеймом уже существует"
    "INVALID_CODE" -> "Код неверный, истёк или превышено число попыток"
    "PASSWORD_TOO_SHORT" -> "Пароль должен содержать не менее 12 символов"
    "PASSWORD_TOO_LONG" -> "Пароль слишком длинный"
    "INVALID_USERNAME" -> "Некорректный юзернейм"
    "INVALID_EMAIL" -> "Некорректный адрес почты"
    "DEVICE_LIMIT" -> "Другое устройство уже активно. Используйте восстановление для его замены"
    "NETWORK_ERROR" -> "Сервер недоступен. Проверьте соединение"
    else -> "Не удалось выполнить операцию"
}

private fun infoText(code: String): String = when (code) {
    "VERIFICATION_SENT" -> "Код подтверждения отправлен"
    "EMAIL_VERIFIED" -> "Почта подтверждена. Теперь можно войти"
    "RECOVERY_SENT" -> "Если аккаунт существует, письмо отправлено"
    "PASSWORD_CHANGED" -> "Пароль изменён"
    else -> ""
}
