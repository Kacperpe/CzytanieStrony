@file:OptIn(ExperimentalMaterial3Api::class)

package pl.local.czytnikstrony

import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun CzytnikApp(state: AppState, webView: WebView) {
    @Suppress("UNUSED_VARIABLE") val tick = state.readerTick
    val reader = state.reader
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        val m = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(m)
        state.message = null
    }
    BackHandler(enabled = state.playerOpen) { state.playerOpen = false }

    // Raport ostatniego crasha (do przesłania autorowi)
    var crash by remember { mutableStateOf(CrashLog.read(state.activity)) }
    crash?.let { report ->
        AlertDialog(
            onDismissRequest = { CrashLog.clear(state.activity); crash = null },
            title = { Text("Aplikacja się zamknęła") },
            text = {
                Column {
                    Text("Skopiuj raport i wyślij go, żeby dało się naprawić błąd.",
                        style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                    Text(report, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val cb = state.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    cb.setPrimaryClip(android.content.ClipData.newPlainText("crash", report))
                    CrashLog.clear(state.activity)
                    crash = null
                    state.message = "Raport skopiowany"
                }) { Text("Kopiuj raport") }
            },
            dismissButton = {
                TextButton(onClick = { CrashLog.clear(state.activity); crash = null }) { Text("Zamknij") }
            }
        )
    }

    Box(Modifier.fillMaxSize()) {
        // Ukryty WebView do wyciągania artykułów
        AndroidView(factory = { webView }, modifier = Modifier.requiredSize(360.dp, 2.dp).alpha(0f))

        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                Column {
                    if (reader.hasContent()) MiniPlayer(state)
                    NavigationBar {
                        val items = listOf(
                            Triple("Start", Icons.Rounded.Home, 0),
                            Triple("Biblioteka", Icons.Rounded.LibraryBooks, 1),
                            Triple("Głosy", Icons.Rounded.GraphicEq, 2),
                        )
                        for ((label, icon, i) in items) {
                            NavigationBarItem(
                                selected = state.tab == i,
                                onClick = { state.tab = i },
                                icon = { Icon(icon, contentDescription = label) },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad)) {
                when (state.tab) {
                    0 -> HomeScreen(state)
                    1 -> LibraryScreen(state)
                    else -> VoicesScreen(state)
                }
            }
        }

        AnimatedVisibility(
            visible = state.playerOpen,
            enter = slideInVertically(tween(320)) { it } + fadeIn(),
            exit = slideOutVertically(tween(260)) { it } + fadeOut()
        ) {
            PlayerScreen(state)
        }
    }
}

/** Jednowierszowy odtwarzacz nad nawigacją; stuknięcie otwiera pełny ekran. */
@Composable
fun MiniPlayer(state: AppState) {
    @Suppress("UNUSED_VARIABLE") val tick = state.readerTick
    val r = state.reader
    val haptic = LocalHapticFeedback.current
    val dur = r.durationSeconds().coerceAtLeast(1)
    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable { state.playerOpen = true },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column {
            Row(Modifier.padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                CoverArt(r.domain, r.displayTitle(), 44.dp, Modifier.size(44.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.displayTitle(), style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = when {
                        r.isLoading -> r.status
                        r.domain.isNotEmpty() -> r.domain
                        else -> r.currentChunkPreview()
                    }
                    Text(sub, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (r.isLoading) {
                    CircularProgressIndicator(Modifier.size(22.dp).padding(2.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                }
                IconButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    r.togglePlayPause()
                }) {
                    Icon(if (r.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (r.isPlaying) "Pauza" else "Czytaj",
                        modifier = Modifier.size(30.dp))
                }
                IconButton(onClick = { r.next() }) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "Następne zdanie")
                }
            }
            LinearProgressIndicator(
                progress = { r.positionSeconds() / dur.toFloat() },
                modifier = Modifier.fillMaxWidth().height(2.dp).padding(horizontal = 12.dp),
                drawStopIndicator = {}
            )
            Spacer(Modifier.height(4.dp))
        }
    }
}
