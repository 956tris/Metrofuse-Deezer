package dev.metrofuse.deezer

import dev.metrofuse.api.LoginCredentials
import io.ktor.http.HttpHeaders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

class DeezerSessionTest {
    @Test
    fun validatesCapturedArlAndRejectsHeaderInjection() {
        assertEquals(TEST_ARL, DeezerAccounts.extractArl("sid=fixture; arl=$TEST_ARL"))
        assertEquals(TEST_ARL, DeezerAccounts.extractArl(TEST_ARL))
        for (input in listOf(
            "arl=short",
            "xarl=$TEST_ARL",
            "arl=${"z".repeat(192)}",
            "arl=$TEST_ARL\r\nX: bad"
        )) {
            assertNull(DeezerAccounts.extractArl(input))
        }
    }

    @Test
    fun verifiesAccountBeforePublishingLoginAndKeepsPreviousAccountOnFailure() = runBlocking {
        var valid = true
        var invalidations = 0
        val accounts = DeezerAccounts(createClient = { arl ->
            mockHttp(arl) { request ->
                assertTrue(request.headers[HttpHeaders.Cookie].orEmpty().contains("arl=$TEST_ARL"))
                userData(if (valid) 7 else 0)
            }
        }, invalidateStreams = { invalidations++ })
        try {
            assertFalse(accounts.loginState.value.loggedIn)
            assertEquals("Fixture Account", accounts.login(TEST_CREDENTIALS).accountName)
            val previous = assertNotNull(accounts.current())
            valid = false
            assertFailsWith<DeezerFailure> { accounts.login(TEST_CREDENTIALS) }
            assertSame(previous, accounts.current())
            assertTrue(accounts.loginState.value.loggedIn)
            assertEquals(1, invalidations)
            accounts.logout()
            assertTrue(previous.closed)
            assertFalse(accounts.loginState.value.loggedIn)
            assertNull(accounts.current())
            assertEquals(2, invalidations)
        } finally {
            accounts.logout()
        }
    }

    @Test
    fun logoutRejectsAnAuthenticationResultThatArrivesLate() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val accounts = DeezerAccounts(createClient = { arl ->
            mockHttp(arl) {
                started.complete(Unit)
                finish.await()
                userData()
            }
        }, invalidateStreams = {})
        val result = async { accounts.login(TEST_CREDENTIALS) }
        started.await()
        accounts.logout()
        finish.complete(Unit)
        assertFailsWith<CancellationException> { result.await() }
        assertFalse(accounts.loginState.value.loggedIn)
        assertNull(accounts.current())
    }

    @Test
    fun renewsAnExpiredCsrfTokenOnceAndExpiresTheAccountIfVerificationFails() = runBlocking {
        var bootstrap = 0
        var requests = 0
        val accounts = DeezerAccounts(createClient = { arl ->
            mockHttp(arl) { request ->
                when (request.url.parameters["method"]) {
                    "deezer.getUserData" -> userData(if (++bootstrap == 1) 7 else 0)

                    else -> {
                        requests++
                        """{"error":{"VALID_TOKEN_REQUIRED":"synthetic"},"results":{}}"""
                    }
                }
            }
        }, invalidateStreams = {})
        accounts.login(TEST_CREDENTIALS)
        assertFailsWith<DeezerFailure> { accounts.withSession { it.call("song.getData") } }
        assertEquals(2, bootstrap)
        assertEquals(1, requests)
        assertFalse(accounts.loginState.value.loggedIn)
    }

    @Test
    fun credentialsAndSessionDescriptionsDoNotExposeSecrets() = runBlocking<Unit> {
        val accounts =
            DeezerAccounts(createClient = { arl ->
                mockHttp(arl) { userData() }
            }, invalidateStreams = {})
        try {
            accounts.login(TEST_CREDENTIALS)
            assertFalse(TEST_CREDENTIALS.toString().contains(TEST_ARL))
            assertFalse(accounts.current().toString().contains("fixture-license"))
            assertFailsWith<DeezerFailure> {
                accounts.login(LoginCredentials(mapOf("cookie" to "invalid")))
            }
        } finally {
            accounts.logout()
        }
    }
}
