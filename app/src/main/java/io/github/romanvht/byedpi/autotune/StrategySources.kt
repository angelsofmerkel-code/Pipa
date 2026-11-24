package io.github.romanvht.byedpi.autotune

import android.content.Context
import androidx.core.content.edit
import io.github.romanvht.byedpi.BuildConfig
import io.github.romanvht.byedpi.utility.getPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Источники стратегий: встроенный список + список мейнтейнера ByeByeDPI + свой список из вашего репозитория.
 * Каждая строка проверяется: ciadpi — это только SOCKS-прокси, но мы всё равно не даём удалённому списку
 * менять адрес/порт прослушивания и прочие служебные флаги.
 */
object StrategySources {
    private const val CACHE_FILE = "remote_strategies.txt"
    private const val TOP_FILE = "auto_top.txt"
    private const val UPSTREAM_PATH = "app/src/main/assets/proxytest_strategies.list"
    private const val UPSTREAM_REPO = "romanvht/ByeByeDPI"
    private const val UPSTREAM_BRANCH = "main"
    private const val OWN_PATH = "strategies/custom.list"

    private val TOKEN = Regex("^[-A-Za-z0-9:+,._=\"{}]+$")
    private val DENIED_SHORT = setOf('i', 'p', 'I', 'w', 'D', 'E', 'x')
    private val DENIED_LONG = setOf("--ip", "--port", "--conn-ip", "--pidfile", "--daemon", "--transparent", "--debug")

    fun isValid(line: String): Boolean {
        if (line.length > 300) return false
        val tokens = line.trim().split(Regex("\\s+"))
        if (tokens.isEmpty() || tokens.size > 60) return false
        if (!tokens[0].startsWith("-")) return false
        for (t in tokens) {
            if (!TOKEN.matches(t)) return false
            if (t.startsWith("--")) {
                if (t.substringBefore('=') in DENIED_LONG) return false
            } else if (t.length >= 2 && t[0] == '-' && t[1] in DENIED_SHORT) {
                return false
            }
        }
        return true
    }

    fun parse(text: String): List<String> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && isValid(it) }
            .distinct()
            .toList()

    private fun mirrors(repo: String, branch: String, path: String) = listOf(
        "https://raw.githubusercontent.com/$repo/$branch/$path",
        "https://cdn.jsdelivr.net/gh/$repo@$branch/$path",
    )

    private fun download(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "AutoDpiTv")
        try {
            if (c.responseCode != 200) null
            else c.inputStream.bufferedReader().use { it.readText() }.takeIf { it.length < 300_000 }
        } finally {
            c.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    /** true — список изменился, false — без изменений, null — сеть/источники недоступны. */
    suspend fun refresh(context: Context): Boolean? = withContext(Dispatchers.IO) {
        val sources = mutableListOf(mirrors(UPSTREAM_REPO, UPSTREAM_BRANCH, UPSTREAM_PATH))
        if (BuildConfig.STRATEGIES_REPO.isNotBlank()) {
            sources += mirrors(BuildConfig.STRATEGIES_REPO, BuildConfig.STRATEGIES_BRANCH, OWN_PATH)
        }
        val all = linkedSetOf<String>()
        var any = false
        for (urls in sources) {
            for (u in urls) {
                val text = download(u) ?: continue
                all += parse(text)
                any = true
                break
            }
        }
        if (!any) return@withContext null
        val file = File(context.filesDir, CACHE_FILE)
        val old = if (file.exists()) file.readText() else ""
        val new = all.joinToString("\n")
        file.writeText(new)
        context.getPreferences().edit { putLong(AutoKeys.LAST_FETCH, System.currentTimeMillis()) }
        old != new
    }

    private fun builtin(context: Context): List<String> =
        parse(context.assets.open("proxytest_strategies.list").bufferedReader().use { it.readText() })

    private fun remote(context: Context): List<String> {
        val f = File(context.filesDir, CACHE_FILE)
        return if (f.exists()) parse(f.readText()) else emptyList()
    }

    fun readTop(context: Context): List<String> {
        val f = File(context.filesDir, TOP_FILE)
        return if (f.exists()) f.readLines().map { it.trim() }.filter { it.isNotEmpty() } else emptyList()
    }

    fun saveTop(context: Context, commands: List<String>) {
        File(context.filesDir, TOP_FILE).writeText(commands.joinToString("\n"))
    }

    /** full — всё; иначе текущая + прошлые лучшие + новые из удалённых списков. */
    fun candidates(context: Context, current: String, full: Boolean): List<String> {
        val builtin = builtin(context)
        val remote = remote(context)
        val everything = (remote + builtin).distinct()
        val top = readTop(context)
        val list = if (full || top.isEmpty()) everything
        else (top + remote.filter { it !in builtin }).distinct()
        return (listOf(current).filter { it.isNotBlank() } + list).distinct()
    }
}
