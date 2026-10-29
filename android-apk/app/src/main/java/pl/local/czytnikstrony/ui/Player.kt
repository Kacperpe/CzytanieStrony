@file:OptIn(ExperimentalMaterial3Api::class)

package pl.local.czytnikstrony

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun PlayerScreen(state: AppState) {
    @Suppress("UNUSED_VARIABLE") val tick = state.readerTick
    val r = state.reader
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    var showText by rememberSaveable { mutableStateOf(true) }
    var speedSheet by remember { mutableStateOf(false) }
    var sleepSheet by remember { mutableStateOf(false) }
    val cover = coverColors(r.domain, r.displayTitle())

    // Odliczanie timera snu
    var sleepLeft by remember { mutableIntStateOf(r.sleepRemainingSeconds()) }
    LaunchedEffect(tick) {
        while (true) {
            sleepLeft = r.sleepRemainingSeconds()
            if (sleepLeft <= 0) break
            delay(1000)
        }
    }

    Surface(Modifier.fillMaxSize(), color = cs.surface) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(cover[0].copy(alpha = 0.45f), cs.surface, cs.surface)))
        ) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                // ── Górny pasek ──
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { state.playerOpen = false }) {
                        Icon(Icons.Rounded.KeyboardArrowDown, "Zwiń", Modifier.size(30.dp))
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("TERAZ CZYTANE", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                        Text(r.domain.ifEmpty { "Własny tekst" }, style = MaterialTheme.typography.labelLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { showText = !showText }) {
                        Icon(if (showText) Icons.Rounded.Image else Icons.Rounded.Article,
                            if (showText) "Pokaż okładkę" else "Pokaż tekst")
                    }
                }

                // ── Środek: tekst (jak „Lyrics”) albo okładka ──
                AnimatedContent(targetState = showText, modifier = Modifier.weight(1f), label = "mid") { text ->
                    if (text) LyricsView(state)
                    else Column(
                        Modifier.fillMaxSize().padding(horizontal = 32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CoverArt(r.domain, r.displayTitle(), 280.dp,
                            Modifier.widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1f).shadow(16.dp, RoundedCornerShape(56.dp)))
                    }
                }

                // ── Tytuł ──
                Column(Modifier.padding(horizontal = 24.dp)) {
                    Spacer(Modifier.height(8.dp))
                    Text(r.displayTitle(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (r.isLoading) r.status else r.voiceLabel(r.activeLang),
                        style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }

                // ── Pasek czasu ──
                val dur = r.durationSeconds().coerceAtLeast(1)
                var dragging by remember { mutableStateOf(false) }
                var dragValue by remember { mutableFloatStateOf(0f) }
                val pos = if (dragging) dragValue else r.positionSeconds() / dur.toFloat()
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Slider(
                        value = pos.coerceIn(0f, 1f),
                        onValueChange = { dragging = true; dragValue = it },
                        onValueChangeFinished = { dragging = false; r.seekFraction(dragValue) }
                    )
                    Row(Modifier.padding(horizontal = 8.dp)) {
                        Text(fmtTime((pos * dur).toInt()), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        Text("-" + fmtTime(((1f - pos) * dur).toInt()), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    }
                }

                // ── Sterowanie ──
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { r.seekSeconds(-10) }, Modifier.size(52.dp)) {
                        Icon(Icons.Rounded.Replay10, "Cofnij 10 s", Modifier.size(30.dp))
                    }
                    IconButton(onClick = { r.prev() }, Modifier.size(52.dp)) {
                        Icon(Icons.Rounded.SkipPrevious, "Poprzednie zdanie", Modifier.size(34.dp))
                    }
                    val scale by animateFloatAsState(if (r.isPlaying) 1f else 0.94f, label = "s")
                    FilledIconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            r.togglePlayPause()
                        },
                        modifier = Modifier.size((76 * scale).dp),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = cs.primary)
                    ) {
                        if (r.isLoading) CircularProgressIndicator(Modifier.size(30.dp), color = cs.onPrimary, strokeWidth = 3.dp)
                        else Icon(if (r.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            if (r.isPlaying) "Pauza" else "Czytaj", Modifier.size(40.dp))
                    }
                    IconButton(onClick = { r.next() }, Modifier.size(52.dp)) {
                        Icon(Icons.Rounded.SkipNext, "Następne zdanie", Modifier.size(34.dp))
                    }
                    IconButton(onClick = { r.seekSeconds(10) }, Modifier.size(52.dp)) {
                        Icon(Icons.Rounded.Forward10, "Do przodu 10 s", Modifier.size(30.dp))
                    }
                }

                // ── Dodatkowe ──
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                ) {
                    AssistChip(onClick = { speedSheet = true }, label = { Text(fmtRate(r.rate)) },
                        leadingIcon = { Icon(Icons.Rounded.Speed, null, Modifier.size(AssistChipDefaults.IconSize)) })
                    val sleepLabel = when {
                        r.isSleepEndOfArticle -> "Koniec artykułu"
                        sleepLeft > 0 -> fmtTime(sleepLeft)
                        else -> "Timer snu"
                    }
                    AssistChip(onClick = { sleepSheet = true }, label = { Text(sleepLabel) },
                        leadingIcon = { Icon(Icons.Rounded.Bedtime, null, Modifier.size(AssistChipDefaults.IconSize)) })
                    AssistChip(onClick = { state.playerOpen = false; state.voicesLang = r.activeLang; state.tab = 2 },
                        label = { Text("Głos") },
                        leadingIcon = { Icon(Icons.Rounded.RecordVoiceOver, null, Modifier.size(AssistChipDefaults.IconSize)) })
                }
            }
        }
    }

    if (speedSheet) SpeedSheet(state) { speedSheet = false }
    if (sleepSheet) SleepSheet(state) { sleepSheet = false }
}

/** Tekst artykułu z wyróżnionym bieżącym zdaniem; stuknięcie przeskakuje. */
@Composable
private fun LyricsView(state: AppState) {
    @Suppress("UNUSED_VARIABLE") val tick = state.readerTick
    val r = state.reader
    val cs = MaterialTheme.colorScheme
    val list = rememberLazyListState()
    val index = r.index
    val count = r.chunkCount

    LaunchedEffect(index, count) {
        if (count > 0) list.animateScrollToItem((index - 1).coerceAtLeast(0))
    }

    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(count) { i ->
            val current = i == index
            val past = i < index
            Text(
                r.chunk(i),
                style = if (current) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                fontWeight = if (current) FontWeight.Bold else FontWeight.Medium,
                color = when {
                    current -> cs.onSurface
                    past -> cs.onSurface.copy(alpha = 0.35f)
                    else -> cs.onSurface.copy(alpha = 0.55f)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { r.seek(i); if (!r.isActive) r.togglePlayPause() }
                    .padding(vertical = 4.dp, horizontal = 4.dp)
            )
        }
    }
}

@Composable
private fun SpeedSheet(state: AppState, onClose: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val tick = state.readerTick
    val r = state.reader
    var value by remember { mutableFloatStateOf(r.rate) }
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("Tempo czytania", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(fmtRate(value), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
            Slider(
                value = value,
                onValueChange = { value = (Math.round(it * 20f) / 20f) },
                onValueChangeFinished = { r.setRate(value) },
                valueRange = 0.5f..2.5f
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (p in listOf(0.8f, 1f, 1.25f, 1.5f, 2f)) {
                    FilterChip(
                        selected = kotlin.math.abs(value - p) < 0.01f,
                        onClick = { value = p; r.setRate(p) },
                        label = { Text(fmtRate(p)) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SleepSheet(state: AppState, onClose: () -> Unit) {
    val r = state.reader
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text("Timer snu", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            val active = r.sleepRemainingSeconds() > 0
            val options = listOf(
                0 to "Wyłączony", 5 to "5 minut", 15 to "15 minut", 30 to "30 minut",
                45 to "45 minut", 60 to "1 godzina", -1 to "Do końca artykułu"
            )
            for ((min, label) in options) {
                val sel = when (min) {
                    0 -> !active && !r.isSleepEndOfArticle
                    -1 -> r.isSleepEndOfArticle
                    else -> false
                }
                ListItem(
                    headlineContent = { Text(label) },
                    leadingContent = { RadioButton(selected = sel, onClick = null) },
                    modifier = Modifier.clickable {
                        r.setSleepTimer(min)
                        if (min != 0) state.message = if (min > 0) "Czytanie zatrzyma się za $min min" else "Zatrzymam po tym artykule"
                        onClose()
                    }
                )
            }
        }
    }
}
