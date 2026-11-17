package dev.metrofuse.deezer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.metrofuse.api.NativeScreen
import dev.metrofuse.api.PluginContext
import dev.metrofuse.api.PluginSettings
import dev.metrofuse.api.PluginStorage
import dev.metrofuse.api.UiContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Own the enum controls because older hosts render manifest enums as editable text. */
internal class DeezerStreamingPreferences private constructor(
    private val host: PluginSettings,
    private val storage: PluginStorage,
    initial: Map<String, JsonElement>
) : PluginSettings {
    private val gate = Mutex()
    private var choices = initial
    private val state = MutableStateFlow(host.values.value + initial)
    override val values: StateFlow<Map<String, JsonElement>> = state

    override suspend fun set(key: String, value: JsonElement) {
        val options = when (key) {
            "streamingMode" -> setOf("ACCOUNT", "DZMEDIA")
            "audioQuality" -> setOf("AUTO", "MP3_128", "MP3_320", "FLAC", "FLAC_24")
            else -> return host.set(key, value)
        }
        require((value as? JsonPrimitive)?.contentOrNull in options)
        gate.withLock {
            val next = choices + (key to value)
            storage.set(STORAGE_KEY, JsonObject(next))
            choices = next
            state.update { it + next }
        }
    }

    companion object {
        private const val STORAGE_KEY = "streamingChoices"

        suspend fun create(context: PluginContext): DeezerStreamingPreferences {
            val stored = context.storage.get(STORAGE_KEY) as? JsonObject
            val legacy = context.settings.values.value
            fun choice(key: String, options: Set<String>, default: String): JsonPrimitive {
                val value = (stored?.get(key) as? JsonPrimitive)?.contentOrNull
                    ?: (legacy[key] as? JsonPrimitive)?.contentOrNull
                return JsonPrimitive(value?.takeIf { it in options } ?: default)
            }
            val choices = mapOf(
                "streamingMode" to choice("streamingMode", setOf("ACCOUNT", "DZMEDIA"), "ACCOUNT"),
                "audioQuality" to choice("audioQuality", setOf("AUTO", "MP3_128", "MP3_320", "FLAC", "FLAC_24"), "AUTO")
            )
            val preferences = DeezerStreamingPreferences(context.settings, context.storage, choices)
            context.scope.launch {
                context.settings.values.collect { values ->
                    preferences.gate.withLock {
                        preferences.state.value = values + preferences.choices
                    }
                }
            }
            return preferences
        }
    }
}

internal class DeezerSettingsScreen(
    private val settings: PluginSettings,
    private val strings: Map<String, String>
) : NativeScreen {
    @Composable
    override fun Content(context: UiContext) {
        val values by settings.values.collectAsState()
        val current = StreamingSettings.from(values)
        val scope = rememberCoroutineScope()
        var saving by remember { mutableStateOf(false) }
        var failed by remember { mutableStateOf(false) }
        fun select(key: String, value: String) {
            scope.launch {
                saving = true
                failed = false
                try {
                    settings.set(key, JsonPrimitive(value))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failed = true
                } finally {
                    saving = false
                }
            }
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StreamingChoice(
                strings.getValue("streamingSource"),
                current.mode,
                linkedMapOf("ACCOUNT" to strings.getValue("accountSource"), "DZMEDIA" to strings.getValue("dzmediaSource")),
                !saving
            ) { select("streamingMode", it) }
            Text(
                strings.getValue(if (current.mode == "DZMEDIA") "dzmediaStreamingHint" else "accountStreamingHint"),
                style = MaterialTheme.typography.bodySmall
            )
            StreamingChoice(
                strings.getValue("audioQuality"),
                current.quality,
                linkedMapOf(
                    "AUTO" to strings.getValue("qualityAuto"),
                    "MP3_128" to strings.getValue("quality128"),
                    "MP3_320" to strings.getValue("quality320"),
                    "FLAC" to strings.getValue("qualityFlac"),
                    "FLAC_24" to strings.getValue("qualityFlac24")
                ),
                !saving
            ) { select("audioQuality", it) }
            Text(strings.getValue("qualityHint"), style = MaterialTheme.typography.bodySmall)
            if (failed) Text(strings.getValue("settingsError"), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun StreamingChoice(
    label: String,
    value: String,
    options: Map<String, String>,
    enabled: Boolean,
    select: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        OutlinedButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
        ) {
            Text(options[value].orEmpty(), Modifier.weight(1f))
            Text(if (expanded) "▴" else "▾")
        }
        // This plugin's desktop bytecode also ships in the Android APK. A platform popup
        // would link its desktop-only implementation when the control opens on Android.
        if (expanded) {
            Column(Modifier.selectableGroup()) {
                options.forEach { (key, title) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                            selected = key == value,
                            enabled = enabled,
                            role = Role.RadioButton,
                            onClick = {
                                expanded = false
                                if (key != value) select(key)
                            },
                        ),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = key == value, onClick = null, enabled = enabled)
                        Text(title)
                    }
                }
            }
        }
    }
}
