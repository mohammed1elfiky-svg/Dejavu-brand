package com.dejavu.core.network

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
import com.dejavu.core.model.CheckoutRequest
import com.dejavu.core.model.CouponRequest
import com.dejavu.core.model.CreateReturnRequest
import com.dejavu.core.model.Customer
import com.dejavu.core.model.DeviceRegistration
import com.dejavu.core.model.DeviceRequest
import com.dejavu.core.model.DeviceToken
import com.dejavu.core.model.LoginRequest
import com.dejavu.core.model.NotificationPage
import com.dejavu.core.model.OrderDetail
import com.dejavu.core.model.OrderSummary
import com.dejavu.core.model.Page
import com.dejavu.core.model.PaymentMethod
import com.dejavu.core.model.QuantityRequest
import com.dejavu.core.model.ReasonRequest
import com.dejavu.core.model.RefreshRequest
import com.dejavu.core.model.RegisterRequest
import com.dejavu.core.model.SalesReturn
import com.dejavu.core.model.ShippingZone
import com.dejavu.core.model.StoreProduct
import com.dejavu.core.model.UnreadCount
import com.dejavu.core.model.StoreBankAccount
import com.dejavu.core.model.TopUp
import com.dejavu.core.model.TopUpRequest
import com.dejavu.core.model.UploadedFile
import com.dejavu.core.model.Wallet
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.Multipart
import retrofit2.http.Part
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
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

    @GET("auth/governorates")
    suspend fun governorates(): List<com.dejavu.core.model.Governorate>

    @GET("auth/customer/otp")
    suspend fun otpRequired(): com.dejavu.core.model.OtpRequired

    @POST("auth/customer/otp")
    suspend fun sendOtp(@Body body: com.dejavu.core.model.OtpRequest): com.dejavu.core.model.OtpSent

    @GET("store/stock-alerts")
    suspend fun stockAlerts(): List<com.dejavu.core.model.StockAlert>

    @POST("store/stock-alerts/{variantId}")
    suspend fun watchStock(@Path("variantId") variantId: String): List<com.dejavu.core.model.StockAlert>

    @DELETE("store/stock-alerts/{variantId}")
    suspend fun unwatchStock(@Path("variantId") variantId: String): List<com.dejavu.core.model.StockAlert>

    @POST("auth/customer/otp/reset")
    suspend fun sendResetCode(@Body body: com.dejavu.core.model.OtpRequest): com.dejavu.core.model.OtpSent

    @POST("auth/customer/password-reset")
    suspend fun resetPassword(@Body body: com.dejavu.core.model.PasswordResetRequest): AuthResponse

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
        @Query("brand") brand: String? = null,
    ): Page<StoreProduct>

    @GET("store/brands")
    suspend fun brands(): List<Brand>

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

    // currencies
    @GET("store/currencies")
    suspend fun currencies(): Currencies

    // payments
    @GET("store/payment-methods")
    suspend fun paymentMethods(@Query("governorate") governorate: String? = null): List<PaymentMethod>

    @GET("store/products/{slug}/reviews")
    suspend fun reviews(@Path("slug") slug: String, @Query("page") page: Int = 1): com.dejavu.core.model.ProductReviews

    @GET("store/products/{slug}/my-review")
    suspend fun myReview(@Path("slug") slug: String): com.dejavu.core.model.MyReview

    @PUT("store/products/{slug}/my-review")
    suspend fun writeReview(@Path("slug") slug: String, @Body body: com.dejavu.core.model.WriteReviewRequest): com.dejavu.core.model.ProductReview

    @Multipart
    @POST("store/me/review-photos")
    suspend fun uploadReviewPhoto(@Part file: okhttp3.MultipartBody.Part): com.dejavu.core.model.UploadedFile

    @POST("store/orders/{number}/pay")
    suspend fun payByCard(@Path("number") number: String): CardCheckout

    // wallet
    @GET("store/me/wallet")
    suspend fun wallet(): Wallet

    @GET("store/bank-accounts")
    suspend fun bankAccounts(): List<StoreBankAccount>

    @GET("store/me/wallet/top-ups")
    suspend fun topUps(): List<TopUp>

    @POST("store/me/wallet/top-ups")
    suspend fun requestTopUp(@Body body: TopUpRequest): TopUp

    @PATCH("store/me/wallet/top-ups/{id}")
    suspend fun updateTopUp(@Path("id") id: String, @Body body: UpdateTopUpRequest): TopUp

    @DELETE("store/me/wallet/top-ups/{id}")
    suspend fun deleteTopUp(@Path("id") id: String): Response<Unit>

    /** Deposit-slip photo (private to the customer and staff). */
    @Multipart
    @POST("store/me/files")
    suspend fun uploadFile(@Part file: MultipartBody.Part): UploadedFile

    // notifications
    @GET("store/notifications")
    suspend fun notifications(@Query("page") page: Int = 1, @Query("pageSize") pageSize: Int = 20): NotificationPage

    @GET("store/notifications/unread-count")
    suspend fun unreadCount(): UnreadCount

    @POST("store/notifications/{id}/read")
    suspend fun markRead(@Path("id") id: String): UnreadCount

    @POST("store/notifications/read-all")
    suspend fun markAllRead(): UnreadCount

    @POST("store/devices")
    suspend fun registerDevice(@Body body: DeviceRequest): DeviceRegistration

    @HTTP(method = "DELETE", path = "store/devices", hasBody = true)
    suspend fun unregisterDevice(@Body body: DeviceToken): Response<Unit>

    // ---- round 5: app settings, wish list, suggestions, assistant, analytics ----

    @GET("store/config")
    suspend fun storeConfig(): com.dejavu.core.model.StoreConfig

    @GET("store/about")
    suspend fun storeAbout(): com.dejavu.core.model.StoreAbout

    @GET("store/search/popular")
    suspend fun popularSearches(): List<String>

    @GET("store/products/{slug}/related")
    suspend fun related(@Path("slug") slug: String): com.dejavu.core.model.RelatedProducts

    @GET("store/wishlist")
    suspend fun wishlist(): List<StoreProduct>

    @GET("store/wishlist/ids")
    suspend fun wishlistIds(): List<String>

    @POST("store/wishlist/{productId}")
    suspend fun addToWishlist(@Path("productId") productId: String): List<String>

    @DELETE("store/wishlist/{productId}")
    suspend fun removeFromWishlist(@Path("productId") productId: String): List<String>

    @GET("store/cart/suggestions")
    suspend fun cartSuggestions(): List<StoreProduct>

    @GET("store/chat")
    suspend fun chat(): com.dejavu.core.model.ChatHistory

    @POST("store/chat")
    suspend fun sendChat(@Body body: com.dejavu.core.model.ChatSendRequest): com.dejavu.core.model.ChatMessage

    @DELETE("store/chat")
    suspend fun clearChat(): Response<Unit>

    @POST("store/analytics")
    suspend fun analytics(@Body body: com.dejavu.core.model.AnalyticsBatch): Response<Unit>

    @GET("store/pre-orders")
    suspend fun preOrders(): List<com.dejavu.core.model.PreOrder>

    @POST("store/pre-orders")
    suspend fun preOrder(@Body body: com.dejavu.core.model.PreOrderRequest): com.dejavu.core.model.PreOrder

    @DELETE("store/pre-orders/{id}")
    suspend fun cancelPreOrder(@Path("id") id: String): com.dejavu.core.model.PreOrder

    @POST("store/me/gift-cards/redeem")
    suspend fun redeemGiftCard(@Body body: com.dejavu.core.model.GiftCardRedeemRequest): com.dejavu.core.model.GiftCardRedeemed
}
