package dev.metrofuse.deezer

import dev.metrofuse.api.LoginCredentials
import dev.metrofuse.api.PluginLoginState
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val GATEWAY = "https://www.deezer.com/ajax/gw-light.php"
internal const val MEDIA_API = "https://media.deezer.com/v1/get_url"
internal const val PUBLIC_API = "https://api.deezer.com"
internal const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

internal class DeezerFailure(val code: String, val retryable: Boolean = false) : Exception(code)

internal fun createDeezerHttp(arl: String? = null): HttpClient = HttpClient {
    followRedirects = false
    install(HttpTimeout) {
        requestTimeoutMillis = 7000
        connectTimeoutMillis = 4000
        socketTimeoutMillis = 7000
    }
    install(HttpCookies) {
        storage = AcceptAllCookiesStorage()
        if (arl !=
            null
        ) {
            default {
                addCookie(
                    Url("https://www.deezer.com/"),
                    Cookie("arl", arl, domain = ".deezer.com", path = "/", secure = true)
                )
            }
        }
    }
}

internal suspend fun HttpResponse.jsonValue(): JsonElement {
    if (!status.isSuccess()) {
        throw DeezerFailure(
            when (status) {
                HttpStatusCode.Unauthorized -> "SESSION_EXPIRED"
                HttpStatusCode.Forbidden -> "ACCESS_DENIED"
                HttpStatusCode.NotFound -> "NOT_FOUND"
                HttpStatusCode.TooManyRequests -> "RATE_LIMITED"
                else -> "HTTP_ERROR"
            },
            status.value >= 500 || status.value == 429
        )
    }
    val buffer = ByteArray(8192)
    val bytes = ByteArrayOutputStream()
    val channel = bodyAsChannel()
    while (true) {
        val count = channel.readAvailable(buffer, 0, buffer.size)
        if (count == -1) break
        if (bytes.size() + count > 4 * 1024 * 1024) {
            channel.cancel(null)
            throw DeezerFailure("RESPONSE_TOO_LARGE")
        }
        bytes.write(buffer, 0, count)
    }
    return try {
        Json.parseToJsonElement(bytes.toString(Charsets.UTF_8.name()))
    } catch (_: Exception) {
        throw DeezerFailure("INVALID_RESPONSE")
    }
}

internal suspend fun HttpClient.publicGet(
    path: String,
    language: String = Locale.getDefault().toLanguageTag()
): JsonObject {
    require(path.startsWith('/') && !path.startsWith("//") && !path.contains('#'))
    val root = get(PUBLIC_API + path) {
        header("User-Agent", USER_AGENT)
        header("Accept-Language", language)
        header("Accept", "application/json")
    }.jsonValue() as? JsonObject ?: throw DeezerFailure("INVALID_RESPONSE")
    if (root.containsKey("error")) {
        throw DeezerFailure(
            if (root.obj("error").text("code") ==
                "800"
            ) {
                "NOT_FOUND"
            } else {
                "CATALOG_ERROR"
            }
        )
    }
    return root
}

internal class DeezerSession(private val client: HttpClient) : AutoCloseable {
    private val renewal = Mutex()
    private var refreshedAt = 0L
    private var checkForm = ""
    var userId = ""
        private set
    var accountName = ""
        private set
    var favoritesId: String? = null
        private set
    var licenseToken = ""
        private set
    var closed = false
        private set

    suspend fun initialize() = refresh()

    private suspend fun refresh(force: Boolean = false) = renewal.withLock {
        if (closed) throw CancellationException("Deezer session closed")
        if (!force && checkForm.isNotEmpty() &&
            System.currentTimeMillis() - refreshedAt < 45 * 60_000
        ) {
            return@withLock
        }
        val root =
            raw("deezer.getUserData", EMPTY_OBJECT, "") as? JsonObject
                ?: throw DeezerFailure("INVALID_RESPONSE")
        val user = root.obj("USER")
        val id = deezerId(user.text("USER_ID")) ?: throw DeezerFailure("SESSION_EXPIRED")
        if (userId.isNotEmpty() && userId != id) throw DeezerFailure("SESSION_EXPIRED")
        val token =
            root.text("checkForm")?.takeIf { it != "0" } ?: throw DeezerFailure("SESSION_EXPIRED")
        userId = id
        accountName =
            user.text("BLOG_NAME", "USER_NAME", "USER_PSEUDO") ?: user.obj("DATA").text("name")
                ?: id
        favoritesId = deezerId(user.text("LOVEDTRACKS_ID"))
        licenseToken = user.obj("OPTIONS").text("license_token").orEmpty()
        checkForm = token
        refreshedAt = System.currentTimeMillis()
    }

    suspend fun call(method: String, arguments: JsonObject = EMPTY_OBJECT): JsonElement {
        refresh()
        return try {
            raw(method, arguments, checkForm)
        } catch (failure: DeezerFailure) {
            if (failure.code != "VALID_TOKEN_REQUIRED" && failure.code != "4") throw failure
            refresh(force = true)
            raw(method, arguments, checkForm)
        }
    }

    suspend fun fullMedia(
        trackToken: String,
        formats: List<String>,
        forceRefresh: Boolean = false
    ): JsonObject {
        refresh(forceRefresh)
        if (licenseToken.isEmpty()) throw DeezerFailure("SUBSCRIPTION_REQUIRED")
        val body = buildJsonObject {
            put("license_token", licenseToken)
            put(
                "media",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("type", "FULL")
                            put(
                                "formats",
                                JsonArray(
                                    formats.map { format ->
                                        buildJsonObject {
                                            put("cipher", "BF_CBC_STRIPE")
                                            put("format", format)
                                        }
                                    }
                                )
                            )
                        }
                    )
                )
            )
            put("track_tokens", JsonArray(listOf(JsonPrimitive(trackToken))))
        }
        return client.post(MEDIA_API) {
            header("User-Agent", USER_AGENT)
            header("Referer", "https://www.deezer.com/")
            header("Origin", "https://www.deezer.com")
            header("Content-Type", "application/json")
            setBody(body.toString())
        }.jsonValue() as? JsonObject ?: throw DeezerFailure("INVALID_RESPONSE")
    }

    private suspend fun raw(method: String, arguments: JsonObject, token: String): JsonElement {
        val root =
            client.post(
                "$GATEWAY?method=${method.encodeURLParameter()}&input=3&api_version=1.0&api_token=${token.encodeURLParameter()}"
            ) {
                header("User-Agent", USER_AGENT)
                header("Accept-Language", Locale.getDefault().toLanguageTag())
                header("Referer", "https://www.deezer.com/")
                header("Origin", "https://www.deezer.com")
                header("Content-Type", "application/json")
                setBody(arguments.toString())
            }.jsonValue() as? JsonObject ?: throw DeezerFailure("INVALID_RESPONSE")
        val errors = root["error"]
        if (errors is JsonObject &&
            errors.isNotEmpty()
        ) {
            throw DeezerFailure(
                errors.keys.first().takeIf {
                    it.matches(Regex("[A-Z0-9_]{1,80}"))
                }
                    ?: "GATEWAY_ERROR"
            )
        }
        if (errors is JsonArray && errors.isNotEmpty()) throw DeezerFailure("GATEWAY_ERROR")
        return root["results"] ?: throw DeezerFailure("INVALID_RESPONSE")
    }

    override fun close() {
        closed = true
        checkForm = ""
        licenseToken = ""
        client.close()
    }

    override fun toString() = "DeezerSession([redacted])"
}

internal class DeezerAccounts(
    private val createClient: (String) -> HttpClient = { createDeezerHttp(it) },
    private val invalidateStreams: () -> Unit
) {
    private val state = MutableStateFlow(PluginLoginState())
    val loginState: StateFlow<PluginLoginState> = state
    private val lock = Any()
    private var revision = 0L

    @Volatile
    private var session: DeezerSession? = null

    fun current() = session

    suspend fun login(credentials: LoginCredentials): PluginLoginState {
        val arl =
            extractArl(credentials.values["cookie"]) ?: throw DeezerFailure("INVALID_CREDENTIALS")
        val generation = synchronized(lock) { ++revision }
        val client = createClient(arl)
        val candidate = DeezerSession(client)
        try {
            candidate.initialize()
            synchronized(lock) {
                if (generation != revision) throw CancellationException("Deezer login replaced")
                val previous = session
                invalidateStreams()
                session = candidate
                state.value = PluginLoginState(true, candidate.accountName)
                previous?.close()
                return state.value
            }
        } catch (failure: Throwable) {
            candidate.close()
            throw failure
        }
    }

    fun logout() {
        synchronized(lock) {
            ++revision
            val previous = session
            session = null
            state.value = PluginLoginState()
            invalidateStreams()
            previous?.close()
        }
    }

    suspend fun <T> withSession(block: suspend (DeezerSession) -> T): T {
        val snapshot = session ?: throw DeezerFailure("LOGIN_REQUIRED")
        try {
            val result = block(snapshot)
            if (session !== snapshot) throw CancellationException("Deezer account changed")
            return result
        } catch (failure: DeezerFailure) {
            if (failure.code in setOf("SESSION_EXPIRED", "VALID_TOKEN_REQUIRED", "4")) {
                synchronized(lock) { if (session === snapshot) logout() }
            }
            throw failure
        }
    }

    companion object {
        fun extractArl(value: String?): String? {
            val input =
                value?.trim()?.takeIf {
                    it.length <= 65_536 && !it.contains('\r') &&
                        !it.contains('\n')
                }
                    ?: return null
            val token = if ('=' in
                input
            ) {
                Regex("(?:^|;\\s*)arl=([^;]+)").find(input)?.groupValues?.get(1)
            } else {
                input
            }
            return token?.takeIf { it.matches(Regex("[a-fA-F0-9]{192}")) }
        }
    }
}
