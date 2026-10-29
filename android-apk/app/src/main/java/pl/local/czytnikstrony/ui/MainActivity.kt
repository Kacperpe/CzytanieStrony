package pl.local.czytnikstrony

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.util.Locale

/** Stan aplikacji widoczny dla Compose. Liczniki *Tick wymuszają odświeżenie po zmianach w Javie. */
class AppState(val activity: MainActivity) : Reader.StateListener, Library.Listener, VoiceCatalog.DownloadListener {

    val reader: Reader = Reader.get(activity)
    val library: Library = Library.get(activity)

    var readerTick by mutableIntStateOf(0)
    var libraryTick by mutableIntStateOf(0)
    var voiceTick by mutableIntStateOf(0)

    var tab by mutableIntStateOf(0)
    var playerOpen by mutableStateOf(false)
    var input by mutableStateOf("")
    var extractingUrl by mutableStateOf<String?>(null)
    var extractProgress by mutableIntStateOf(0)
    var message by mutableStateOf<String?>(null)
    var updateVersion by mutableStateOf<String?>(null)
    var updateUrl: String = ""
    var voicesLang by mutableStateOf("pl")

    lateinit var extractor: ArticleExtractor

    fun register() {
        reader.addListener(this)
        library.addListener(this)
        VoiceCatalog.addListener(this)
        reader.systemEngine.whenReady { voiceTick++ }
        onReaderState()
    }

    fun unregister() {
        reader.removeListener(this)
        library.removeListener(this)
        VoiceCatalog.removeListener(this)
    }

    override fun onReaderState() { readerTick++ }
    override fun onLibraryChanged() { libraryTick++ }
    override fun onProgress(id: String, done: Long, total: Long) { voiceTick++ }
    override fun onDone(id: String, error: String?) {
        voiceTick++
        val m = VoiceCatalog.find(activity, id)
        if (error != null) {
            if (error != "Anulowano") message = "Nie udało się pobrać głosu: $error"
        } else if (m != null) {
            message = "Pobrano głos ${m.name}"
            if (!reader.getVoice(m.lang).startsWith(Reader.VOICE_NEURAL_PREFIX)) {
                reader.setVoice(m.lang, Reader.VOICE_NEURAL_PREFIX + m.id)
            }
        }
    }

    // ── Akcje ────────────────────────────────────────────────────────────

    /** Link → wczytaj stronę; tekst → czytaj od razu. */
    fun openInput(value: String) {
        val v = value.trim()
        if (v.isEmpty()) { message = "Wklej link albo tekst"; return }
        val url = ArticleExtractor.extractUrl(v)
        if (url != null) openUrl(url) else reader.play(v, "", "", 0)
        input = ""
    }

    fun queueInput(value: String) {
        val v = value.trim()
        if (v.isEmpty()) return
        val url = ArticleExtractor.extractUrl(v)
        if (url != null) library.upsert("", url, null, true) else library.upsert("", "", Reader.cleanText(v), true)
        input = ""
        message = "Dodano do kolejki"
    }

    fun openUrl(raw: String) {
        val url = ArticleExtractor.normalizeUrl(raw) ?: return
        extractingUrl = url
        extractProgress = 0
        extractor.extract(url, object : ArticleExtractor.Callback {
            override fun onProgress(percent: Int) { extractProgress = percent }
            override fun onResult(title: String, text: String, errorOrNull: String?) {
                extractingUrl = null
                if (errorOrNull != null) { message = errorOrNull; return }
                reader.play(text, title, url, 0)
            }
        })
    }

    fun cancelExtraction() {
        extractor.cancel()
        extractingUrl = null
    }

    fun openArticle(a: Library.Article) {
        if (reader.playArticle(a.id)) return
        if (a.url.isNotEmpty()) openUrl(a.url) else message = "Brak treści tego artykułu"
    }

    fun pasteFromClipboard(): String {
        val cb = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return ""
        val clip = cb.primaryClip ?: return ""
        if (clip.itemCount == 0) return ""
        return clip.getItemAt(0).coerceToText(activity)?.toString()?.trim() ?: ""
    }

    fun startImport() {
        if (!VoiceCatalog.hasAnyEspeakData(activity)) {
            message = "Najpierw pobierz dowolny głos z listy (zawiera pliki wymowy)"
            return
        }
        activity.importLauncher.launch(arrayOf("*/*"))
    }

    fun openSystemTtsSettings() {
        try { activity.startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
        catch (e: Exception) { message = "Otwórz Ustawienia → Zamiana tekstu na mowę" }
    }
}

class MainActivity : ComponentActivity() {

    lateinit var state: AppState
    private lateinit var webView: WebView

    val importLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        var onnx: Uri? = null
        var json: Uri? = null
        for (u in uris) {
            val n = VoiceCatalog.displayName(this, u).lowercase(Locale.ROOT)
            if (n.endsWith(".json")) json = u else if (n.endsWith(".onnx")) onnx = u
        }
        if (onnx == null || json == null) {
            state.message = "Zaznacz oba pliki naraz: .onnx i .onnx.json"
            return@registerForActivityResult
        }
        state.message = "Importuję głos…"
        VoiceCatalog.importCustom(this, onnx, json) { id, err ->
            if (err != null) { state.message = "Import nieudany: $err"; return@importCustom }
            val m = VoiceCatalog.find(this, id)
            if (m != null) {
                state.reader.setVoice(m.lang, Reader.VOICE_NEURAL_PREFIX + m.id)
                state.voicesLang = m.lang
                state.message = "Dodano głos ${m.name}"
            }
            state.voiceTick++
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        state = AppState(this)
        webView = WebView(this).apply { visibility = View.INVISIBLE }
        state.extractor = ArticleExtractor(webView)
        state.register()

        setContent { CzytnikTheme { CzytnikApp(state, webView) } }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        handleIntent(intent)
        checkForUpdate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        state.unregister()
        state.extractor.cancel()
        webView.destroy()
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val shared: CharSequence? = when (intent.action) {
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            else -> null
        }
        if (intent.getBooleanExtra("open_player", false)) state.playerOpen = true
        if (shared == null) return
        intent.action = null
        val value = shared.toString().trim()
        val url = ArticleExtractor.extractUrl(value)
        if (url != null) state.openUrl(url)
        else state.reader.play(value, intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: "", "", 0)
        state.tab = 0
    }

    private fun checkForUpdate() {
        Thread {
            try {
                val conn = java.net.URL("https://api.github.com/repos/Kacperpe/CzytanieStrony/releases/latest")
                    .openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 6000
                conn.readTimeout = 6000
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                if (conn.responseCode != 200) return@Thread
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val json = JSONObject(body)
                val latest = json.optString("tag_name").replace(Regex("[^0-9.]"), "")
                val current = packageManager.getPackageInfo(packageName, 0).versionName ?: "0"
                if (latest.isNotEmpty() && isNewer(latest, current)) {
                    runOnUiThread {
                        state.updateUrl = json.optString("html_url")
                        state.updateVersion = latest
                    }
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun isNewer(latest: String, current: String): Boolean {
        val l = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val c = current.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(l.size, c.size)) {
            val a = l.getOrElse(i) { 0 }
            val b = c.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    fun openUpdatePage() {
        if (state.updateUrl.isNotEmpty()) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.updateUrl)))
    }
}
