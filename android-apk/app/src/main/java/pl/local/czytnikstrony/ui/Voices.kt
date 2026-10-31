@file:OptIn(ExperimentalMaterial3Api::class)

package pl.local.czytnikstrony

import android.speech.tts.Voice
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun VoicesScreen(state: AppState) {
    @Suppress("UNUSED_VARIABLE") val t1 = state.voiceTick
    @Suppress("UNUSED_VARIABLE") val t2 = state.readerTick
    val r = state.reader
    val ctx = state.activity
    val lang = state.voicesLang
    val selected = r.getVoice(lang)
    val neural = VoiceCatalog.forLang(ctx, lang)
    val system = r.systemEngine.voicesFor(lang)

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text("Głosy", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            Text("Język czytania", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            val modes = listOf("auto" to "Automatycznie", "pl" to "Polski", "en" to "English")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                modes.forEachIndexed { i, (k, label) ->
                    SegmentedButton(
                        selected = r.langMode == k,
                        onClick = { r.setLangMode(k) },
                        shape = SegmentedButtonDefaults.itemShape(i, modes.size)
                    ) { Text(label, maxLines = 1) }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("Głos dla języka", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            val langs = listOf("pl" to "Polski", "en" to "Angielski")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                langs.forEachIndexed { i, (k, label) ->
                    SegmentedButton(
                        selected = lang == k,
                        onClick = { state.voicesLang = k },
                        shape = SegmentedButtonDefaults.itemShape(i, langs.size)
                    ) { Text(label) }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Teraz: " + r.voiceLabel(lang), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary)
        }

        item {
            Spacer(Modifier.height(10.dp))
            SectionTitle("Naturalne · offline")
            Text("Neuronowe głosy Piper działające na telefonie. Pobierasz raz, potem bez internetu.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
        }
        items(neural, key = { it.id }) { m -> NeuralVoiceItem(state, m, lang, selected) }
        item {
            OutlinedButton(onClick = { state.startImport() }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Icon(Icons.Rounded.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("Importuj własny głos (.onnx + .onnx.json)")
            }
        }

        item {
            Spacer(Modifier.height(12.dp))
            SectionTitle("Systemowe")
        }
        item {
            VoiceCard(
                title = "Automatycznie",
                subtitle = "najlepszy zainstalowany głos systemowy",
                initial = "A",
                selected = selected == Reader.VOICE_SYSTEM_AUTO,
                onSelect = { r.setVoice(lang, Reader.VOICE_SYSTEM_AUTO) },
                trailing = { PreviewButton(state, Reader.VOICE_SYSTEM_AUTO, lang) }
            )
        }
        items(system, key = { it.name }) { v: Voice ->
            val spec = Reader.VOICE_SYSTEM_PREFIX + v.name
            val q = when {
                v.quality >= Voice.QUALITY_VERY_HIGH -> "bardzo wysoka jakość"
                v.quality >= Voice.QUALITY_HIGH -> "wysoka jakość"
                else -> "standard"
            }
            VoiceCard(
                title = Reader.prettyVoiceName(v.name),
                subtitle = v.locale.toLanguageTag() + " · " + q + if (v.isNetworkConnectionRequired) " · wymaga internetu" else " · offline",
                initial = Reader.prettyVoiceName(v.name).take(1),
                selected = selected == spec,
                onSelect = { r.setVoice(lang, spec) },
                trailing = { PreviewButton(state, spec, lang) }
            )
        }
        item {
            TextButton(onClick = { state.openSystemTtsSettings() }) {
                Icon(Icons.Rounded.Settings, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Pobierz więcej głosów systemowych")
            }
        }
    }
}

@Composable
private fun NeuralVoiceItem(state: AppState, m: VoiceCatalog.Model, lang: String, selected: String) {
    val ctx = state.activity
    val spec = Reader.VOICE_NEURAL_PREFIX + m.id
    val installed = VoiceCatalog.isInstalled(ctx, m.id)
    val progress = VoiceCatalog.progressOf(m.id)
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }

    val sub = buildString {
        append(m.desc)
        if (m.high()) append(" · HQ")
        when {
            progress != null -> append(" · %.0f / %.0f MB".format(progress[0] / 1048576.0, progress[1] / 1048576.0))
            installed -> append(" · pobrany")
            m.approxMb > 0 -> append(" · ~${m.approxMb} MB")
        }
    }
    VoiceCard(
        title = m.name,
        subtitle = sub,
        initial = m.name.take(1),
        selected = spec == selected,
        enabled = installed,
        onSelect = { if (installed) state.reader.setVoice(lang, spec) },
        trailing = {
            when {
                progress != null -> {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = { if (progress[1] > 0) progress[0] / progress[1].toFloat() else 0f },
                            modifier = Modifier.size(40.dp), strokeWidth = 3.dp
                        )
                        IconButton(onClick = { VoiceCatalog.cancel(m.id) }) { Icon(Icons.Rounded.Close, "Anuluj", Modifier.size(18.dp)) }
                    }
                }
                installed -> {
                    PreviewButton(state, spec, lang)
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Więcej") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Usuń głos") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                                onClick = { menu = false; confirm = true })
                        }
                    }
                }
                else -> FilledTonalButton(onClick = { VoiceCatalog.download(ctx, m); state.voiceTick++ }) {
                    Icon(Icons.Rounded.Download, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Pobierz")
                }
            }
        }
    )
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Usunąć głos ${m.name}?") },
            text = { Text("Zwolni miejsce na telefonie. Możesz go pobrać ponownie.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    if (state.reader.getVoice(lang) == spec) {
                        state.reader.stop()
                        state.reader.setVoice(lang, Reader.VOICE_SYSTEM_AUTO)
                    }
                    VoiceCatalog.delete(ctx, m.id)
                    state.voiceTick++
                }) { Text("Usuń") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Anuluj") } }
        )
    }
}

@Composable
private fun PreviewButton(state: AppState, spec: String, lang: String) {
    val previewing = state.reader.isPreviewing
    IconButton(onClick = { if (previewing) state.reader.stop() else state.reader.preview(spec, lang) }) {
        Icon(if (previewing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, "Odsłuchaj")
    }
}

@Composable
private fun VoiceCard(
    title: String,
    subtitle: String,
    initial: String,
    selected: Boolean,
    enabled: Boolean = true,
    onSelect: () -> Unit,
    trailing: @Composable () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) cs.primaryContainer else cs.surfaceContainer)
            .then(if (selected) Modifier.border(1.5.dp, cs.primary, RoundedCornerShape(18.dp)) else Modifier)
            .clickable(enabled = enabled, onClick = onSelect)
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (enabled) {
            RadioButton(selected = selected, onClick = onSelect)
        } else {
            val colors = coverColors(title, title)
            Box(Modifier.padding(horizontal = 6.dp).size(36.dp).clip(RoundedCornerShape(18.dp))
                .background(androidx.compose.ui.graphics.Brush.linearGradient(colors)),
                contentAlignment = Alignment.Center) {
                Text(initial, color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                color = if (selected) cs.onPrimaryContainer else cs.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = if (selected) cs.onPrimaryContainer else cs.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically) { trailing() }
    }
}
