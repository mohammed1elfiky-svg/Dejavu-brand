package com.dejavu.core.data

import com.dejavu.core.model.Brand
import com.dejavu.core.model.UpdateTopUpRequest
import com.dejavu.core.model.AddCartItemRequest
import com.dejavu.core.model.Address
import com.dejavu.core.model.AddressRequest
import com.dejavu.core.model.AuthResponse
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

    suspend fun register(
        name: String,
        phone: String,
        password: String,
        email: String? = null,
        governorate: String? = null,
        area: String? = null,
        otp: String? = null,
    ): Result<Customer> = apiCall {
        api.register(RegisterRequest(name.trim(), phone.normalizedPhone(), password, email?.trim()?.ifBlank { null }, governorate, area, otp?.trim()?.ifBlank { null }))
    }.map(::start)

    /** Governorates for the sign-up form. */
    suspend fun governorates() = apiCall { api.governorates() }

    /** Whether sign-up needs a code texted to the phone. */
    suspend fun otpRequired() = apiCall { api.otpRequired() }.map { it.required }

    /** Forgotten password: texts a reset code to the registered [phone]. */
    suspend fun sendResetCode(phone: String) = apiCall { api.sendResetCode(com.dejavu.core.model.OtpRequest(phone.normalizedPhone())) }

    /** Sets [newPassword] with the texted [code] and signs in. */
    suspend fun resetPassword(phone: String, code: String, newPassword: String): Result<Customer> =
        apiCall { api.resetPassword(com.dejavu.core.model.PasswordResetRequest(phone.normalizedPhone(), code.trim(), newPassword)) }.map(::start)

    /** Texts a sign-up code to [phone] (sent from the store's phone). */
    suspend fun sendOtp(phone: String) = apiCall { api.sendOtp(com.dejavu.core.model.OtpRequest(phone.normalizedPhone())) }

    suspend fun logout() {
        tokens.current()?.let { t -> apiCall { api.logout(RefreshRequest(t.refreshToken)) } }
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

enum class ProductSort(val wire: String?) { NEWEST(null), PRICE_ASC("price_asc"), PRICE_DESC("price_desc") }

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
        sort: ProductSort = ProductSort.NEWEST,
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

    suspend fun refresh(addressId: String? = null) = update { api.cart(addressId) }
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
    suspend fun checkout(addressId: String, method: PaymentMethod, note: String?, currency: Currency? = null): Result<OrderDetail> =
        apiCall { api.checkout(CheckoutRequest(addressId, method, note?.trim()?.ifBlank { null }, currency)) }
            .onSuccess { cart.refresh() }

    suspend fun orders(page: Int = 1): Result<Page<OrderSummary>> = apiCall { api.orders(page) }
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

    suspend fun bankAccounts(): Result<List<StoreBankAccount>> = apiCall { api.bankAccounts() }

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
    ): Result<TopUp> = apiCall {
        val part = MultipartBody.Part.createFormData("file", "receipt.jpg", receipt.toRequestBody(mimeType.toMediaType()))
        val file = api.uploadFile(part)
        api.requestTopUp(TopUpRequest(currency, amount, file.id, bankAccountId, reference?.ifBlank { null }, note?.ifBlank { null }))
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
}
