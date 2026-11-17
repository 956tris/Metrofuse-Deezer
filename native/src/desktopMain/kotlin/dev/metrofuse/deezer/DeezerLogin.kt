package dev.metrofuse.deezer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.metrofuse.api.LoginUiContext
import dev.metrofuse.api.NativeLoginUi
import dev.metrofuse.api.PluginResources
import dev.metrofuse.api.WebLoginRequest
import dev.metrofuse.sdk.PluginAlertDialog
import dev.metrofuse.sdk.WebViewLabels
import dev.metrofuse.sdk.WebViewLoginScreen
import dev.metrofuse.sdk.WebViewLoginUi
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class DeezerLoginScreen(
    private val request: WebLoginRequest,
    private val strings: Map<String, String>,
    private val resources: PluginResources
) : NativeLoginUi {
    private val sdkScreen = WebViewLoginScreen(
        WebViewLoginUi(
            request,
            WebViewLabels(
                strings.getValue("title"), strings.getValue("instructions"), strings.getValue("error"),
                strings.getValue("retry"), strings.getValue("cancel"), strings.getValue("login"), strings.getValue("logout")
            )
        )
    )

    @Composable
    override fun Content(context: LoginUiContext) {
        if (!System.getProperty("os.name").startsWith("Windows") || context.account.loggedIn || !context.ready) {
            sdkScreen.Content(context)
            return
        }
        val submit by rememberUpdatedState(context.submit)
        var attempt by remember { mutableIntStateOf(0) }
        var manual by remember { mutableStateOf(false) }
        var token by remember { mutableStateOf("") }
        var running by remember { mutableStateOf(false) }
        var failed by remember { mutableStateOf(false) }
        var captured by remember { mutableStateOf(false) }
        LaunchedEffect(request, attempt, manual) {
            if (manual) return@LaunchedEffect
            running = true
            failed = false
            captured = false
            try {
                val header = withTimeout(5 * 60_000L) {
                    runDeezerBrowser(resources, request, strings.getValue("title"))
                }
                val arl = DeezerAccounts.extractArl(header) ?: throw DeezerFailure("INVALID_CREDENTIALS")
                captured = true
                submit(mapOf(request.cookieField to arl))
            } catch (cancelled: CancellationException) {
                if (cancelled !is TimeoutCancellationException) throw cancelled
                failed = true
            } catch (_: Exception) {
                failed = true
            } finally {
                running = false
            }
        }
        PluginAlertDialog(
            onDismissRequest = context.dismiss,
            title = { Text(strings.getValue("title")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (manual) {
                        Text(strings.getValue("sessionHint"))
                        OutlinedTextField(
                            value = token,
                            onValueChange = { token = it.take(192) },
                            label = { Text(strings.getValue("session")) },
                            enabled = !context.busy,
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text(strings.getValue(if (running) "browserOpened" else "browserOpening"))
                    }
                    if (running || context.busy) CircularProgressIndicator()
                    if (failed) Text(strings.getValue("browserUnavailable"))
                    if (context.failed) Text(strings.getValue("error"))
                    TextButton(enabled = !context.busy, onClick = { manual = !manual }) {
                        Text(strings.getValue(if (manual) "useBrowser" else "useSession"))
                    }
                }
            },
            confirmButton = {
                if (manual) {
                    TextButton(
                        enabled = !context.busy && DeezerAccounts.extractArl(token) != null,
                        onClick = { submit(mapOf(request.cookieField to token.trim())); token = "" }
                    ) { Text(strings.getValue("login")) }
                } else if (failed || (captured && context.failed)) {
                    TextButton(enabled = !running && !context.busy, onClick = { attempt++ }) {
                        Text(strings.getValue("retry"))
                    }
                }
            },
            dismissButton = { TextButton(onClick = context.dismiss) { Text(strings.getValue("cancel")) } }
        )
    }
}

/** Load the helper from this plugin's assets, independently of the host SDK's resources. */
private suspend fun runDeezerBrowser(
    resources: PluginResources,
    request: WebLoginRequest,
    title: String
): String = withContext(Dispatchers.IO) {
    val directory = Files.createTempDirectory("metrofuse-deezer-login-").toFile()
    var process: Process? = null
    try {
        val executable = File(directory, "deezer-browser.exe")
        executable.writeBytes(resources.readAsset("assets/windows/deezer-browser.exe"))
        val requestFile = File(directory, "request.json")
        requestFile.writeText(buildJsonObject {
            put("url", request.url)
            put("cookieUrl", request.cookieUrl)
            put("cookieField", request.cookieField)
            put("requiredCookies", JsonArray(request.requiredCookies.map(::JsonPrimitive)))
            put("allowedHosts", JsonArray(request.allowedHosts.map(::JsonPrimitive)))
        }.toString())
        val builder = ProcessBuilder(executable.absolutePath, requestFile.absolutePath, title, File(directory, "profile").absolutePath)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
        builder.environment()["DOTNET_BUNDLE_EXTRACT_BASE_DIR"] = File(directory, "runtime").absolutePath
        val browser = builder.start().also { process = it }
        val output = coroutineScope {
            val reader = async(Dispatchers.IO) {
                browser.inputStream.use { input ->
                    val bytes = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        if (bytes.size() + count > 131_072) throw DeezerFailure("INVALID_BROWSER_RESPONSE")
                        bytes.write(buffer, 0, count)
                    }
                    bytes.toString(Charsets.UTF_8.name())
                }
            }
            try {
                while (browser.isAlive) delay(100)
                if (browser.exitValue() != 0) throw DeezerFailure("BROWSER_UNAVAILABLE")
                reader.await()
            } finally {
                if (browser.isAlive) browser.destroyForcibly()
            }
        }
        val root = Json.parseToJsonElement(output) as? JsonObject ?: throw DeezerFailure("INVALID_BROWSER_RESPONSE")
        root.obj("values").text(request.cookieField) ?: throw DeezerFailure("INVALID_BROWSER_RESPONSE")
    } finally {
        withContext(NonCancellable + Dispatchers.IO) {
            process?.let {
                if (it.isAlive) it.destroyForcibly()
                it.waitFor(2, TimeUnit.SECONDS)
            }
            // Only this newly created private directory is removed, after the browser exits.
            repeat(40) {
                if (!directory.exists() || directory.deleteRecursively()) return@withContext
                delay(250)
            }
            directory.deleteOnExit()
        }
    }
}
