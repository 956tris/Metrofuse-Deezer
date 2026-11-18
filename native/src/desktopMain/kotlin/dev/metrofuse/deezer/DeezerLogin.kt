package dev.metrofuse.deezer

import androidx.compose.runtime.Composable
import dev.metrofuse.api.LoginUiContext
import dev.metrofuse.api.NativeLoginUi
import dev.metrofuse.api.WebLoginRequest
import dev.metrofuse.sdk.WebViewLabels
import dev.metrofuse.sdk.WebViewLoginScreen
import dev.metrofuse.sdk.WebViewLoginUi

/** Use the same platform browser and credential handoff as the YouTube Music plugin. */
internal class DeezerLoginScreen(
    request: WebLoginRequest,
    strings: Map<String, String>,
) : NativeLoginUi {
    private val screen = WebViewLoginScreen(
        WebViewLoginUi(
            request,
            WebViewLabels(
                strings.getValue("title"),
                strings.getValue("instructions"),
                strings.getValue("error"),
                strings.getValue("retry"),
                strings.getValue("cancel"),
                strings.getValue("login"),
                strings.getValue("logout"),
            ),
        ),
    )

    @Composable
    override fun Content(context: LoginUiContext) {
        screen.Content(context)
    }
}
