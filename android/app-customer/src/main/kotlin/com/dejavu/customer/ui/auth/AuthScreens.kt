package com.dejavu.customer.ui.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import com.dejavu.core.ui.theme.Button
import com.dejavu.core.ui.theme.GlassCard
import com.dejavu.core.ui.theme.Liquid
import com.dejavu.core.ui.theme.OutlinedButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import com.dejavu.core.ui.theme.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.core.data.SessionRepository
import com.dejavu.core.data.Validators
import com.dejavu.core.network.ApiException
import com.dejavu.core.data.userMessage
import com.dejavu.core.model.Governorate
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.storage.CredentialsStore
import com.dejavu.core.ui.storage.SavedLogin
import com.dejavu.customer.ui.appViewModel
import com.dejavu.core.ui.BackTopBar
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AuthForm(
    val name: String = "",
    /** The country of the mobile number (Yemen unless picked) and the number without its code. */
    val country: Country = Country.YE,
    val phone: String = "",
    val password: String = "",
    /** Login only: keep the phone and password on this device for the next sign-in. */
    val remember: Boolean = true,
    /** Sign-up only: one of [Governorate.name], and the area inside it (Taiz). */
    val governorate: String? = null,
    val area: String? = null,
    /** Login only: the forgotten-password flow is showing instead of the sign-in form. */
    val reset: Boolean = false,
    /** Forgotten password: the new password and its confirmation. */
    val newPassword: String = "",
    val confirmPassword: String = "",
    /** Sign-up / forgotten password: the verification code step is showing. */
    val otpStep: Boolean = false,
    val otp: String = "",
    /** Seconds until the code can be sent again (0 = now). */
    val resendIn: Int = 0,
    /** No text reaches this number: the code is asked for on the store's WhatsApp (its number; "" = unknown). */
    val whatsapp: String? = null,
    /** Signing in to an account still waiting for its code. */
    val pendingLogin: Boolean = false,
    /** How the code reaches the customer: SMS (default) or WHATSAPP (asked for from the store). */
    val otpChannel: String = "SMS",
    /** Sign-up only: optional username (Instagram style), and whether it is their Instagram account. */
    val username: String = "",
    val usernameIsInstagram: Boolean = false,
    val submitted: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

/** Two English letters, then letters, digits, "." or "_" (like Instagram). */
private val USERNAME = Regex("^@?[A-Za-z]{2}[A-Za-z0-9._]{1,28}$")

/** The number as the server takes it (+967…, +966…). */
val AuthForm.full: String get() = country.full(phone)

/** Taiz is split between the old and the new rial; these are its two areas. */
private const val TAIZ = "تعز"
private val TAIZ_AREAS = listOf("الحوبان", "المدينة")

/** Areas to choose from in [g] (Taiz always asks, even if the server sent none). */
internal fun areasOf(g: Governorate?): List<String> = when {
    g == null -> emptyList()
    g.areas.isNotEmpty() -> g.areas.map { it.name }
    g.name == TAIZ -> TAIZ_AREAS
    else -> emptyList()
}

class AuthViewModel(
    private val session: SessionRepository,
    private val registering: Boolean,
    private val savedLogin: CredentialsStore,
    /** Called after sign-up with the rial of the shopper's region (YER / YEQ), if known. */
    private val onRegistered: (String?) -> Unit = {},
) : BaseViewModel() {
    private val _form = MutableStateFlow(
        if (registering) {
            AuthForm()
        } else {
            savedLogin.load()?.let { saved -> Country.split(saved.login).let { (c, n) -> AuthForm(country = c, phone = n, password = saved.password) } } ?: AuthForm()
        },
    )
    val form = _form.asStateFlow()
    private val _done = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val done = _done.asSharedFlow()

    // ---- sign-up: governorates and the verification code
    private val _governorates = MutableStateFlow<List<Governorate>>(emptyList())
    val governorates = _governorates.asStateFlow()
    private val _governoratesLoading = MutableStateFlow(false)
    val governoratesLoading = _governoratesLoading.asStateFlow()
    private val _governoratesError = MutableStateFlow<String?>(null)
    val governoratesError = _governoratesError.asStateFlow()

    private var countdown: Job? = null

    init {
        if (registering) loadGovernorates()
    }

    fun loadGovernorates() {
        if (_governoratesLoading.value) return
        _governoratesLoading.value = true
        _governoratesError.value = null
        viewModelScope.launch {
            session.governorates()
                .onSuccess { _governorates.value = it }
                .onFailure { _governoratesError.value = it.userMessage() }
            _governoratesLoading.value = false
        }
    }

    fun update(change: AuthForm.() -> AuthForm) {
        _form.value = _form.value.change().copy(error = null)
    }

    fun pickGovernorate(name: String) {
        // The area belongs to the governorate: changing it asks again.
        update { if (governorate == name) this else copy(governorate = name, area = null) }
    }

    private fun selectedGovernorate(f: AuthForm): Governorate? = _governorates.value.firstOrNull { it.name == f.governorate }

    fun errors(f: AuthForm = _form.value): Map<String, String> = buildMap {
        if (!f.country.isValid(f.phone)) put("phone", "رقم موبايل ${f.country.nameAr} غير صحيح (مثال: ${f.country.example})")
        if (f.reset) {
            if (f.otpStep) {
                Validators.password(f.newPassword)?.let { put("newPassword", it) }
                if (f.confirmPassword != f.newPassword) put("confirmPassword", "كلمتا المرور غير متطابقتين")
            }
            return@buildMap
        }
        Validators.password(f.password)?.let { put("password", it) }
        if (registering) {
            Validators.name(f.name)?.let { put("name", it) }
            if (f.username.isNotBlank() && !USERNAME.matches(f.username.trim())) {
                put("username", "اليوزر يبدأ بحرفين إنجليزيين ثم حروف أو أرقام أو . أو _")
            }
            // Only Yemeni customers pick their governorate.
            if (f.country == Country.YE) {
                if (f.governorate == null) put("governorate", "اختر المحافظة")
                if (f.area == null && areasOf(selectedGovernorate(f)).isNotEmpty()) put("area", "اختر مكان سكنك")
            }
        }
    }

    fun submit() {
        val f = _form.value.copy(submitted = true)
        _form.value = f
        if (errors(f).isNotEmpty() || f.busy) return
        _form.value = f.copy(busy = true)
        viewModelScope.launch {
            if (!registering) {
                session.login(f.full, f.password)
                    .onSuccess {
                        // "Remember me" survives sign-out; unticking it forgets the saved sign-in.
                        savedLogin.save(if (f.remember) SavedLogin(f.full, f.password) else null)
                        _done.tryEmit(Unit)
                    }
                    .onFailure { e ->
                        // Signed up but never entered the code: ask for it now.
                        if ((e as? ApiException)?.status == 403) {
                            _form.value = _form.value.copy(pendingLogin = true, error = null)
                            sendCode(_form.value)
                        } else {
                            _form.value = _form.value.copy(error = e.userMessage())
                        }
                    }
            } else {
                register(f)
            }
            _form.value = _form.value.copy(busy = false)
        }
    }

    // ---- forgotten password

    /** Opens the forgotten-password flow, keeping the phone typed on the sign-in form. */
    fun startReset() {
        countdown?.cancel()
        _form.value = _form.value.copy(
            reset = true, otpStep = false, otp = "", newPassword = "", confirmPassword = "",
            resendIn = 0, submitted = false, busy = false, error = null,
        )
    }

    /** Back from the forgotten-password flow to the sign-in form. */
    fun cancelReset() {
        countdown?.cancel()
        _form.value = _form.value.copy(
            reset = false, otpStep = false, otp = "", newPassword = "", confirmPassword = "",
            resendIn = 0, submitted = false, busy = false, error = null,
        )
    }

    /** Forgotten password step 1: texts the reset code to the phone. */
    fun requestResetCode() {
        val f = _form.value.copy(submitted = true)
        _form.value = f
        if (errors(f).isNotEmpty() || f.busy) return
        _form.value = f.copy(busy = true, submitted = false)
        viewModelScope.launch {
            sendCode(_form.value)
            _form.value = _form.value.copy(busy = false)
        }
    }

    /** Forgotten password step 2: sets the new password with the code, which also signs in. */
    private suspend fun resetPassword(f: AuthForm) {
        session.resetPassword(f.full, f.otp, f.newPassword)
            .onSuccess {
                // Same as a sign-in: keep (or forget) the saved sign-in, now with the new password.
                savedLogin.save(if (f.remember) SavedLogin(f.full, f.newPassword) else null)
                _done.tryEmit(Unit)
            }
            .onFailure { _form.value = _form.value.copy(error = it.userMessage()) }
    }

    /** Texts the code (sign-up or password reset) to the phone and shows the code step. */
    private suspend fun sendCode(f: AuthForm) {
        (if (f.reset) session.sendResetCode(f.full, f.otpChannel) else session.sendOtp(f.full, f.otpChannel))
            .onSuccess { sent ->
                _form.value = _form.value.copy(otpStep = true, otp = "", error = null, whatsapp = if (sent.byWhatsapp) sent.whatsapp.orEmpty() else null)
                if (!sent.byWhatsapp) startCountdown()
            }
            .onFailure { _form.value = _form.value.copy(error = it.userMessage()) }
    }

    private fun startCountdown() {
        countdown?.cancel()
        countdown = viewModelScope.launch {
            for (left in RESEND_SECONDS downTo 1) {
                _form.value = _form.value.copy(resendIn = left)
                delay(1_000)
            }
            _form.value = _form.value.copy(resendIn = 0)
        }
    }

    fun resend() {
        val f = _form.value
        if (f.busy || f.resendIn > 0) return
        _form.value = f.copy(busy = true, error = null)
        viewModelScope.launch {
            sendCode(_form.value)
            _form.value = _form.value.copy(busy = false)
        }
    }

    fun confirmCode() {
        val f = _form.value
        if (f.busy) return
        if (f.otp.length != OTP_LENGTH) {
            _form.value = f.copy(error = "أدخل الرمز المكوّن من $OTP_LENGTH أرقام")
            return
        }
        if (f.reset) {
            val checked = f.copy(submitted = true)
            if (errors(checked).isNotEmpty()) {
                _form.value = checked
                return
            }
        }
        _form.value = f.copy(busy = true, error = null)
        viewModelScope.launch {
            if (f.reset) resetPassword(_form.value) else verify(_form.value)
            _form.value = _form.value.copy(busy = false)
        }
    }

    /** Back from the code step to the form (e.g. to fix the phone number). */
    fun editDetails() {
        countdown?.cancel()
        _form.value = _form.value.copy(otpStep = false, otp = "", resendIn = 0, error = null, pendingLogin = false, whatsapp = null)
    }

    /** Sign-up: signs in at once, or (the store asks for a code) waits for the code step. */
    private suspend fun register(f: AuthForm) {
        // Phone + password only: no email is collected.
        val yemen = f.country == Country.YE
        session.signUp(
            f.name,
            f.full,
            f.password,
            null,
            governorate = f.governorate.takeIf { yemen },
            area = f.area.takeIf { yemen },
            otpChannel = f.otpChannel,
            username = f.username.takeIf { it.isNotBlank() },
            usernameIsInstagram = f.usernameIsInstagram,
        )
            .onSuccess { r ->
                val c = r.customer
                if (r.pending || c == null) {
                    _form.value = _form.value.copy(otpStep = true, otp = "", error = null, whatsapp = if (r.byWhatsapp) r.whatsapp.orEmpty() else null)
                    if (!r.byWhatsapp) startCountdown()
                } else {
                    signedUp(f, c.preferredCurrency)
                }
            }
            .onFailure { _form.value = _form.value.copy(error = it.userMessage()) }
    }

    /** The code of a waiting sign-up (new, or met at sign-in): finishes it and signs in. */
    private suspend fun verify(f: AuthForm) {
        session.verify(f.full, f.otp)
            .onSuccess { c ->
                if (registering) {
                    signedUp(f, c.preferredCurrency)
                } else {
                    savedLogin.save(if (f.remember) SavedLogin(f.full, f.password) else null)
                    _done.tryEmit(Unit)
                }
            }
            .onFailure { _form.value = _form.value.copy(error = it.userMessage()) }
    }

    private fun signedUp(f: AuthForm, preferred: String?) {
        val g = selectedGovernorate(f)
        onRegistered(preferred ?: g?.areas?.firstOrNull { it.name == f.area }?.currency ?: g?.currency)
        _done.tryEmit(Unit)
    }

    private companion object {
        const val RESEND_SECONDS = 60
        const val OTP_LENGTH = 6
    }
}

@Composable
fun LoginScreen(onBack: () -> Unit, loggedIn: () -> Unit, register: () -> Unit, required: Boolean = false) =
    AuthScreen(registering = false, onBack = onBack, done = loggedIn, switch = register, required = required)

@Composable
fun RegisterScreen(onBack: () -> Unit, registered: () -> Unit, login: () -> Unit, required: Boolean = false) =
    AuthScreen(registering = true, onBack = onBack, done = registered, switch = login, required = required)

@Composable
/** [required]: the store asks shoppers to sign in before browsing (no way back into the store). */
private fun AuthScreen(registering: Boolean, onBack: () -> Unit, done: () -> Unit, switch: () -> Unit, required: Boolean = false) {
    val vm = appViewModel(key = "auth:$registering") { c ->
        AuthViewModel(c.session, registering, c.savedLogin, onRegistered = { code -> c.applyRegionCurrency(code, force = true) })
    }
    val form by vm.form.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.done.collect { done() } }
    val errors = if (form.submitted) vm.errors(form) else emptyMap()

    // In the forgotten-password flow, back returns to the sign-in form.
    BackHandler(enabled = form.reset) { vm.cancelReset() }
    val title = when {
        registering -> "إنشاء حساب"
        form.reset -> "استعادة كلمة المرور"
        else -> "تسجيل الدخول"
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            if (required && !form.reset) {
                com.dejavu.core.ui.theme.GlassTopBar(title = { Text(title) })
            } else {
                BackTopBar(title, if (form.reset) vm::cancelReset else onBack)
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            com.dejavu.customer.ui.components.BrandMark(height = 48.dp)
            if (required) {
                Text(
                    "سجّل الدخول أو أنشئ حساباً جديداً لتصفّح المتجر",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            GlassCard(Modifier.fillMaxWidth(), elevation = 16.dp, contentPadding = 20.dp) {
                if (form.otpStep) {
                    OtpStep(vm, form, errors)
                } else if (form.reset) {
                    ResetPhoneStep(vm, form, errors)
                } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (registering) "انضم إلى Dejavu" else "أهلاً بعودتك", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(4.dp))
                    if (registering) {
                        Input("الاسم", form.name, errors["name"]) { v -> vm.update { copy(name = v) } }
                    }
                    PhoneField(form.country, form.phone, errors["phone"], onCountry = { c -> vm.update { copy(country = c) } }, onNumber = { v -> vm.update { copy(phone = v) } })
                    Input("كلمة المرور", form.password, errors["password"], KeyboardType.Password, password = true) { v -> vm.update { copy(password = v) } }
                    if (registering) {
                        Input("اسم المستخدم (اختياري)", form.username, errors["username"], placeholder = "مثل: sara.ali") { v ->
                            vm.update { copy(username = v.filter { c -> !c.isWhitespace() }.take(30)) }
                        }
                        if (form.username.isNotBlank()) {
                            Row(
                                Modifier.fillMaxWidth().toggleable(value = form.usernameIsInstagram, role = Role.Checkbox, onValueChange = { c -> vm.update { copy(usernameIsInstagram = c) } }),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = form.usernameIsInstagram, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = Liquid.GoldDeep))
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text("هذا يوزري في انستجرام", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "يصلك كود من 4 أرقام ترسله لحساب ديجافو في انستجرام، فيظهر بجانب اسمك شعار انستجرام ونجمة التوثيق. حسابك يعمل عادي حتى يتم التوثيق.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        if (form.country == Country.YE) RegionFields(vm, form, errors)
                        CodeChannel(form) { c -> vm.update { copy(otpChannel = c) } }
                    }
                    if (!registering) {
                        Row(
                            Modifier.fillMaxWidth().toggleable(value = form.remember, role = Role.Checkbox, onValueChange = { c -> vm.update { copy(remember = c) } }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = form.remember,
                                onCheckedChange = null,
                                colors = CheckboxDefaults.colors(checkedColor = Liquid.GoldDeep),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("تذكرني (حفظ رقم الجوال وكلمة المرور)", style = MaterialTheme.typography.bodyMedium)
                        }
                        // Forgotten password: a code by SMS or WhatsApp, then a new password.
                        OutlinedButton(onClick = vm::startReset, enabled = !form.busy, modifier = Modifier.fillMaxWidth()) {
                            Text("نسيت كلمة المرور؟ استعادة كلمة المرور", color = Liquid.GoldDeep)
                        }
                    }

                    form.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(onClick = vm::submit, enabled = !form.busy, modifier = Modifier.fillMaxWidth()) {
                        if (form.busy) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp) else Text(if (registering) "إنشاء الحساب" else "دخول")
                    }
                    TextButton(onClick = switch, modifier = Modifier.fillMaxWidth()) {
                        Text(if (registering) "لديك حساب؟ سجّل الدخول" else "ليس لديك حساب؟ أنشئ حساباً جديداً", color = Liquid.GoldDeep)
                    }
                }
            }
        }
    }
}

/** Governorate picker, and for Taiz the area (it decides the old or the new rial). */
@Composable
private fun RegionFields(vm: AuthViewModel, form: AuthForm, errors: Map<String, String>) {
    val governorates by vm.governorates.collectAsStateWithLifecycle()
    val loading by vm.governoratesLoading.collectAsStateWithLifecycle()
    val loadError by vm.governoratesError.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("المحافظة", style = MaterialTheme.typography.labelLarge)
        OutlinedButton(
            onClick = {
                if (governorates.isEmpty()) {
                    vm.loadGovernorates()
                } else {
                    open = true
                }
            },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                form.governorate ?: if (loading) "جارٍ التحميل…" else "اختر المحافظة",
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (loading) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(18.dp).width(18.dp))
            } else {
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
        }
        val error = loadError
        if (error != null && governorates.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = vm::loadGovernorates) { Text("إعادة المحاولة", color = Liquid.GoldDeep) }
            }
        }
        errors["governorate"]?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }

    val areas = areasOf(governorates.firstOrNull { it.name == form.governorate })
    if (areas.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("أين تسكن في ${form.governorate}؟", style = MaterialTheme.typography.labelLarge)
            Text(
                "يحدد هذا عرض الأسعار بالريال القديم أو الريال الجديد (القعيطي).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            areas.forEach { a ->
                Row(
                    Modifier.fillMaxWidth()
                        .selectable(selected = form.area == a, role = Role.RadioButton, onClick = { vm.update { copy(area = a) } })
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = form.area == a, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = Liquid.GoldDeep))
                    Spacer(Modifier.width(8.dp))
                    Text(a, style = MaterialTheme.typography.bodyMedium)
                }
            }
            errors["area"]?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }

    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("المحافظة") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    governorates.forEachIndexed { i, g ->
                        if (i > 0) HorizontalDivider()
                        Text(
                            g.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (g.name == form.governorate) Liquid.GoldDeep else Color.Unspecified,
                            fontWeight = if (g.name == form.governorate) FontWeight.Bold else null,
                            modifier = Modifier.fillMaxWidth().clickable {
                                vm.pickGovernorate(g.name)
                                open = false
                            }.padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("إغلاق", color = Liquid.GoldDeep) } },
        )
    }
}

/** "How should the code reach you?": a text message (default) or WhatsApp. Texts reach Yemeni numbers only. */
@Composable
private fun CodeChannel(form: AuthForm, onChange: (String) -> Unit) {
    if (form.country != Country.YE) {
        Text(
            "يصلك رمز التحقق عبر واتساب المتجر لأرقام ${form.country.nameAr}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("طريقة استلام رمز التحقق", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("SMS" to "رسالة نصية", "WHATSAPP" to "واتساب").forEach { (key, label) ->
                androidx.compose.material3.FilterChip(
                    selected = form.otpChannel == key,
                    onClick = { onChange(key) },
                    label = { Text(label) },
                )
            }
        }
    }
}

/** Forgotten password step 1: the phone the reset code is texted to. */
@Composable
private fun ResetPhoneStep(vm: AuthViewModel, form: AuthForm, errors: Map<String, String>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("نسيت كلمة المرور؟", style = MaterialTheme.typography.headlineSmall)
        Text(
            "أدخل رقم الموبايل المسجّل وسنرسل إليك رمزاً لتعيين كلمة مرور جديدة.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PhoneField(form.country, form.phone, errors["phone"], onCountry = { c -> vm.update { copy(country = c) } }, onNumber = { v -> vm.update { copy(phone = v) } })
        CodeChannel(form) { c -> vm.update { copy(otpChannel = c) } }
        form.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = vm::requestResetCode, enabled = !form.busy, modifier = Modifier.fillMaxWidth()) {
            if (form.busy) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp) else Text("إرسال الرمز")
        }
        TextButton(onClick = vm::cancelReset, enabled = !form.busy, modifier = Modifier.fillMaxWidth()) {
            Text("العودة إلى تسجيل الدخول", color = Liquid.GoldDeep)
        }
    }
}

/** Step 2 of sign-up or of a password reset: the code texted to the phone (and, for a reset, the new password). */
@Composable
private fun OtpStep(vm: AuthViewModel, form: AuthForm, errors: Map<String, String>) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (form.pendingLogin) Text("حسابك بانتظار تأكيد رقم الجوال", style = MaterialTheme.typography.titleSmall, color = Liquid.GoldDeep)
        Text(if (form.whatsapp != null) "أدخل رمز التحقق" else "أدخل رمز التحقق المرسل إلى رقمك", style = MaterialTheme.typography.titleMedium)
        Text(form.full, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        form.whatsapp?.let { store ->
            Text(
                if (form.country == Country.YE) "اطلب الرمز من المتجر عبر واتساب وسنرسله لك هناك." else "لا تصل الرسائل النصية إلى أرقام ${form.country.nameAr}. اطلب الرمز من المتجر عبر واتساب وسنرسله لك هناك.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (store.isNotBlank()) {
                OutlinedButton(
                    onClick = {
                        val what = if (form.reset) "رمز استعادة كلمة المرور" else "رمز التحقق"
                        runCatching { uriHandler.openUri(whatsappLink(store, "مرحباً، أرجو إرسال $what لتطبيق Dejavu لرقمي: ${form.full}")) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("اطلب الرمز عبر واتساب") }
            }
        }
        OutlinedTextField(
            value = form.otp,
            onValueChange = { v -> vm.update { copy(otp = v.filter { it.isDigit() }.take(6)) } },
            label = { Text("رمز التحقق") },
            placeholder = { Text("000000") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )
        if (form.reset) {
            Input("كلمة المرور الجديدة", form.newPassword, errors["newPassword"], KeyboardType.Password, password = true) { v -> vm.update { copy(newPassword = v) } }
            Input("تأكيد كلمة المرور", form.confirmPassword, errors["confirmPassword"], KeyboardType.Password, password = true) { v -> vm.update { copy(confirmPassword = v) } }
        }
        form.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = vm::confirmCode, enabled = !form.busy && form.otp.length == 6, modifier = Modifier.fillMaxWidth()) {
            if (form.busy) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
            } else {
                Text(
                    when {
                        form.reset -> "حفظ كلمة المرور"
                        form.pendingLogin -> "تأكيد ودخول"
                        else -> "تأكيد وإنشاء الحساب"
                    },
                )
            }
        }
        if (form.whatsapp == null) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TextButton(onClick = vm::resend, enabled = !form.busy && form.resendIn == 0) {
                Text(
                    if (form.resendIn > 0) "إعادة إرسال الرمز بعد ${form.resendIn} ث" else "إعادة إرسال الرمز",
                    color = if (form.resendIn > 0) MaterialTheme.colorScheme.onSurfaceVariant else Liquid.GoldDeep,
                )
            }
        }
        TextButton(onClick = vm::editDetails, enabled = !form.busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (form.reset) "تعديل الرقم" else "تعديل البيانات", color = Liquid.GoldDeep)
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
    var shown by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        trailingIcon = if (password) {
            {
                TextButton(onClick = { shown = !shown }) {
                    Text(if (shown) "إخفاء" else "إظهار", color = Liquid.GoldDeep)
                }
            }
        } else null,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        singleLine = true,
        visualTransformation = if (password && !shown) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth(),
    )
}
