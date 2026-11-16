package dev.metrofuse.deezer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
            "audioQuality" -> setOf("AUTO", "MP3_128", "MP3_320", "FLAC")
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
                "audioQuality" to choice("audioQuality", setOf("AUTO", "MP3_128", "MP3_320", "FLAC"), "AUTO")
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
            StreamingDropdown(
                strings.getValue("streamingSource"),
                current.mode,
                linkedMapOf("ACCOUNT" to strings.getValue("accountSource"), "DZMEDIA" to strings.getValue("dzmediaSource")),
                !saving
            ) { select("streamingMode", it) }
            Text(
                strings.getValue(if (current.mode == "DZMEDIA") "dzmediaStreamingHint" else "accountStreamingHint"),
                style = MaterialTheme.typography.bodySmall
            )
            StreamingDropdown(
                strings.getValue("audioQuality"),
                current.quality,
                linkedMapOf(
                    "AUTO" to strings.getValue("qualityAuto"),
                    "MP3_128" to strings.getValue("quality128"),
                    "MP3_320" to strings.getValue("quality320"),
                    "FLAC" to strings.getValue("qualityFlac")
                ),
                !saving
            ) { select("audioQuality", it) }
            if (failed) Text(strings.getValue("settingsError"), color = MaterialTheme.colorScheme.error)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StreamingDropdown(
    label: String,
    value: String,
    options: Map<String, String>,
    enabled: Boolean,
    select: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it }
    ) {
        OutlinedTextField(
            value = options[value].orEmpty(),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (key, title) ->
                DropdownMenuItem(text = { Text(title) }, onClick = {
                    expanded = false
                    select(key)
                })
            }
        }
    }
}
