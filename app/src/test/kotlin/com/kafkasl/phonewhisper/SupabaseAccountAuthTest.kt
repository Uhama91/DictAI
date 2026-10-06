package com.kafkasl.phonewhisper

import okhttp3.mockwebserver.MockResponse
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SupabaseAccountAuthTest {
    private val server = MockWebServer()
    private lateinit var auth: SupabaseAccountAuth
    private val user = "10000000-0000-4000-8000-000000000001"
    private val attempt = AccountOAuthAttempt("a".repeat(43), "b".repeat(43), 1000)
    @Before fun before() { server.start(); auth = SupabaseAccountAuth(server.url("/"), "sb_publishable_test") }
    @After fun after() { server.shutdown() }

    @Test fun pkceUsesRfc7636S256Example() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", SupabaseAccountAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }
    @Test fun authorizationUsesGoogleAndFreshRandomVerifier() {
        val first = auth.newAttempt(1000); val second = auth.newAttempt(1000)
        assertNotEquals(first.verifier, second.verifier)
        assertTrue(first.verifier.length >= 43)
        val url = auth.authorizeUrl(first)
        assertEquals("google", url.queryParameter("provider"))
        assertEquals("s256", url.queryParameter("code_challenge_method"))
        assertEquals(SupabaseAccountAuth.challenge(first.verifier), url.queryParameter("code_challenge"))
        assertTrue(url.queryParameter("redirect_to")!!.startsWith(SupabaseAccountAuth.CALLBACK + "?flow="))
        assertFalse(url.toString().contains(first.verifier))
    }
    @Test fun authorizationRequestsOpenIdWithoutAdditionalGoogleScopes() {
        val url = auth.authorizeUrl(attempt)
        assertEquals(listOf("openid"), url.queryParameterValues("scopes"))
        assertEquals(0, server.requestCount)
    }
    @Test fun callbackAcceptsOnlyPendingFlowOnExactCallback() {
        assertEquals("code", auth.callbackCode("${SupabaseAccountAuth.CALLBACK}?flow=${attempt.state}&code=code", attempt, 1100))
        listOf(
            "https://attacker.test/auth/callback?flow=${attempt.state}&code=code",
            "${SupabaseAccountAuth.CALLBACK}/other?flow=${attempt.state}&code=code",
            "${SupabaseAccountAuth.CALLBACK}?flow=other&code=code",
            "${SupabaseAccountAuth.CALLBACK}?flow=${attempt.state}&code=one&code=two",
            "${SupabaseAccountAuth.CALLBACK}?flow=${attempt.state}&code=code#access_token=bad",
        ).forEach { callback -> assertThrows(IllegalArgumentException::class.java) { auth.callbackCode(callback, attempt, 1100) } }
    }
    @Test fun expiredFlowCannotExchangeCode() {
        assertThrows(IllegalArgumentException::class.java) { auth.callbackCode("${SupabaseAccountAuth.CALLBACK}?flow=${attempt.state}&code=code", attempt, 1000 + 601_000) }
        assertEquals(0, server.requestCount)
    }
    @Test fun exchangeSendsVerifierInBodyAndReadsIdentity() {
        server.enqueue(MockResponse().setBody(sessionBody(user)))
        val session = auth.exchange("code", attempt, 1000)
        assertEquals(user, session.userId)
        assertEquals("teacher@example.test", session.email)
        assertEquals(3_601_000, session.expiresAt)
        val request = server.takeRequest()
        assertEquals("/auth/v1/token?grant_type=pkce", request.path)
        assertEquals("sb_publishable_test", request.getHeader("apikey"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("code", body.getString("auth_code"))
        assertEquals(attempt.verifier, body.getString("code_verifier"))
        assertFalse(request.path!!.contains(attempt.verifier))
    }
    @Test fun refreshRejectsUnexpectedUser() {
        server.enqueue(MockResponse().setBody(sessionBody("20000000-0000-4000-8000-000000000002")))
        assertThrows(IllegalArgumentException::class.java) { auth.refresh(AccountSession(user, "teacher@example.test", "old", "refresh", 1000), 2000) }
    }
    @Test fun refreshReadsRotatedCredentials() {
        server.enqueue(MockResponse().setBody(sessionBody(user)))
        val refreshed = auth.refresh(AccountSession(user, "teacher@example.test", "old", "old-refresh", 1000), 2000)
        assertEquals("new-refresh", refreshed.refreshToken)
        val request = server.takeRequest()
        assertEquals("/auth/v1/token?grant_type=refresh_token", request.path)
        assertEquals("old-refresh", JSONObject(request.body.readUtf8()).getString("refresh_token"))
    }
    @Test fun authErrorsDoNotExposeProviderResponsesOrTokens() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("secret-provider-body"))
        val failure = assertThrows(java.io.IOException::class.java) { auth.exchange("private-code", attempt, 1000) }
        assertFalse(failure.message.orEmpty().contains("secret-provider-body"))
        assertFalse(failure.message.orEmpty().contains("private-code"))
    }
    @Test fun oversizedSessionIsRejected() {
        server.enqueue(MockResponse().setBody("x".repeat(100_000)))
        assertThrows(java.io.IOException::class.java) { auth.exchange("code", attempt, 1000) }
    }
    @Test fun credentialEndpointsMustUseHttpsWithoutUserinfoOrQueries() {
        listOf("http://remote.example.test/", "https://user:password@example.test/", "https://example.test/?key=value",
            "https://example.test/#fragment").forEach {
            assertThrows(IllegalArgumentException::class.java) { SupabaseAccountAuth(it.toHttpUrl(), "sb_publishable_test") }
        }
    }
    @Test fun malformedCallbackDoesNotAppearInException() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            auth.callbackCode("${SupabaseAccountAuth.CALLBACK}?flow=private code", attempt, 1100)
        }
        assertFalse(failure.message.orEmpty().contains("private"))
    }
    @Test fun excessivelyNestedResponseIsRefusedWithoutStackOverflow() {
        server.enqueue(MockResponse().setBody("[".repeat(3000) + "0" + "]".repeat(3000)))
        assertThrows(java.io.IOException::class.java) { auth.exchange("code", attempt, 1000) }
    }
    @Test fun signOutRevokesOnlyThisDeviceSession() {
        server.enqueue(MockResponse().setResponseCode(204))
        auth.revoke(AccountSession(user, "teacher@example.test", "access", "refresh", 1000))
        val request = server.takeRequest()
        assertEquals("/auth/v1/logout?scope=local", request.path)
        assertEquals("Bearer access", request.getHeader("Authorization"))
    }
    private fun sessionBody(id: String) = """{"access_token":"new-access","refresh_token":"new-refresh","token_type":"bearer","expires_in":3600,"user":{"id":"$id","email":"teacher@example.test"}}"""
}
