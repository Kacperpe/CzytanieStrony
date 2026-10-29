package pl.local.czytnikstrony

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp

private val LightScheme = lightColorScheme(
    primary = Color(0xFF5B4AE8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E1FF),
    onPrimaryContainer = Color(0xFF170A6B),
    secondaryContainer = Color(0xFFE5E1F7),
    background = Color(0xFFFBF8FF),
    surface = Color(0xFFFBF8FF),
    surfaceContainer = Color(0xFFF0ECFA),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFC6BFFF),
    onPrimary = Color(0xFF2A1A9C),
    primaryContainer = Color(0xFF4232C4),
    onPrimaryContainer = Color(0xFFE6E1FF),
    background = Color(0xFF121218),
    surface = Color(0xFF121218),
    surfaceContainer = Color(0xFF1F1F27),
)

@Composable
fun CzytnikTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Kolory okładki jako Compose Color. */
fun coverColors(domain: String?, title: String?): List<Color> {
    val key = if (!domain.isNullOrEmpty()) domain else title
    val c = Cover.colors(key)
    return listOf(Color(c[0]), Color(c[1]))
}

/** Okładka artykułu: gradient + litera domeny. */
@Composable
fun CoverArt(domain: String?, title: String?, size: Dp, modifier: Modifier = Modifier) {
    val colors = coverColors(domain, title)
    Box(
        modifier
            .clip(RoundedCornerShape(size * 0.2f))
            .background(Brush.linearGradient(colors)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            Cover.letter(domain, title),
            color = Color.White.copy(alpha = 0.95f),
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.42f).sp
        )
    }
}

fun fmtTime(sec: Int): String {
    val s = sec.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val r = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%d:%02d".format(m, r)
}

fun fmtRate(r: Float): String {
    val s = "%.2f".format(java.util.Locale.US, r).trimEnd('0').trimEnd('.')
    return "$s×"
}
