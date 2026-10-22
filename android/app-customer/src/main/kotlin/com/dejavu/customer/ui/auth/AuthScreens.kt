package com.dejavu.customer.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import com.dejavu.core.ui.theme.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.dejavu.core.ui.theme.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.core.data.SessionRepository
import com.dejavu.core.data.Validators
import com.dejavu.core.data.userMessage
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.customer.ui.appViewModel
import com.dejavu.core.ui.BackTopBar
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AuthForm(
    val name: String = "",
    val phone: String = "",
    val email: String = "",
    val password: String = "",
    val submitted: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

class AuthViewModel(private val session: SessionRepository, private val registering: Boolean) : BaseViewModel() {
    private val _form = MutableStateFlow(AuthForm())
    val form = _form.asStateFlow()
    private val _done = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val done = _done.asSharedFlow()

    fun update(change: AuthForm.() -> AuthForm) {
        _form.value = _form.value.change().copy(error = null)
    }

    fun errors(f: AuthForm = _form.value): Map<String, String> = buildMap {
        Validators.phone(f.phone)?.let { put("phone", it) }
        Validators.password(f.password)?.let { put("password", it) }
        if (registering) {
            Validators.name(f.name)?.let { put("name", it) }
            Validators.email(f.email)?.let { put("email", it) }
        }
    }

    fun submit() {
        val f = _form.value.copy(submitted = true)
        _form.value = f
        if (errors(f).isNotEmpty() || f.busy) return
        _form.value = f.copy(busy = true)
        viewModelScope.launch {
            val result = if (registering) session.register(f.name, f.phone, f.password, f.email) else session.login(f.phone, f.password)
            result
                .onSuccess { _done.tryEmit(Unit) }
                .onFailure { _form.value = _form.value.copy(error = it.userMessage()) }
            _form.value = _form.value.copy(busy = false)
        }
    }
}

@Composable
fun LoginScreen(onBack: () -> Unit, loggedIn: () -> Unit, register: () -> Unit) =
    AuthScreen(registering = false, onBack = onBack, done = loggedIn, switch = register)

@Composable
fun RegisterScreen(onBack: () -> Unit, registered: () -> Unit, login: () -> Unit) =
    AuthScreen(registering = true, onBack = onBack, done = registered, switch = login)

@Composable
private fun AuthScreen(registering: Boolean, onBack: () -> Unit, done: () -> Unit, switch: () -> Unit) {
    val vm = appViewModel(key = "auth:$registering") { AuthViewModel(it.session, registering) }
    val form by vm.form.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.done.collect { done() } }
    val errors = if (form.submitted) vm.errors(form) else emptyMap()

    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { BackTopBar(if (registering) "إنشاء حساب" else "تسجيل الدخول", onBack) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (registering) "انضم إلى Dejavu" else "أهلاً بعودتك", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            if (registering) {
                Input("الاسم", form.name, errors["name"]) { v -> vm.update { copy(name = v) } }
            }
            Input("رقم الموبايل", form.phone, errors["phone"], KeyboardType.Phone, placeholder = "771234567") { v -> vm.update { copy(phone = v) } }
            if (registering) {
                Input("البريد الإلكتروني (اختياري)", form.email, errors["email"], KeyboardType.Email) { v -> vm.update { copy(email = v) } }
            }
            Input("كلمة المرور", form.password, errors["password"], KeyboardType.Password, password = true) { v -> vm.update { copy(password = v) } }

            form.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = vm::submit, enabled = !form.busy, modifier = Modifier.fillMaxWidth()) {
                if (form.busy) CircularProgressIndicator(strokeWidth = 2.dp) else Text(if (registering) "إنشاء الحساب" else "دخول")
            }
            TextButton(onClick = switch, modifier = Modifier.fillMaxWidth()) {
                Text(if (registering) "لديك حساب؟ سجّل الدخول" else "ليس لديك حساب؟ أنشئ حساباً جديداً")
            }
        }
    }
}

@Composable
private fun Input(
    label: String,
    value: String,
    error: String?,
    keyboard: KeyboardType = KeyboardType.Text,
    placeholder: String? = null,
    password: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth(),
    )
}
