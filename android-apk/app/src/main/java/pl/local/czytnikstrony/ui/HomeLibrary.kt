@file:OptIn(ExperimentalMaterial3Api::class)

package pl.local.czytnikstrony

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// ═══════════════════════════════════════════════════════════════════════
//  Start
// ═══════════════════════════════════════════════════════════════════════

@Composable
fun HomeScreen(state: AppState) {
    @Suppress("UNUSED_VARIABLE") val t1 = state.readerTick
    @Suppress("UNUSED_VARIABLE") val t2 = state.libraryTick
    @Suppress("UNUSED_VARIABLE") val t3 = state.voiceTick
    val r = state.reader
    val ctx = state.activity
    val history = state.library.history()
    val cont = state.library.lastUnfinished()?.takeIf { it.id != r.articleId || !r.isActive }
    val hasNeural = VoiceCatalog.MODELS.any { VoiceCatalog.isInstalled(ctx, it.id) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(Modifier.padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Czytnik", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                    Text("Artykuły czytane na głos — także offline",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.updateVersion?.let { v ->
                    FilledTonalButton(onClick = { state.activity.openUpdatePage() }) {
                        Icon(Icons.Rounded.SystemUpdate, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(v)
                    }
                }
            }
        }

        item { InputCard(state) }

        state.extractingUrl?.let { url ->
            item { LoadingCard(Library.domainOf(url), state.extractProgress) { state.cancelExtraction() } }
        }

        if (!hasNeural) item { VoiceOnboardingCard(state) }

        cont?.let { a ->
            item {
                SectionTitle("Kontynuuj")
                ContinueCard(a) { state.openArticle(a) }
            }
        }

        if (history.isNotEmpty()) {
            item { SectionTitle("Ostatnio czytane") }
            items(history.take(6), key = { "h" + it.id }) { a -> ArticleRow(state, a) }
            if (history.size > 6) item {
                TextButton(onClick = { state.tab = 1 }) { Text("Cała biblioteka ›") }
            }
        } else {
            item { HowToCard() }
        }
    }
}

@Composable
private fun InputCard(state: AppState) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(16.dp)) {
            OutlinedTextField(
                value = state.input,
                onValueChange = { state.input = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Wklej link do artykułu lub dowolny tekst…") },
                maxLines = 6,
                shape = RoundedCornerShape(16.dp),
                trailingIcon = {
                    if (state.input.isEmpty()) {
                        IconButton(onClick = {
                            val c = state.pasteFromClipboard()
                            if (c.isEmpty()) state.message = "Schowek jest pusty" else state.input = c
                        }) { Icon(Icons.Rounded.ContentPaste, "Wklej") }
                    } else {
                        IconButton(onClick = { state.input = "" }) { Icon(Icons.Rounded.Close, "Wyczyść") }
                    }
                }
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { state.openInput(state.input) },
                    enabled = state.input.isNotBlank(),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Icon(Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Czytaj teraz")
                }
                OutlinedButton(
                    onClick = { state.queueInput(state.input) },
                    enabled = state.input.isNotBlank(),
                    modifier = Modifier.height(48.dp)
                ) {
                    Icon(Icons.Rounded.PlaylistAdd, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Później")
                }
            }
        }
    }
}

/** Szkielet ładowania strony (pulsujące paski). */
@Composable
private fun LoadingCard(domain: String, progress: Int, onCancel: () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        0.35f, 0.8f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a"
    )
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CoverArt(domain, domain, 48.dp, Modifier.size(48.dp).alpha(pulse + 0.2f))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Wczytuję artykuł…", style = MaterialTheme.typography.titleSmall)
                    Text(domain, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, "Anuluj") }
            }
            Spacer(Modifier.height(12.dp))
            for (w in listOf(1f, 0.9f, 0.6f)) {
                Box(Modifier.fillMaxWidth(w).height(10.dp).clip(RoundedCornerShape(5.dp))
                    .alpha(pulse).background(MaterialTheme.colorScheme.surfaceVariant))
                Spacer(Modifier.height(8.dp))
            }
            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun VoiceOnboardingCard(state: AppState) {
    @Suppress("UNUSED_VARIABLE") val t = state.voiceTick
    val gosia = VoiceCatalog.find(state.activity, "vits-piper-pl_PL-gosia-medium") ?: return
    val p = VoiceCatalog.progressOf(gosia.id)
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.width(10.dp))
                Text("Naturalny głos zamiast robota", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(6.dp))
            Text("Pobierz neuronowy głos Piper — brzmi jak lektor i działa bez internetu.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(14.dp))
            if (p != null) {
                LinearProgressIndicator(
                    progress = { if (p[1] > 0) p[0] / p[1].toFloat() else 0f },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Text("Pobieranie %.0f / %.0f MB".format(p[0] / 1048576.0, p[1] / 1048576.0),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { VoiceCatalog.download(state.activity, gosia); state.voiceTick++ }) {
                        Icon(Icons.Rounded.Download, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Pobierz Gosię (~${gosia.approxMb} MB)")
                    }
                    TextButton(onClick = { state.tab = 2 }) { Text("Inne głosy") }
                }
            }
        }
    }
}

@Composable
private fun ContinueCard(a: Library.Article, onPlay: () -> Unit) {
    Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.clickable(onClick = onPlay)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverArt(a.domain(), a.displayTitle(), 64.dp, Modifier.size(64.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(a.displayTitle(), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(a.domain().ifEmpty { "Własny tekst" }, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { a.progress() }, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.width(10.dp))
            FilledIconButton(onClick = onPlay, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.PlayArrow, "Czytaj dalej")
            }
        }
    }
}

@Composable
private fun HowToCard() {
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Jak to działa", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            HowToLine("1", "W przeglądarce: Udostępnij → „Czytnik strony” — czyta od razu.")
            HowToLine("2", "Udostępnij → „Czytaj później” — zapisuje do kolejki.")
            HowToLine("3", "Sterujesz z powiadomienia, ekranu blokady albo słuchawek.")
        }
    }
}

@Composable
private fun HowToLine(n: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(26.dp).clip(RoundedCornerShape(13.dp)).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center) {
            Text(n, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
}

// ═══════════════════════════════════════════════════════════════════════
//  Wiersz artykułu
// ═══════════════════════════════════════════════════════════════════════

@Composable
fun ArticleRow(state: AppState, a: Library.Article) {
    var menu by remember { mutableStateOf(false) }
    val r = state.reader
    val current = a.id == r.articleId && r.isActive
    val hasText = state.library.hasText(a.id)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { state.openArticle(a) }
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverArt(a.domain(), a.displayTitle(), 52.dp, Modifier.size(52.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(a.displayTitle(), style = MaterialTheme.typography.titleSmall,
                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            val minutes = (a.chars / (15f * 60f)).toInt().coerceAtLeast(1)
            val info = buildString {
                append(a.domain().ifEmpty { "Własny tekst" })
                when {
                    current -> append(" · teraz czyta")
                    !hasText -> append(" · do wczytania")
                    a.finished -> append(" · przeczytane")
                    a.progress() > 0f -> append(" · ${(a.progress() * 100).toInt()}%")
                    else -> append(" · ~$minutes min")
                }
            }
            Text(info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!a.finished && a.progress() > 0f) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { a.progress() }, modifier = Modifier.fillMaxWidth(0.6f).height(3.dp),
                    drawStopIndicator = {})
            }
        }
        if (a.finished) Icon(Icons.Rounded.CheckCircle, "Przeczytane", tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp))
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Więcej") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(if (a.queued) "Usuń z kolejki" else "Czytaj później") },
                    leadingIcon = { Icon(if (a.queued) Icons.Rounded.Close else Icons.Rounded.Schedule, null) },
                    onClick = { menu = false; state.library.setQueued(a.id, !a.queued) }
                )
                DropdownMenuItem(
                    text = { Text("Usuń z biblioteki") },
                    leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                    onClick = {
                        menu = false
                        if (a.id == r.articleId) r.stop()
                        state.library.remove(a.id)
                    }
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  Biblioteka
// ═══════════════════════════════════════════════════════════════════════

@Composable
fun LibraryScreen(state: AppState) {
    @Suppress("UNUSED_VARIABLE") val t1 = state.libraryTick
    @Suppress("UNUSED_VARIABLE") val t2 = state.readerTick
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val queue = state.library.queue()
    val history = state.library.history()

    Column(Modifier.fillMaxSize()) {
        Text("Biblioteka", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp))
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Kolejka (${queue.size})") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Historia") })
        }
        val list = if (tab == 0) queue else history
        if (list.isEmpty()) {
            EmptyState(
                icon = if (tab == 0) Icons.Rounded.PlaylistAdd else Icons.Rounded.Schedule,
                title = if (tab == 0) "Kolejka jest pusta" else "Nic jeszcze nie czytałeś",
                text = if (tab == 0) "W przeglądarce stuknij Udostępnij → „Czytaj później”. Po skończeniu artykułu czytnik sam przejdzie do następnego z kolejki."
                else "Wklej link na ekranie Start albo udostępnij artykuł z przeglądarki."
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (tab == 0) item {
                    Text("Czytane po kolei — po skończeniu artykułu następny włącza się sam.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
                }
                items(list, key = { it.id }) { a -> ArticleRow(state, a) }
            }
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, text: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(72.dp).clip(RoundedCornerShape(36.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(34.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
