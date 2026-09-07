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
    private val barcodes by lazy { BarcodeSettings(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityIntegrationBinding.inflate(layoutInflater)
        setContentView(ui.root)

        ui.httpPort.setText(httpPort.toString())
        ui.scalePort.setText(scalePort.toString())
        ui.bcPrefix.setText(barcodes.prefix)
        ui.bcPlu.setText(barcodes.pluDigits.toString())
        ui.bcWeight.setText(barcodes.weightDigits.toString())

        // Пересобираем пример на каждое изменение: сразу видно, сходится ли разметка
        listOf(ui.bcPrefix, ui.bcPlu, ui.bcWeight).forEach { field ->
            field.addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    saveBarcode(); showBarcode()
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            })
        }

        ui.toggle.setOnClickListener { if (Servers.running) stop() else start() }
        ui.copy.setOnClickListener { copySettings() }
        showJournal()
        showBarcode()
        refresh()
    }

    private val httpPort get() = prefs.getInt("http", 8090)
    private val scalePort get() = prefs.getInt("scale", 5001)

    private fun savePorts() {
        prefs.edit()
            .putInt("http", ui.httpPort.text.toString().toIntOrNull() ?: 8090)
            .putInt("scale", ui.scalePort.text.toString().toIntOrNull() ?: 5001)
            .apply()
    }

    private fun start() {
        savePorts()
        Servers.start(this) { runOnUiThread { showJournal(); refresh() } }
        showJournal()
        refresh()
    }

    private fun stop() {
        Servers.stop(this)
        refresh()
    }

    private fun showJournal() {
        ui.log.text = Servers.journal.joinToString("\n")
    }

    private fun saveBarcode() {
        barcodes.prefix = ui.bcPrefix.text.toString()
        barcodes.pluDigits = ui.bcPlu.text.toString().toIntOrNull() ?: 5
        barcodes.weightDigits = ui.bcWeight.text.toString().toIntOrNull() ?: 6
    }

    private fun showBarcode() {
        ui.bcPreview.text = if (barcodes.valid) {
            getString(R.string.bc_ok, barcodes.template, barcodes.sample())
        } else {
            getString(
                R.string.bc_bad,
                barcodes.prefix.length + barcodes.pluDigits + barcodes.weightDigits
            )
        }
    }

    private fun refresh() {
        val addresses = localAddresses()
        val wifi = addresses.firstOrNull { it.local }
        val ip = wifi?.ip ?: "—"
        val on = Servers.running

        ui.toggle.setText(if (on) R.string.srv_stop else R.string.srv_start)
        ui.state.text = getString(if (on) R.string.srv_on else R.string.srv_off)

        ui.address.text = buildString {
            if (wifi == null) {
                // Мобильный интернет не годится: касса до такого адреса не достучится
                append(getString(R.string.srv_no_wifi))
            } else {
                append(getString(R.string.srv_address, wifi.ip))
            }
            val others = addresses.filterNot { it == wifi }
            if (others.isNotEmpty()) {
                append("\n\n")
                append(getString(R.string.srv_other_ifaces))
                others.forEach { append("\n  ${it.iface}: ${it.ip}") }
            }
        }

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

/**
 * Один экземпляр серверов на всё приложение.
 *
 * Они переживают смену экрана и поднимаются сами при следующем запуске,
 * если их однажды включили: иначе после перезагрузки телефона касса
 * молча перестала бы получать данные.
 */
object Servers {
    private var server: LocalServer? = null
    private val recent = ArrayList<String>()

    val running get() = server?.running == true

    /** Последние строки журнала — чтобы они не пропадали при уходе с экрана. */
    val journal: List<String> get() = recent.toList()

    private fun prefs(context: Context) =
        context.getSharedPreferences("server", Context.MODE_PRIVATE)

    fun httpPort(context: Context) = prefs(context).getInt("http", 8090)
    fun scalePort(context: Context) = prefs(context).getInt("scale", 5001)

    fun start(context: Context, log: (String) -> Unit = {}) {
        stop()
        prefs(context).edit().putBoolean("enabled", true).apply()
        val http = httpPort(context)
        val scale = scalePort(context)
        server = LocalServer(http, scale, BarcodeSettings(context)) { line ->
            synchronized(recent) {
                recent.add(0, line)
                while (recent.size > 40) recent.removeAt(recent.size - 1)
            }
            log(line)
        }.also { it.start() }
    }

    fun stop(context: Context? = null) {
        context?.prefsDisable()
        server?.stop()
        server = null
    }

    private fun Context.prefsDisable() =
        prefs(this).edit().putBoolean("enabled", false).apply()

    /** Поднять сервера при запуске приложения, если их включали раньше. */
    fun restoreIfEnabled(context: Context) {
        if (running) return
        if (prefs(context).getBoolean("enabled", false)) start(context)
    }
}
