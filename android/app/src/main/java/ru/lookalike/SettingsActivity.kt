package ru.lookalike

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import ru.lookalike.databinding.ActivitySettingsBinding

/**
 * Одна дверь во все настройки. Раньше связь с весами и связь с кассой
 * жили отдельными кнопками на рабочем экране, и было непонятно,
 * что где искать.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var ui: ActivitySettingsBinding
    private val scale by lazy { ScaleSettings(this) }
    private val barcodes by lazy { BarcodeSettings(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(ui.root)

        ui.scaleRow.setOnClickListener { startActivity(Intent(this, ScaleActivity::class.java)) }
        ui.serverRow.setOnClickListener {
            startActivity(Intent(this, IntegrationActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // Состояние сразу на виду: не надо заходить внутрь, чтобы понять,
        // настроено ли и работает ли
        ui.scaleState.text = if (scale.configured) {
            getString(R.string.set_scale_on, scale.host, scale.port)
        } else {
            getString(R.string.set_scale_off)
        }

        ui.serverState.text = buildString {
            append(
                if (Servers.running) getString(R.string.set_server_on, localIp().ifBlank { "—" })
                else getString(R.string.set_server_off)
            )
            append('\n')
            append(
                if (barcodes.valid) getString(R.string.set_bc_ok, barcodes.template)
                else getString(R.string.set_bc_bad)
            )
        }
    }
}
