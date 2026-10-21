package com.dejavu.core.data

import com.dejavu.core.model.AddCartItemRequest
import com.dejavu.core.model.Address
import com.dejavu.core.model.AddressRequest
import com.dejavu.core.model.AuthResponse
import com.dejavu.core.model.Cart
import com.dejavu.core.model.Category
import com.dejavu.core.model.CheckoutRequest
import com.dejavu.core.model.CouponRequest
import com.dejavu.core.model.CreateReturnRequest
import com.dejavu.core.model.Customer
import com.dejavu.core.model.LoginRequest
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

    suspend fun register(name: String, phone: String, password: String, email: String?): Result<Customer> =
        apiCall { api.register(RegisterRequest(name.trim(), phone.normalizedPhone(), password, email?.trim()?.ifBlank { null })) }.map(::start)

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
    ): Result<Page<StoreProduct>> =
        apiCall { api.products(query?.trim()?.ifBlank { null }, category, featured, sort.wire, page, pageSize) }

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
    suspend fun checkout(addressId: String, method: PaymentMethod, note: String?): Result<OrderDetail> =
        apiCall { api.checkout(CheckoutRequest(addressId, method, note?.trim()?.ifBlank { null })) }
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
}
