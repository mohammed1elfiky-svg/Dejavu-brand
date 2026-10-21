package com.dejavu.core.admin

import com.dejavu.core.network.ApiClient
import com.dejavu.core.network.InMemoryTokenStore
import com.dejavu.core.network.Tokens
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StaffSessionTest {
    @Test
    fun `an unreachable server at launch reports an error instead of loading forever`() = runBlocking {
        val tokens = InMemoryTokenStore(Tokens("access", "refresh"))
        lateinit var session: StaffSessionRepository
        // Nothing listens on port 1.
        val (retrofit, _) = ApiClient.retrofit({ "http://127.0.0.1:1/api/v1/" }, tokens, { session.onSessionExpired() })
        session = StaffSessionRepository(retrofit.create(AdminApi::class.java), tokens)

        assertEquals(StaffSession.LoggedIn(null), session.state.value)
        session.restore()
        assertNotNull(session.restoreError.value)
        assertEquals(StaffSession.LoggedIn(null), session.state.value)

        // Signing out from the error screen clears it and shows the login.
        session.onSessionExpired()
        assertNull(session.restoreError.value)
        assertEquals(StaffSession.LoggedOut, session.state.value)
    }
}
