package io.github.romanvht.byedpi.autotune

import android.content.Context
import android.net.VpnService
import androidx.core.content.edit
import io.github.romanvht.byedpi.data.Mode
import io.github.romanvht.byedpi.data.StrategyResult
import io.github.romanvht.byedpi.services.NativeEngine
import io.github.romanvht.byedpi.services.ServiceManager
import io.github.romanvht.byedpi.services.TestService
import io.github.romanvht.byedpi.utility.getCmdArgs
import io.github.romanvht.byedpi.utility.getPreferences
import io.github.romanvht.byedpi.utility.getStringNotNull
import io.github.romanvht.byedpi.utility.mode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object AutoTuner {
    enum class Outcome { SWITCHED, KEPT, NO_GOOD, FAILED, BUSY }

    private val running = AtomicBoolean(false)
    val isBusy: Boolean get() = running.get()

    private const val MIN_GOOD_PERCENT = 40

    /** Быстрый подбор; если ничего хорошего не нашлось — автоматически полный перебор. */
    suspend fun run(context: Context, full: Boolean): Outcome {
        var r = tune(context, full)
        if (r == Outcome.NO_GOOD && !full) r = tune(context, true)
        return r
    }

    private suspend fun tune(context: Context, full: Boolean): Outcome {
        if (!running.compareAndSet(false, true)) return Outcome.BUSY
        val app = context.applicationContext
        val prefs = app.getPreferences()
        val mode = prefs.mode()
        val wasRunning = NativeEngine.running(app) != null
        var outcome = Outcome.FAILED
        try {
            StrategySources.refresh(app)

            val current = prefs.getCmdArgs()
            val sni = prefs.getStringNotNull("byedpi_proxytest_sni", "google.com")
            val raw = StrategySources.candidates(app, current, full)
            val commands = raw.map { it.replace("{sni}", "\"$sni\"") }.distinct()

            prefs.edit(commit = true) {
                putBoolean("byedpi_proxytest_usercommands", true)
                putString("byedpi_proxytest_commands", raw.joinToString("\n"))
            }

            withContext(Dispatchers.Main) { TestService.start(app) }
            val finished = withTimeoutOrNull(30 * 60_000L) { TestService.awaitStopped(); true }
            if (finished == null) {
                TestService.stop()
                TestService.awaitStopped()
            }

            val wanted = commands.toSet()
            val results = TestService.state.value.strategies
                .filter { it.isCompleted && it.totalRequests > 0 && it.command in wanted }
            if (results.isEmpty()) {
                status(app, "Тест не удался (нет результатов)")
                return Outcome.FAILED
            }

            val ranked = results.sortedWith(
                compareByDescending<StrategyResult> { it.successPercentage }.thenBy { it.elapsedMs }
            )
            StrategySources.saveTop(app, ranked.take(8).map { it.command })

            val best = ranked.first()
            val cur = results.firstOrNull { it.command == current }

            var dnsNote = ""
            val newDns = DnsPicker.pick(prefs.getStringNotNull("dns_ip", "1.1.1.1"))
            if (newDns != null) {
                prefs.edit(commit = true) { putString("dns_ip", newDns) }
                dnsNote = ", DNS → $newDns"
            }

            if (best.successPercentage < MIN_GOOD_PERCENT) {
                status(app, "Ничего подходящего (лучшее ${best.successPercentage}%)$dnsNote")
                outcome = Outcome.NO_GOOD
            } else if (cur != null && cur.successPercentage >= best.successPercentage &&
                cur.elapsedMs <= best.elapsedMs * 115 / 100
            ) {
                status(app, "Текущая стратегия остаётся лучшей (${cur.successPercentage}%)$dnsNote")
                prefs.edit { putLong(AutoKeys.LAST_TUNE, System.currentTimeMillis()) }
                outcome = Outcome.KEPT
            } else {
                prefs.edit(commit = true) {
                    putString("byedpi_cmd_args", best.command)
                    putBoolean("byedpi_enable_cmd_settings", true)
                    putLong(AutoKeys.LAST_TUNE, System.currentTimeMillis())
                }
                status(app, "Выбрана новая стратегия: ${best.successPercentage}%, ${best.elapsedMs / 1000} с$dnsNote")
                outcome = Outcome.SWITCHED
            }
            if (outcome == Outcome.NO_GOOD) {
                prefs.edit { putLong(AutoKeys.NEXT_TRY, System.currentTimeMillis() + 30 * 60_000L) }
            }
            return outcome
        } catch (e: Exception) {
            status(app, "Ошибка подбора: ${e.message}")
            return Outcome.FAILED
        } finally {
            val keepOn = prefs.getBoolean(AutoKeys.KEEP_ON, true)
            if ((wasRunning || keepOn) && (mode != Mode.VPN || VpnService.prepare(app) == null)) {
                try {
                    ServiceManager.restart(app, mode)
                } catch (_: Exception) {
                }
            }
            running.set(false)
        }
    }

    private fun status(context: Context, text: String) {
        val time = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date())
        context.getPreferences().edit { putString(AutoKeys.LAST_STATUS, "$time — $text") }
    }
}
