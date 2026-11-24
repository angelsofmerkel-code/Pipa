package io.github.romanvht.byedpi.autotune

import android.content.Intent
import android.graphics.Typeface
import android.net.VpnService
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.romanvht.byedpi.activities.MainActivity
import io.github.romanvht.byedpi.data.AppStatus
import io.github.romanvht.byedpi.data.Mode
import io.github.romanvht.byedpi.data.START_ACTION
import io.github.romanvht.byedpi.data.STOP_ACTION
import io.github.romanvht.byedpi.receiver.ServiceActionReceiver
import io.github.romanvht.byedpi.services.ServiceManager
import io.github.romanvht.byedpi.services.TestService
import io.github.romanvht.byedpi.services.appStatus
import io.github.romanvht.byedpi.utility.getCmdArgs
import io.github.romanvht.byedpi.utility.getPreferences
import io.github.romanvht.byedpi.utility.getStringNotNull
import io.github.romanvht.byedpi.utility.mode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Главный экран для ТВ: крупные кнопки, управление пультом (D-pad). */
class AutoActivity : AppCompatActivity() {
    private lateinit var info: TextView
    private lateinit var toggleBtn: Button
    private lateinit var autoBtn: Button

    private val vpnLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) sendAction(START_ACTION, getPreferences().mode())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ServiceManager.refresh(this)
        val prefs = getPreferences()
        if (!prefs.getBoolean(AutoKeys.INIT, false)) {
            prefs.edit(commit = true) {
                putBoolean(AutoKeys.INIT, true)
                putBoolean(AutoKeys.ENABLED, true)
                putBoolean(AutoKeys.KEEP_ON, true)
                putBoolean("autostart", true)
                putBoolean("byedpi_enable_cmd_settings", true)
            }
        }
        if (prefs.getBoolean(AutoKeys.ENABLED, true)) AutoScheduler.schedule(this)

        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad * 2, pad, pad * 2, pad)
        }
        val title = TextView(this).apply {
            text = "Трахнем ПЫПУ"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
        }
        info = TextView(this).apply { textSize = 18f; setPadding(0, pad / 2, 0, pad / 2) }
        toggleBtn = button { toggle() }
        autoBtn = button { toggleAuto() }
        val quick = button("Подобрать стратегию сейчас (быстро)") { startTune(false) }
        val full = button("Полный перебор всех стратегий") { startTune(true) }
        val adv = button("Расширенные настройки") { startActivity(Intent(this, MainActivity::class.java)) }

        listOf(title, info, toggleBtn, quick, full, autoBtn, adv).forEach { root.addView(it) }
        setContentView(ScrollView(this).apply { addView(root) })
        toggleBtn.requestFocus()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    render()
                    delay(1000)
                }
            }
        }
    }

    private fun button(label: String = "", onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 20f
        setAllCaps(false)
        isFocusable = true
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .also { it.topMargin = 12 }
        setOnClickListener { onClick() }
    }

    private fun running() = appStatus.first == AppStatus.Running

    private fun render() {
        val prefs = getPreferences()
        val t = TestService.state.value
        val sb = StringBuilder()
        sb.appendLine(if (running()) "● Обход ВКЛЮЧЁН" else "○ Обход выключен")
        if (t.isRunning || AutoTuner.isBusy) {
            sb.appendLine("⏳ Идёт подбор стратегии: ${t.currentStrategy} из ${t.strategies.size} (обход на время теста отключается)")
        }
        sb.appendLine("Стратегия: ${prefs.getCmdArgs()}")
        sb.appendLine("DNS: ${prefs.getStringNotNull("dns_ip", "1.1.1.1")}")
        sb.appendLine("Последний подбор: ${prefs.getString(AutoKeys.LAST_STATUS, "ещё не было")}")
        info.text = sb.toString()
        toggleBtn.text = if (running()) "Выключить обход" else "Включить обход"
        autoBtn.text = "Автоподбор: " + if (prefs.getBoolean(AutoKeys.ENABLED, true)) "ВКЛЮЧЁН" else "выключен"
    }

    private fun toggle() {
        val prefs = getPreferences()
        if (running()) {
            prefs.edit { putBoolean(AutoKeys.KEEP_ON, false) }
            sendAction(STOP_ACTION, appStatus.second)
        } else {
            prefs.edit { putBoolean(AutoKeys.KEEP_ON, true) }
            val mode = prefs.mode()
            if (mode == Mode.VPN) {
                val intent = VpnService.prepare(this)
                if (intent != null) {
                    vpnLauncher.launch(intent)
                    return
                }
            }
            sendAction(START_ACTION, mode)
        }
    }

    private fun toggleAuto() {
        val prefs = getPreferences()
        val on = !prefs.getBoolean(AutoKeys.ENABLED, true)
        prefs.edit { putBoolean(AutoKeys.ENABLED, on) }
        if (on) AutoScheduler.schedule(this) else AutoScheduler.cancel(this)
    }

    private fun startTune(full: Boolean) {
        if (TestService.isRunning || AutoTuner.isBusy) {
            Toast.makeText(this, "Подбор уже идёт", Toast.LENGTH_SHORT).show()
            return
        }
        AutoScheduler.tuneNow(this, full)
        Toast.makeText(this, "Подбор запущен. Это займёт несколько минут", Toast.LENGTH_LONG).show()
    }

    private fun sendAction(action: String, mode: Mode) {
        sendBroadcast(
            Intent(this, ServiceActionReceiver::class.java)
                .setAction(action)
                .putExtra(ServiceActionReceiver.EXTRA_MODE, mode.name)
        )
    }
}
