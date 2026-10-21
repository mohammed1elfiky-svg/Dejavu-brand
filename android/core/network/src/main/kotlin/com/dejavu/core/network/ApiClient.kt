package com.dejavu.core.network

import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

val ApiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    encodeDefaults = false
}

/** Retrofit needs a base URL at build time; [BaseUrlInterceptor] replaces it on every call. */
private const val PLACEHOLDER_BASE = "http://dejavu.invalid/api/v1/"

/**
 * Points every request at the server currently configured in the app (it can be
 * changed at runtime from the settings screen, e.g. to a Codespaces URL).
 */
class BaseUrlInterceptor(private val baseUrl: () -> String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return chain.proceed(request.newBuilder().url(rebase(request.url, baseUrl())).build())
    }

    companion object {
        fun rebase(url: HttpUrl, base: String): HttpUrl {
            val target = normalizeBaseUrl(base).toHttpUrl()
            val relative = url.encodedPath.removePrefix(PLACEHOLDER_BASE.toHttpUrl().encodedPath)
            return url.newBuilder()
                .scheme(target.scheme)
                .host(target.host)
                .port(target.port)
                .encodedPath(target.encodedPath + relative)
                .build()
        }
    }
}

/** "https://x.app/api/v1" → "https://x.app/api/v1/"; a bare host gets "/api/v1/". */
fun normalizeBaseUrl(raw: String): String {
    var url = raw.trim()
    if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
    if (!url.contains("/api/v1")) url = url.trimEnd('/') + "/api/v1"
    return if (url.endsWith("/")) url else "$url/"
}

object ApiClient {
    /** Customer-app client. */
    fun create(
        baseUrl: () -> String,
        tokens: TokenStore,
        onSessionExpired: () -> Unit,
        debugLogging: Boolean = false,
        configure: OkHttpClient.Builder.() -> Unit = {},
    ): Pair<DejavuApi, OkHttpClient> {
        val (retrofit, client) = retrofit(baseUrl, tokens, onSessionExpired, debugLogging, configure)
        return retrofit.create(DejavuApi::class.java) to client
    }

    /** Shared Retrofit setup for any Dejavu API interface (customer or staff). */
    fun retrofit(
        baseUrl: () -> String,
        tokens: TokenStore,
        onSessionExpired: () -> Unit,
        debugLogging: Boolean = false,
        configure: OkHttpClient.Builder.() -> Unit = {},
    ): Pair<Retrofit, OkHttpClient> {
        val base = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // Free hosting (e.g. Render) sleeps when idle and needs up to a minute to wake up.
            .readTimeout(75, TimeUnit.SECONDS)
            .addInterceptor(BaseUrlInterceptor(baseUrl))
            .apply(configure)
            .build()

        val client = base.newBuilder()
            .addInterceptor(AuthInterceptor(tokens))
            .authenticator(
                TokenAuthenticator(
                    tokens = tokens,
                    refreshClient = base,
                    refreshUrl = { PLACEHOLDER_BASE + "auth/refresh" },
                    onSessionExpired = onSessionExpired,
                ),
            )
            .apply {
                if (debugLogging) addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC))
            }
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(PLACEHOLDER_BASE)
            .client(client)
            .addConverterFactory(ApiJson.asConverterFactory("application/json".toMediaType()))
            .build()
        return retrofit to client
    }
}
