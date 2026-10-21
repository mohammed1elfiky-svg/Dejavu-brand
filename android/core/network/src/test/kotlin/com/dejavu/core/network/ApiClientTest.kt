package com.dejavu.core.network

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ApiClientTest {
    private lateinit var server: MockWebServer
    private val tokens = InMemoryTokenStore(Tokens("old-access", "old-refresh"))
    private var expired = 0
    private val refreshCalls = AtomicInteger()

    private val me = """{"id":"c1","code":"CUS-000001","name":"منى","phone":"01000000001"}"""
    private val auth = """{"accessToken":"new-access","refreshToken":"new-refresh","customer":$me}"""

    private fun api() = ApiClient.create(
        baseUrl = { server.url("/api/v1/").toString() },
        tokens = tokens,
        onSessionExpired = { expired++ },
    ).first

    @Before fun setUp() {
        server = MockWebServer()
    }

    @After fun tearDown() = server.shutdown()

    /** Accepts only "new-access"; refresh succeeds when [refreshOk]. */
    private fun backend(refreshOk: Boolean) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
            "/api/v1/auth/refresh" -> {
                refreshCalls.incrementAndGet()
                Thread.sleep(50) // widen the race window for the concurrency test
                if (refreshOk && request.body.readUtf8().contains("old-refresh")) MockResponse().setBody(auth)
                else MockResponse().setResponseCode(401).setBody("""{"message":"Refresh token expired or already used"}""")
            }
            else ->
                if (request.getHeader("Authorization") == "Bearer new-access") MockResponse().setBody(me)
                else MockResponse().setResponseCode(401).setBody("""{"message":"Invalid or expired access token"}""")
        }
    }

    @Test
    fun `refreshes an expired token and retries the call`() = runBlocking {
        server.dispatcher = backend(refreshOk = true)
        val customer = api().me()
        assertEquals("منى", customer.name)
        assertEquals(Tokens("new-access", "new-refresh"), tokens.current())
        assertEquals(1, refreshCalls.get())
        assertEquals(0, expired)
    }

    @Test
    fun `refreshes only once for concurrent 401s`() = runBlocking {
        server.dispatcher = backend(refreshOk = true)
        val api = api()
        val results = (1..5).map { async(kotlinx.coroutines.Dispatchers.IO) { api.me() } }.awaitAll()
        assertTrue(results.all { it.id == "c1" })
        assertEquals(1, refreshCalls.get())
    }

    @Test
    fun `ends the session when the refresh token is rejected`() = runBlocking {
        server.dispatcher = backend(refreshOk = false)
        val result = apiCall { api().me() }
        val error = result.exceptionOrNull() as ApiException
        assertEquals(401, error.status)
        assertNull(tokens.current())
        assertEquals(1, expired)
    }

    @Test
    fun `does not attach tokens to login and does not refresh on bad credentials`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"Invalid credentials","statusCode":401}"""))
        val result = apiCall { api().login(com.dejavu.core.model.LoginRequest("01000000001", "wrong")) }
        assertEquals("Invalid credentials", result.exceptionOrNull()!!.message)
        assertNull(server.takeRequest().getHeader("Authorization"))
        assertEquals(0, refreshCalls.get())
        assertEquals(Tokens("old-access", "old-refresh"), tokens.current())
    }

    @Test
    fun `parses validation and checkout errors`() {
        val validation = ApiException.parse(400, """{"statusCode":400,"message":["phone must be valid","password too short"]}""")
        assertEquals("phone must be valid\npassword too short", validation.message)

        val cart = ApiException.parse(422, """{"message":"Cart needs attention","problems":["DJV-1: Only 2 left in stock"]}""")
        assertEquals(listOf("DJV-1: Only 2 left in stock"), cart.problems)

        assertEquals("الخادم غير متاح حالياً، حاول لاحقاً", ApiException.parse(502, "<html>").message)
    }

    @Test
    fun `rebases requests onto the configured server`() {
        val placeholder = "http://dejavu.invalid/api/v1/store/products?page=2".toHttpUrl()
        assertEquals(
            "https://abc-3000.app.github.dev/api/v1/store/products?page=2",
            BaseUrlInterceptor.rebase(placeholder, "abc-3000.app.github.dev").toString(),
        )
        assertEquals("http://10.0.2.2:3000/api/v1/", normalizeBaseUrl("http://10.0.2.2:3000/api/v1"))
    }

    @Test
    fun `refreshes staff sessions too (response carries a user, not a customer)`() = runBlocking {
        val staffAuth = """{"accessToken":"new-access","refreshToken":"new-refresh","user":{"id":"u1","permissions":["orders.read"]}}"""
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/v1/auth/refresh" -> MockResponse().setBody(staffAuth)
                else -> if (request.getHeader("Authorization") == "Bearer new-access") MockResponse().setBody(me) else MockResponse().setResponseCode(401)
            }
        }
        api().me()
        assertEquals(Tokens("new-access", "new-refresh"), tokens.current())
        assertEquals(0, expired)
    }
}
