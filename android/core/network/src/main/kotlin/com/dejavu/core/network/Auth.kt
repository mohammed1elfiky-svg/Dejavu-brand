package com.dejavu.core.network

import com.dejavu.core.model.AuthResponse
import com.dejavu.core.model.RefreshRequest
import kotlinx.serialization.encodeToString
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import java.util.concurrent.atomic.AtomicReference

data class Tokens(val accessToken: String, val refreshToken: String)

/**
 * Where tokens live. [current] must be fast and non-blocking (OkHttp calls it on
 * every request), so implementations keep an in-memory copy of what they persist.
 */
interface TokenStore {
    fun current(): Tokens?
    fun save(tokens: Tokens?)
}

class InMemoryTokenStore(initial: Tokens? = null) : TokenStore {
    private val ref = AtomicReference(initial)
    override fun current(): Tokens? = ref.get()
    override fun save(tokens: Tokens?) = ref.set(tokens)
}

/** Endpoints that authenticate by their body, never by a bearer token. */
private val TOKEN_FREE = listOf("/auth/customer/login", "/auth/customer/register", "/auth/refresh", "/auth/logout")

/** Adds `Authorization: Bearer …` to every API call except the auth endpoints themselves. */
class AuthInterceptor(private val tokens: TokenStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val token = tokens.current()?.accessToken
        if (token == null || request.header("Authorization") != null || isAuthCall(request)) {
            return chain.proceed(request)
        }
        return chain.proceed(request.newBuilder().header("Authorization", "Bearer $token").build())
    }
}

private fun isAuthCall(request: Request): Boolean = TOKEN_FREE.any { request.url.encodedPath.endsWith(it) }

/**
 * On a 401, swaps the refresh token for a new pair and retries the request once.
 * Concurrent 401s refresh only once: whoever comes second sees that the token
 * already changed and simply retries with it. If refreshing fails the session is
 * over: tokens are cleared and [onSessionExpired] is called.
 */
class TokenAuthenticator(
    private val tokens: TokenStore,
    private val refreshClient: OkHttpClient,
    private val refreshUrl: () -> String,
    private val onSessionExpired: () -> Unit,
) : Authenticator {
    private val lock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        if (isAuthCall(response.request) || responseCount(response) > 1) return null
        val usedToken = response.request.header("Authorization")?.removePrefix("Bearer ")

        synchronized(lock) {
            val current = tokens.current() ?: return null
            if (usedToken != null && current.accessToken != usedToken) {
                return response.request.withToken(current.accessToken) // someone else already refreshed
            }
            val renewed = refresh(current.refreshToken)
            if (renewed == null) {
                tokens.save(null)
                onSessionExpired()
                return null
            }
            tokens.save(renewed)
            return response.request.withToken(renewed.accessToken)
        }
    }

    private fun refresh(refreshToken: String): Tokens? {
        val body = ApiJson.encodeToString(RefreshRequest(refreshToken)).toRequestBody(JSON)
        val request = Request.Builder().url(refreshUrl()).post(body).build()
        return runCatching {
            refreshClient.newCall(request).execute().use { res ->
                if (!res.isSuccessful) return null
                val auth = ApiJson.decodeFromString<AuthResponse>(res.body!!.string())
                Tokens(auth.accessToken, auth.refreshToken)
            }
        }.getOrNull()
    }

    private fun Request.withToken(token: String) = newBuilder().header("Authorization", "Bearer $token").build()

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
