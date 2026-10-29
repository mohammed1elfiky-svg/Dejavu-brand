package com.dejavu.core.data

import com.dejavu.core.model.Brand
import com.dejavu.core.model.UpdateTopUpRequest
import com.dejavu.core.model.AddCartItemRequest
import com.dejavu.core.model.Address
import com.dejavu.core.model.AddressRequest
import com.dejavu.core.model.AuthResponse
import com.dejavu.core.model.SignUpResult
import com.dejavu.core.model.CardCheckout
import com.dejavu.core.model.Cart
import com.dejavu.core.model.Category
import com.dejavu.core.model.Currencies
import com.dejavu.core.model.Currency
import com.dejavu.core.model.StoreBankAccount
import com.dejavu.core.model.TopUp
import com.dejavu.core.model.TopUpRequest
import com.dejavu.core.model.Wallet
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import com.dejavu.core.model.CheckoutRequest
import com.dejavu.core.model.CouponRequest
import com.dejavu.core.model.CreateReturnRequest
import com.dejavu.core.model.Customer
import com.dejavu.core.model.DeviceRegistration
import com.dejavu.core.model.DeviceRequest
import com.dejavu.core.model.DeviceToken
import com.dejavu.core.model.LoginRequest
import com.dejavu.core.model.Money
import com.dejavu.core.model.NotificationPage
import com.dejavu.core.model.OrderDetail
import com.dejavu.core.model.OrderSummary
import com.dejavu.core.model.Page
import com.dejavu.core.model.PaymentMethod
import com.dejavu.core.model.QuantityRequest
import com.dejavu.core.model.ReasonRequest
import com.dejavu.core.model.RefreshRequest
import com.dejavu.core.model.RegisterRequest
import com.dejavu.core.model.ReturnItemRequest
import com.dejavu.core.model.SalesReturn
import com.dejavu.core.model.ShippingZone
import com.dejavu.core.model.StoreProduct
import com.dejavu.core.model.UnreadCount
import com.dejavu.core.network.ApiException
import com.dejavu.core.network.DejavuApi
import com.dejavu.core.network.TokenStore
import com.dejavu.core.network.Tokens
import com.dejavu.core.network.apiCall
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// ----------------------------------------------------------------- session ---

sealed interface SessionState {
    data object LoggedOut : SessionState

    /** [customer] is null while the profile is still loading (e.g. app start while offline). */
    data class LoggedIn(val customer: Customer?) : SessionState
}

class SessionRepository(private val api: DejavuApi, private val tokens: TokenStore) {
    private val _state = MutableStateFlow<SessionState>(
        if (tokens.current() == null) SessionState.LoggedOut else SessionState.LoggedIn(null),
    )
    val state: StateFlow<SessionState> = _state.asStateFlow()

    val isLoggedIn: Boolean get() = _state.value is SessionState.LoggedIn

    /** Loads the profile for a remembered session. A 401 here means the session already ended. */
    suspend fun restore() {
        if (tokens.current() == null) return
        apiCall { api.me() }
            .onSuccess { _state.value = SessionState.LoggedIn(it) }
            .onFailure { if ((it as ApiException).isUnauthorized) onSessionExpired() }
    }

    suspend fun login(phone: String, password: String): Result<Customer> =
        apiCall { api.login(LoginRequest(phone.normalizedPhone(), password)) }.map(::start)

    /**
     * Sign-up. Without [otp], when the store asks for a texted code, the account waits
     * ([SignUpResult.pending]) until [verify] gets the code or the owner approves it.
     */
    suspend fun signUp(
        name: String,
        phone: String,
        password: String,
        email: String? = null,
        governorate: String? = null,
        area: String? = null,
        otp: String? = null,
        otpChannel: String? = null,
        username: String? = null,
        usernameIsInstagram: Boolean = false,
        birthDate: String? = null,
    ): Result<SignUpResult> = apiCall {
        val user = username?.trim()?.removePrefix("@")?.ifBlank { null }
        api.register(
            RegisterRequest(
                name.trim(), phone.normalizedPhone(), password, email?.trim()?.ifBlank { null }, governorate, area, otp?.trim()?.ifBlank { null }, otpChannel,
                username = user,
                usernameIsInstagram = if (user != null && usernameIsInstagram) true else null,
                birthDate = birthDate?.ifBlank { null },
            ),
        )
    }.map { r ->
        val customer = r.customer
        val access = r.accessToken
        val refresh = r.refreshToken
        if (access != null && refresh != null && customer != null) start(AuthResponse(access, refresh, r.expiresIn, customer))
        r
    }

    /** Sign-up that signs in at once (the store asks for no code, or [otp] is given). */
    suspend fun register(
        name: String,
        phone: String,
        password: String,
        email: String? = null,
        governorate: String? = null,
        area: String? = null,
        otp: String? = null,
    ): Result<Customer> = signUp(name, phone, password, email, governorate, area, otp).mapCatching { r: SignUpResult ->
        r.customer ?: throw com.dejavu.core.network.ApiException(0, "أدخل رمز التحقق المرسل إلى رقمك")
    }

    /**
     * Changes the password. The server then signs out every device, so this one signs in
     * again at once with the new password.
     */
    suspend fun changePassword(current: String, new: String): Result<Customer> {
        val phone = (_state.value as? SessionState.LoggedIn)?.customer?.phone ?: apiCall { api.me() }.getOrNull()?.phone
            ?: return Result.failure(com.dejavu.core.network.ApiException(0, "سجّل الدخول من جديد"))
        return apiCall { api.changePassword(com.dejavu.core.model.ChangePasswordRequest(current, new)).let { if (!it.isSuccessful) throw retrofit2.HttpException(it) } }
            .mapCatching { login(phone, new).getOrThrow() }
    }

    /** Finishes a waiting sign-up with its code, and signs in. */
    suspend fun verify(phone: String, code: String): Result<Customer> =
        apiCall { api.verify(com.dejavu.core.model.VerifyRequest(phone.normalizedPhone(), code.trim())) }.map(::start)

    /** Governorates for the sign-up form. */
    suspend fun governorates() = apiCall { api.governorates() }

    /** Whether sign-up needs a code texted to the phone. */
    suspend fun otpRequired() = apiCall { api.otpRequired() }.map { it.required }

    /** Forgotten password: texts a reset code to the registered [phone]. */
    suspend fun sendResetCode(phone: String, channel: String? = null) = apiCall { api.sendResetCode(com.dejavu.core.model.OtpRequest(phone.normalizedPhone(), channel)) }

    /** Sets [newPassword] with the texted [code] and signs in. */
    suspend fun resetPassword(phone: String, code: String, newPassword: String): Result<Customer> =
        apiCall { api.resetPassword(com.dejavu.core.model.PasswordResetRequest(phone.normalizedPhone(), code.trim(), newPassword)) }.map(::start)

    /** Texts a sign-up code to [phone] (sent from the store's phone). */
    suspend fun sendOtp(phone: String, channel: String? = null) = apiCall { api.sendOtp(com.dejavu.core.model.OtpRequest(phone.normalizedPhone(), channel)) }

    suspend fun logout() {
        tokens.current()?.let { t -> apiCall { api.logout(RefreshRequest(t.refreshToken)) } }
        tokens.save(null)
        _state.value = SessionState.LoggedOut
    }

    /** Deletes the account for good (password asked again); the session ends with it. */
    suspend fun deleteAccount(password: String): Result<Unit> =
        apiCall { api.deleteAccount(com.dejavu.core.model.DeleteAccountRequest(password)).let { if (!it.isSuccessful) throw retrofit2.HttpException(it) } }
            .onSuccess {
                tokens.save(null)
                _state.value = SessionState.LoggedOut
            }

    /** Called by the HTTP layer when the refresh token was rejected. */
    fun onSessionExpired() {
        tokens.save(null)
        _state.value = SessionState.LoggedOut
    }

    private fun start(auth: AuthResponse): Customer {
        tokens.save(Tokens(auth.accessToken, auth.refreshToken))
        _state.value = SessionState.LoggedIn(auth.customer)
        return auth.customer
    }
}

private fun String.normalizedPhone() = filterNot { it.isWhitespace() }

// ----------------------------------------------------------------- catalog ---

/** STORE: the owner's own order (the server's default), then the most visited, then the newest. */
enum class ProductSort(val wire: String?) { STORE(null), NEWEST("newest"), PRICE_ASC("price_asc"), PRICE_DESC("price_desc") }

class CatalogRepository(private val api: DejavuApi) {
    /** Approved reviews of a product with the average rating. */
    suspend fun reviews(slug: String, page: Int = 1) = apiCall { api.reviews(slug, page) }

    /** The signed-in customer's own review and whether they received the product (may review). */
    suspend fun myReview(slug: String) = apiCall { api.myReview(slug) }

    /** Adds or replaces the review; it shows in the store once the store approves it. */
    suspend fun writeReview(slug: String, rating: Int, comment: String?, images: List<String>) =
        apiCall { api.writeReview(slug, com.dejavu.core.model.WriteReviewRequest(rating, comment?.trim()?.ifBlank { null }, images)) }

    /** A photo for a review; send the returned url in writeReview. */
    suspend fun uploadReviewPhoto(bytes: ByteArray, mimeType: String = "image/jpeg") = apiCall {
        api.uploadReviewPhoto(okhttp3.MultipartBody.Part.createFormData("file", "review.jpg", bytes.toRequestBody(mimeType.toMediaType())))
    }

    /** Sizes/colours the signed-in customer waits for. */
    suspend fun stockAlerts() = apiCall { api.stockAlerts() }

    /** "Tell me when it is back": a notification arrives once [variantId] is in stock again. */
    suspend fun watchStock(variantId: String) = apiCall { api.watchStock(variantId) }
    suspend fun unwatchStock(variantId: String) = apiCall { api.unwatchStock(variantId) }

    private var categoriesCache: List<Category>? = null
    private var zonesCache: List<ShippingZone>? = null

    suspend fun categories(refresh: Boolean = false): Result<List<Category>> {
        if (!refresh) categoriesCache?.let { return Result.success(it) }
        return apiCall { api.categories() }.onSuccess { categoriesCache = it }
    }

    suspend fun products(
        query: String? = null,
        category: String? = null,
        featured: Boolean? = null,
        sort: ProductSort = ProductSort.STORE,
        page: Int = 1,
        pageSize: Int = 20,
        brand: String? = null,
    ): Result<Page<StoreProduct>> =
        apiCall { api.products(query?.trim()?.ifBlank { null }, category, featured, sort.wire, page, pageSize, brand) }

    /** Brands with something on sale, for the home screen. */
    suspend fun brands(): Result<List<Brand>> = apiCall { api.brands() }

    suspend fun product(slug: String): Result<StoreProduct> = apiCall { api.product(slug) }

    /** Governorates we deliver to, for the address form. */
    suspend fun shippingZones(): Result<List<ShippingZone>> {
        zonesCache?.let { return Result.success(it) }
        return apiCall { api.shippingZones() }.onSuccess { zonesCache = it }
    }
}

// -------------------------------------------------------------------- cart ---

/** Keeps the latest cart so the badge and cart screen stay in sync. */
class CartRepository(private val api: DejavuApi) {
    private val _cart = MutableStateFlow<Cart?>(null)
    val cart: StateFlow<Cart?> = _cart.asStateFlow()

    suspend fun refresh(addressId: String? = null, deliveryMethod: String? = null, carrierOfficeId: String? = null) =
        update { api.cart(addressId, deliveryMethod, carrierOfficeId) }
    suspend fun add(variantId: String, quantity: Int) = update { api.addToCart(AddCartItemRequest(variantId, quantity)) }
    suspend fun setQuantity(variantId: String, quantity: Int) = update { api.setCartQuantity(variantId, QuantityRequest(quantity)) }
    suspend fun remove(variantId: String) = update { api.removeFromCart(variantId) }
    suspend fun applyCoupon(code: String) = update { api.applyCoupon(CouponRequest(code.trim())) }
    suspend fun removeCoupon() = update { api.removeCoupon() }

    fun clear() {
        _cart.value = null
    }

    private suspend fun update(block: suspend () -> Cart): Result<Cart> = apiCall { block() }.onSuccess { _cart.value = it }
}

// --------------------------------------------------------------- addresses ---

class AddressRepository(private val api: DejavuApi) {
    suspend fun list(): Result<List<Address>> = apiCall { api.addresses() }
    suspend fun create(request: AddressRequest): Result<Address> = apiCall { api.createAddress(request) }
    suspend fun update(id: String, request: AddressRequest): Result<Address> = apiCall { api.updateAddress(id, request) }
    suspend fun delete(id: String): Result<Unit> = apiCall { api.deleteAddress(id).let { if (!it.isSuccessful) throw retrofit2.HttpException(it) } }
}

// ------------------------------------------------------------------ orders ---

class OrderRepository(private val api: DejavuApi, private val cart: CartRepository) {
    suspend fun checkout(
        addressId: String?,
        method: PaymentMethod,
        note: String?,
        currency: Currency? = null,
        deliveryMethod: String? = null,
        carrierId: String? = null,
        carrierOfficeId: String? = null,
    ): Result<OrderDetail> =
        apiCall { api.checkout(CheckoutRequest(addressId, method, note?.trim()?.ifBlank { null }, currency, deliveryMethod, carrierId, carrierOfficeId)) }
            .onSuccess { cart.refresh() }

    suspend fun orders(page: Int = 1): Result<Page<OrderSummary>> = apiCall { api.orders(page) }
    suspend fun markReceived(number: String): Result<OrderDetail> = apiCall { api.orderReceived(number) }
    suspend fun order(number: String): Result<OrderDetail> = apiCall { api.order(number) }
    suspend fun cancel(number: String, reason: String): Result<OrderDetail> = apiCall { api.cancelOrder(number, ReasonRequest(reason)) }

    suspend fun requestReturn(number: String, reason: String, quantities: Map<String, Int>): Result<SalesReturn> =
        apiCall {
            val items = quantities.filterValues { it > 0 }.map { (id, qty) -> ReturnItemRequest(id, qty) }
            api.requestReturn(number, CreateReturnRequest(reason.trim(), items))
        }

    suspend fun returns(): Result<List<SalesReturn>> = apiCall { api.returns() }

    /** Opens the card-payment page for a card order; open [CardCheckout.checkoutUrl] in a browser. */
    suspend fun payByCard(number: String): Result<CardCheckout> = apiCall { api.payByCard(number) }

    private var methodsCache: List<PaymentMethod>? = null

    /**
     * Methods the checkout accepts right now, as the store set them (card only while a payment
     * gateway is configured; cash on delivery only where the store offers it for [governorate]).
     */
    suspend fun paymentMethods(governorate: String? = null): List<PaymentMethod> {
        if (governorate == null) methodsCache?.let { return it }
        return apiCall { api.paymentMethods(governorate) }.getOrNull()
            ?.filter { it in CHECKOUT_METHODS }
            ?.also { if (governorate == null) methodsCache = it }
            ?: CHECKOUT_METHODS.filter { it != PaymentMethod.CARD && it != PaymentMethod.CUSTOMER_ACCOUNT }
    }

    companion object {
        /** Checkout methods in the order the app shows them. */
        val CHECKOUT_METHODS = listOf(
            PaymentMethod.CASH_ON_DELIVERY,
            PaymentMethod.CARD,
            PaymentMethod.WALLET,
            PaymentMethod.BANK_TRANSFER,
            PaymentMethod.CUSTOMER_ACCOUNT,
        )
    }
}

// -------------------------------------------------------------- currencies ---

/**
 * The currency the shopper sees prices in. Prices stay in SAR in the API; they
 * are converted for display with the current rate. Orders keep their own rate.
 */
class CurrencyRepository(private val api: DejavuApi, private val preferred: () -> Currency, private val save: (Currency) -> Unit) {
    private val _rates = MutableStateFlow<Currencies?>(null)
    val rates: StateFlow<Currencies?> = _rates.asStateFlow()
    private val _selected = MutableStateFlow(preferred())
    val selected: StateFlow<Currency> = _selected.asStateFlow()
    private val _available = MutableStateFlow(listOf(Currency.SAR))

    /** Currencies the store offers shoppers right now (base first). */
    val available: StateFlow<List<Currency>> = _available.asStateFlow()

    /** Loads the current rates and applies the preferred currency. */
    suspend fun refresh(): Result<Currencies> = apiCall { api.currencies() }.onSuccess {
        _available.value = it.register()
        _rates.value = it
        apply(_selected.value)
    }

    /** Shows prices in [currency] from now on (falls back to SAR until a rate is known). */
    fun select(currency: Currency) {
        _selected.value = currency
        save(currency)
        apply(currency)
    }

    /** The currency checkout should invoice in, or null for the SAR default. */
    fun checkoutCurrency(): Currency? = Money.display.currency.takeIf { !it.isBase }

    private fun apply(currency: Currency) {
        val rate = _rates.value?.rate(currency)?.rate?.toBigDecimalOrNull()
        Money.display = if (!currency.isBase && rate != null) Money.Display(Currency.of(currency.code), rate) else Money.Display.BASE
    }
}

// ----------------------------------------------------------------- wallet ---

/** The shopper's balance and manual top-ups through a bank transfer. */
class WalletRepository(private val api: DejavuApi) {
    suspend fun wallet(): Result<Wallet> = apiCall { api.wallet() }

    /** Who a gift to [phone] would reach (masked name), to check before sending. */
    suspend fun giftRecipient(phone: String) = apiCall { api.giftRecipient(phone.filterNot { it.isWhitespace() }) }

    /** Gives [amount] of [currency] from this wallet to the customer with [phone]; [password] confirms. */
    suspend fun sendGift(phone: String, currency: Currency, amount: String, password: String, note: String?) =
        apiCall { api.sendGift(com.dejavu.core.model.GiftRequest(phone.filterNot { it.isWhitespace() }, currency.code, amount.trim(), password, note?.trim()?.ifBlank { null })) }

    suspend fun bankAccounts(): Result<List<StoreBankAccount>> = apiCall { api.bankAccounts() }

    /** One wallet movement in detail (the items of a purchase, how a top-up was paid…). */
    suspend fun entry(id: String) = apiCall { api.walletEntry(id) }

    suspend fun topUps(): Result<List<TopUp>> = apiCall { api.topUps() }

    /** Uploads the deposit slip, then files the request with it. */
    suspend fun requestTopUp(
        currency: Currency,
        amount: String,
        receipt: ByteArray,
        mimeType: String,
        bankAccountId: String?,
        reference: String?,
        note: String?,
        /** The slip already uploaded (by [uploadSlip]); [receipt] is not sent again. */
        receiptFileId: String? = null,
    ): Result<TopUp> = apiCall {
        val fileId = receiptFileId ?: api.uploadFile(MultipartBody.Part.createFormData("file", "receipt.jpg", receipt.toRequestBody(mimeType.toMediaType()))).id
        api.requestTopUp(TopUpRequest(currency, amount, fileId, bankAccountId, reference?.ifBlank { null }, note?.ifBlank { null }))
    }

    /** Uploads the slip photo and reads it: reference number, amount, and whether it was used before. */
    suspend fun uploadSlip(receipt: ByteArray, mimeType: String = "image/jpeg"): Result<Pair<String, com.dejavu.core.model.SlipScan>> = apiCall {
        val file = api.uploadFile(MultipartBody.Part.createFormData("file", "receipt.jpg", receipt.toRequestBody(mimeType.toMediaType())))
        val scan = runCatching { api.scanSlip(com.dejavu.core.model.ScanSlipRequest(file.id)) }.getOrDefault(com.dejavu.core.model.SlipScan())
        file.id to scan
    }

    /** Edits a request still under review; [receipt] replaces the deposit slip when given. */
    suspend fun updateTopUp(
        id: String,
        currency: Currency? = null,
        amount: String? = null,
        receipt: ByteArray? = null,
        mimeType: String = "image/jpeg",
        bankAccountId: String? = null,
        reference: String? = null,
        note: String? = null,
    ): Result<TopUp> = apiCall {
        val fileId = receipt?.let {
            api.uploadFile(MultipartBody.Part.createFormData("file", "receipt.jpg", it.toRequestBody(mimeType.toMediaType()))).id
        }
        api.updateTopUp(id, UpdateTopUpRequest(currency, amount?.trim(), fileId, bankAccountId, reference, note))
    }

    /** Withdraws a request still under review. */
    suspend fun deleteTopUp(id: String): Result<Unit> = apiCall {
        val res = api.deleteTopUp(id)
        if (!res.isSuccessful) throw retrofit2.HttpException(res)
    }
}

// ----------------------------------------------------------- notifications ---

class NotificationRepository(private val api: DejavuApi) {
    private val _unread = MutableStateFlow(0)

    /** Unread count for the bell badge. */
    val unread: StateFlow<Int> = _unread.asStateFlow()

    suspend fun refreshUnread() {
        apiCall { api.unreadCount() }.onSuccess { _unread.value = it.unread }
    }

    suspend fun page(page: Int = 1): Result<NotificationPage> =
        apiCall { api.notifications(page) }.onSuccess { _unread.value = it.unread }

    suspend fun markRead(id: String): Result<UnreadCount> = apiCall { api.markRead(id) }.onSuccess { _unread.value = it.unread }

    suspend fun markAllRead(): Result<UnreadCount> = apiCall { api.markAllRead() }.onSuccess { _unread.value = it.unread }

    /** Links this phone's push token to the signed-in customer. */
    suspend fun registerDevice(token: String, appVersion: String?): Result<DeviceRegistration> =
        apiCall { api.registerDevice(DeviceRequest(token, appVersion)) }

    suspend fun unregisterDevice(token: String): Result<Unit> = apiCall { api.unregisterDevice(DeviceToken(token)) }.map { }

    fun clear() {
        _unread.value = 0
    }
}


/**
 * Round-5 extras of the shop: the owner's app settings and "about" page, the wish list
 * (kept in memory for the hearts), suggestions, popular searches, the assistant, gift cards
 * and usage analytics.
 */
class StoreExtrasRepository(private val api: DejavuApi) {
    private val _config = kotlinx.coroutines.flow.MutableStateFlow(com.dejavu.core.model.StoreConfig())

    /** Sign-in rule, ads and assistant; refreshed at start. */
    val config: kotlinx.coroutines.flow.StateFlow<com.dejavu.core.model.StoreConfig> = _config

    suspend fun refreshConfig() = apiCall { api.storeConfig() }.onSuccess { _config.value = it }

    suspend fun about() = apiCall { api.storeAbout() }

    suspend fun popularSearches() = apiCall { api.popularSearches() }

    suspend fun related(slug: String) = apiCall { api.related(slug) }

    /** Products by id (the other pieces of an offer). */
    suspend fun productsByIds(ids: Collection<String>): Result<List<StoreProduct>> =
        if (ids.isEmpty()) Result.success(emptyList()) else apiCall { api.productsByIds(ids.joinToString(",")) }

    suspend fun cartSuggestions() = apiCall { api.cartSuggestions() }

    private val _wishlist = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())

    /** Ids of the products in the wish list (for the heart buttons). */
    val wishlistIds: kotlinx.coroutines.flow.StateFlow<Set<String>> = _wishlist

    suspend fun refreshWishlistIds() = apiCall { api.wishlistIds() }.onSuccess { _wishlist.value = it.toSet() }

    suspend fun wishlist() = apiCall { api.wishlist() }.onSuccess { list -> _wishlist.value = list.map { it.id }.toSet() }

    /** Adds or removes [productId]; returns whether it is now in the wish list. */
    suspend fun toggleWishlist(productId: String): Result<Boolean> {
        val adding = productId !in _wishlist.value
        return apiCall { if (adding) api.addToWishlist(productId) else api.removeFromWishlist(productId) }
            .onSuccess { _wishlist.value = it.toSet() }
            .map { productId in it }
    }

    fun clearWishlist() {
        _wishlist.value = emptySet()
    }

    suspend fun chat() = apiCall { api.chat() }

    suspend fun sendChat(text: String?, imageFileId: String?) = apiCall { api.sendChat(com.dejavu.core.model.ChatSendRequest(text?.takeIf { it.isNotBlank() }, imageFileId)) }

    suspend fun clearChat() = apiCall { api.clearChat().let { if (!it.isSuccessful) throw retrofit2.HttpException(it) } }

    /** Reports an assistant answer as wrong or offensive; the store owner is told. */
    suspend fun reportChat(messageId: String, reason: String?) =
        apiCall { api.reportChat(messageId, com.dejavu.core.model.ChatReportRequest(reason?.trim()?.ifBlank { null })) }

    /** A photo for the assistant (private to the customer). */
    suspend fun uploadPhoto(bytes: ByteArray, mimeType: String = "image/jpeg") = apiCall {
        val body = bytes.toRequestBody(mimeType.toMediaType())
        api.uploadFile(okhttp3.MultipartBody.Part.createFormData("file", "photo.jpg", body))
    }

    suspend fun sendAnalytics(batch: com.dejavu.core.model.AnalyticsBatch) = apiCall { api.analytics(batch).let { if (!it.isSuccessful) throw retrofit2.HttpException(it) } }

    suspend fun redeemGiftCard(code: String) = apiCall { api.redeemGiftCard(com.dejavu.core.model.GiftCardRedeemRequest(code.trim())) }

    /** Pre-orders on pieces still on their way ("احجز من الشحنة القادمة"). */
    suspend fun preOrders() = apiCall { api.preOrders() }
    suspend fun preOrder(variantId: String, quantity: Int) = apiCall { api.preOrder(com.dejavu.core.model.PreOrderRequest(variantId, quantity)) }
    suspend fun cancelPreOrder(id: String) = apiCall { api.cancelPreOrder(id) }
    suspend fun confirmPreOrder(id: String) = apiCall { api.confirmPreOrder(id) }
}

/**
 * Round 9 for customers: shipping companies and offices, the profile (username, Instagram
 * check, photo, birthday), loyalty points, gifts received and group gifts among friends.
 */
class MembersRepository(private val api: DejavuApi) {
    suspend fun carriers() = apiCall { api.carriers() }

    suspend fun profile() = apiCall { api.profile() }
    suspend fun updateProfile(request: com.dejavu.core.model.UpdateProfileRequest) = apiCall { api.updateProfile(request) }
    suspend fun checkUsername(username: String) = apiCall { api.checkUsername(username.trim().removePrefix("@")) }

    /** Uploads a photo anyone may see (the profile picture) and returns its address. */
    suspend fun uploadPublicImage(bytes: ByteArray, mimeType: String = "image/jpeg") = apiCall {
        api.uploadPublicImage(MultipartBody.Part.createFormData("file", "photo.jpg", bytes.toRequestBody(mimeType.toMediaType()))).url
    }

    suspend fun loyalty() = apiCall { api.loyalty() }
    suspend fun redeem(points: Int) = apiCall { api.redeemPoints(com.dejavu.core.model.RedeemPointsRequest(points)) }

    suspend fun gifts() = apiCall { api.myGifts() }
    suspend fun chooseGift(id: String, request: com.dejavu.core.model.GiftChoiceRequest) = apiCall { api.chooseGift(id, request) }

    suspend fun myGroupGifts() = apiCall { api.myGroupGifts() }
    suspend fun groupGiftsFor(username: String) = apiCall { api.groupGiftsFor(username.trim().removePrefix("@")) }
    suspend fun startGroupGift(request: com.dejavu.core.model.StartGroupGiftRequest) = apiCall { api.startGroupGift(request) }
    suspend fun contribute(id: String, amount: String, message: String?) = apiCall { api.contribute(id, com.dejavu.core.model.ContributeRequest(amount, message?.ifBlank { null })) }
    suspend fun cancelGroupGift(id: String) = apiCall { api.cancelGroupGift(id) }
}

/** The social side: feed, posts, likes, comments, follows, birthdays and friends' wish lists. */
class SocialRepository(private val api: DejavuApi) {
    suspend fun feed(tab: String, page: Int = 1) = apiCall { api.feed(tab, page) }
    suspend fun post(id: String) = apiCall { api.post(id) }
    suspend fun createPost(request: com.dejavu.core.model.NewPostRequest) = apiCall { api.createPost(request) }
    suspend fun deletePost(id: String) = apiCall { api.deletePost(id).let { if (!it.isSuccessful) throw retrofit2.HttpException(it) } }

    suspend fun like(id: String, on: Boolean) = apiCall { if (on) api.like(id) else api.unlike(id) }
    suspend fun comments(id: String) = apiCall { api.comments(id) }
    suspend fun comment(id: String, body: String) = apiCall { api.addComment(id, com.dejavu.core.model.CommentRequest(body.trim())) }
    suspend fun deleteComment(id: String) = apiCall { api.deleteComment(id).let { if (!it.isSuccessful) throw retrofit2.HttpException(it) } }
    suspend fun shopTheLook(id: String) = apiCall { api.shopTheLook(id) }

    suspend fun profile(username: String) = apiCall { api.socialProfile(username.removePrefix("@")) }
    suspend fun posts(username: String) = apiCall { api.userPosts(username.removePrefix("@")) }
    suspend fun followers(username: String) = apiCall { api.followers(username.removePrefix("@")) }
    suspend fun following(username: String) = apiCall { api.following(username.removePrefix("@")) }
    suspend fun wishlistOf(username: String) = apiCall { api.wishlistOf(username.removePrefix("@")) }
    suspend fun follow(username: String, on: Boolean) = apiCall { if (on) api.follow(username.removePrefix("@")) else api.unfollow(username.removePrefix("@")) }

    suspend fun birthdaysToday() = apiCall { api.birthdaysToday() }
    suspend fun rewards() = apiCall { api.myRewards() }
    suspend fun searchUsers(q: String) = apiCall { api.searchUsers(q.trim().removePrefix("@")) }
}
