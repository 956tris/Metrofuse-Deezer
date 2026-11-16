package dev.metrofuse.deezer

import androidx.compose.runtime.Composable
import dev.metrofuse.api.HomePlugin
import dev.metrofuse.api.HomeRequest
import dev.metrofuse.api.LoginCredentials
import dev.metrofuse.api.LoginField
import dev.metrofuse.api.LoginRequest
import dev.metrofuse.api.LoginUiContext
import dev.metrofuse.api.NativeLoginUi
import dev.metrofuse.api.NativePlugin
import dev.metrofuse.api.PluginContext
import dev.metrofuse.api.WebLoginPlugin
import dev.metrofuse.api.WebLoginRequest
import dev.metrofuse.sdk.WebViewLabels
import dev.metrofuse.sdk.WebViewLoginScreen
import dev.metrofuse.sdk.WebViewLoginUi
import io.ktor.client.HttpClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

class DeezerPlugin :
    NativePlugin,
    WebLoginPlugin,
    NativeLoginUi,
    HomePlugin {
    private var http: HttpClient? = null
    private var cdn: DeezerCdn? = null
    private var proxy: DeezerProxy? = null
    private var buffer: DeezerBuffer? = null
    private var catalog: DeezerCatalog? = null
    private var request: LoginRequest? = null
    private var accountUi: WebViewLoginScreen? = null
    private var settingsObserver: Job? = null
    private val accounts = DeezerAccounts(invalidateStreams = ::invalidateStreams)
    override val loginState get() = accounts.loginState

    override suspend fun loginRequest() = request

    override suspend fun webLoginRequest() = browserRequest()

    override suspend fun login(credentials: LoginCredentials) = accounts.login(credentials)

    override suspend fun logout() = accounts.logout()

    override suspend fun getHome(request: HomeRequest) = checkNotNull(catalog).home(request)

    @Composable
    override fun Content(context: LoginUiContext) {
        accountUi?.Content(context)
    }

    override suspend fun start(context: PluginContext) {
        check(http == null)
        val root = Json.parseToJsonElement(
            context.resources.readAsset("assets/strings.json").toString(Charsets.UTF_8)
        ) as JsonObject
        val strings = root.mapValues { (_, value) ->
            requireNotNull((value as? JsonPrimitive)?.contentOrNull)
        }
        request =
            LoginRequest(
                listOf(LoginField("cookie", strings.getValue("session"))),
                strings.getValue("instructions")
            )
        accountUi = WebViewLoginScreen(
            WebViewLoginUi(
                browserRequest(),
                WebViewLabels(
                    title = strings.getValue("title"),
                    hint = strings.getValue("instructions"),
                    error = strings.getValue("error"),
                    retry = strings.getValue("retry"),
                    cancel = strings.getValue("cancel"),
                    login = strings.getValue("login"),
                    logout = strings.getValue("logout")
                )
            )
        )
        val client = createDeezerHttp().also { http = it }
        val mediaOpener = DeezerCdn().also { cdn = it }
        val mediaProxy = DeezerProxy(context.scope, mediaOpener).also { proxy = it }
        val mediaBuffer = DeezerBuffer(mediaOpener).also { buffer = it }
        val provider = DeezerCatalog(client, accounts, strings).also { catalog = it }
        context.registerSearchProvider(provider)
        context.registerCatalogProvider(provider)
        context.registerMetadataSource(provider)
        context.registerLyricsProvider(provider)
        context.registerAudioSource(
            DeezerAudio(
                context.settings,
                DeezerMediaResolver(client, accounts),
                mediaOpener,
                mediaProxy,
                mediaBuffer
            )
        )
        var previous = StreamingSettings.from(context.settings.values.value)
        settingsObserver = context.scope.launch {
            context.settings.values.collect { values ->
                val current = StreamingSettings.from(values)
                if (previous != current) invalidateStreams()
                previous = current
            }
        }
    }

    private fun invalidateStreams() {
        proxy?.clear()
        cdn?.cancelRequests()
        buffer?.clear()
    }

    override suspend fun stop() {
        settingsObserver?.cancel()
        settingsObserver = null
        accounts.logout()
        proxy?.close()
        proxy = null
        buffer?.close()
        buffer = null
        cdn?.close()
        cdn = null
        http?.close()
        http = null
        catalog = null
        accountUi = null
        request = null
    }

    internal fun browserRequest() = WebLoginRequest(
        url = "https://www.deezer.com/login",
        cookieUrl = "https://www.deezer.com",
        cookieField = "cookie",
        requiredCookies = listOf("arl"),
        allowedHosts = listOf(
            "www.deezer.com", "deezer.com", "auth.deezer.com", "connect.deezer.com", "consent.deezer.com",
            "accounts.google.com", "accounts.google.co.uk", "www.google.com", "facebook.com", "www.facebook.com", "m.facebook.com", "appleid.apple.com"
        )
    )
}
