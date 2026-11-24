package io.github.romanvht.byedpi.autotune

import android.content.Context
import io.github.romanvht.byedpi.services.NativeEngine
import io.github.romanvht.byedpi.utility.DomainListUtils
import io.github.romanvht.byedpi.utility.SiteCheckUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object HealthCheck {
    /** Проверка через уже работающий локальный SOCKS-прокси (ciadpi). */
    suspend fun ok(context: Context): Boolean = withContext(Dispatchers.IO) {
        val cfg = NativeEngine.configuration(context)
        val host = when (cfg.host) {
            "0.0.0.0" -> "127.0.0.1"
            "::", "[::]" -> "::1"
            else -> cfg.host
        }
        DomainListUtils.syncLists(context)
        val sites = DomainListUtils.getActiveDomains(context).shuffled().take(8)
        if (sites.isEmpty()) return@withContext true
        val res = SiteCheckUtils(host, cfg.port).checkSitesAsync(
            sites = sites, requestsCount = 1, requestTimeout = 6,
            concurrentRequests = 8, fullLog = false,
        )
        res.count { it.second > 0 } * 100 / res.size >= 60
    }

    /** Есть ли вообще интернет (прямые запросы к российским сайтам; приложение исключено из VPN). */
    suspend fun hasInternet(): Boolean = withContext(Dispatchers.IO) {
        listOf("https://ya.ru", "https://vk.com").any { u ->
            try {
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 5000
                c.readTimeout = 5000
                try { c.responseCode > 0 } finally { c.disconnect() }
            } catch (_: Exception) {
                false
            }
        }
    }
}
