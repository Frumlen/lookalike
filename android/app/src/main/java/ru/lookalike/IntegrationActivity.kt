package ru.lookalike

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import ru.lookalike.databinding.ActivityIntegrationBinding

/**
 * Раздел «Для 1С»: приложение работает как устройство, у которого 1С
 * забирает вес и штрихкод. Здесь показаны адрес и порты, которые надо
 * вписать в настройках 1С.
 */
class IntegrationActivity : AppCompatActivity() {

    private lateinit var ui: ActivityIntegrationBinding
    private val prefs by lazy { getSharedPreferences("server", Context.MODE_PRIVATE) }
    private val lines = ArrayList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityIntegrationBinding.inflate(layoutInflater)
        setContentView(ui.root)

        ui.httpPort.setText(httpPort.toString())
        ui.scalePort.setText(scalePort.toString())

        ui.toggle.setOnClickListener { if (Servers.running) stop() else start() }
        ui.copy.setOnClickListener { copySettings() }
        refresh()
    }

    private val httpPort get() = prefs.getInt("http", 8099)
    private val scalePort get() = prefs.getInt("scale", 5001)

    private fun savePorts() {
        prefs.edit()
            .putInt("http", ui.httpPort.text.toString().toIntOrNull() ?: 8099)
            .putInt("scale", ui.scalePort.text.toString().toIntOrNull() ?: 5001)
            .apply()
    }

    private fun start() {
        savePorts()
        lines.clear()
        Servers.start(httpPort, scalePort) { line ->
            runOnUiThread {
                lines.add(0, line)
                while (lines.size > 30) lines.removeAt(lines.size - 1)
                ui.log.text = lines.joinToString("\n")
                refresh()
            }
        }
        refresh()
    }

    private fun stop() {
        Servers.stop()
        refresh()
    }

    private fun refresh() {
        val ip = localIp().ifBlank { "—" }
        val on = Servers.running
        ui.toggle.setText(if (on) R.string.srv_stop else R.string.srv_start)
        ui.state.text = getString(if (on) R.string.srv_on else R.string.srv_off)
        ui.address.text = getString(R.string.srv_address, ip)
        ui.hint.text = getString(R.string.srv_urls, ip, httpPort, ip, scalePort)
        ui.httpPort.isEnabled = !on
        ui.scalePort.isEnabled = !on
    }

    /** Готовый текст для того, кто настраивает 1С. */
    private fun copySettings() {
        val ip = localIp().ifBlank { "—" }
        val text = getString(R.string.srv_share, ip, httpPort, ip, httpPort, ip, scalePort)
        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clip.setPrimaryClip(android.content.ClipData.newPlainText("lookalike", text))
        Toast.makeText(this, R.string.srv_copied, Toast.LENGTH_SHORT).show()
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                },
                getString(R.string.srv_send)
            )
        )
    }
}

/** Один экземпляр серверов на всё приложение: они должны пережить смену экрана. */
object Servers {
    private var server: LocalServer? = null

    val running get() = server?.running == true

    fun start(httpPort: Int, scalePort: Int, log: (String) -> Unit) {
        stop()
        server = LocalServer(httpPort, scalePort, log).also { it.start() }
    }

    fun stop() {
        server?.stop()
        server = null
    }
}
