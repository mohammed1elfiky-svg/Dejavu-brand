package com.dejavu.core.network

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
import com.dejavu.core.model.QuantityRequest
import com.dejavu.core.model.ReasonRequest
import com.dejavu.core.model.RefreshRequest
import com.dejavu.core.model.RegisterRequest
import com.dejavu.core.model.SalesReturn
import com.dejavu.core.model.ShippingZone
import com.dejavu.core.model.StoreProduct
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/** Customer-app endpoints of the Dejavu API. Paths are relative to `.../api/v1/`. */
interface DejavuApi {
    // auth
    @POST("auth/customer/register")
    suspend fun register(@Body body: RegisterRequest): AuthResponse

    @POST("auth/customer/login")
    suspend fun login(@Body body: LoginRequest): AuthResponse

    @POST("auth/logout")
    suspend fun logout(@Body body: RefreshRequest): Response<Unit>

    @GET("auth/me")
    suspend fun me(): Customer

    // catalog
    @GET("store/categories")
    suspend fun categories(): List<Category>

    @GET("store/products")
    suspend fun products(
        @Query("q") query: String? = null,
        @Query("category") category: String? = null,
        @Query("featured") featured: Boolean? = null,
        @Query("sort") sort: String? = null,
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 20,
    ): Page<StoreProduct>

    @GET("store/products/{slug}")
    suspend fun product(@Path("slug") slug: String): StoreProduct

    @GET("store/shipping-zones")
    suspend fun shippingZones(): List<ShippingZone>

    // addresses
    @GET("store/me/addresses")
    suspend fun addresses(): List<Address>

    @POST("store/me/addresses")
    suspend fun createAddress(@Body body: AddressRequest): Address

    @PATCH("store/me/addresses/{id}")
    suspend fun updateAddress(@Path("id") id: String, @Body body: AddressRequest): Address

    @DELETE("store/me/addresses/{id}")
    suspend fun deleteAddress(@Path("id") id: String): Response<Unit>

    // cart
    @GET("store/cart")
    suspend fun cart(@Query("addressId") addressId: String? = null): Cart

    @POST("store/cart/items")
    suspend fun addToCart(@Body body: AddCartItemRequest): Cart

    @PATCH("store/cart/items/{variantId}")
    suspend fun setCartQuantity(@Path("variantId") variantId: String, @Body body: QuantityRequest): Cart

    @DELETE("store/cart/items/{variantId}")
    suspend fun removeFromCart(@Path("variantId") variantId: String): Cart

    @PUT("store/cart/coupon")
    suspend fun applyCoupon(@Body body: CouponRequest): Cart

    @DELETE("store/cart/coupon")
    suspend fun removeCoupon(): Cart

    // orders & returns
    @POST("store/orders")
    suspend fun checkout(@Body body: CheckoutRequest): OrderDetail

    @GET("store/orders")
    suspend fun orders(@Query("page") page: Int = 1, @Query("pageSize") pageSize: Int = 20): Page<OrderSummary>

    @GET("store/orders/{number}")
    suspend fun order(@Path("number") number: String): OrderDetail

    @POST("store/orders/{number}/cancel")
    suspend fun cancelOrder(@Path("number") number: String, @Body body: ReasonRequest): OrderDetail

    @POST("store/orders/{number}/returns")
    suspend fun requestReturn(@Path("number") number: String, @Body body: CreateReturnRequest): SalesReturn

    @GET("store/returns")
    suspend fun returns(): List<SalesReturn>
}
