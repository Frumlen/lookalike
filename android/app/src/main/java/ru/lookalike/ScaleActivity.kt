package ru.lookalike

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import ru.lookalike.databinding.ActivityScaleBinding
import java.util.concurrent.Executors

/** Настройки весов и проверка связи: ввести адрес, нажать — увидеть вес. */
class ScaleActivity : AppCompatActivity() {

    private lateinit var ui: ActivityScaleBinding
    private val settings by lazy { ScaleSettings(this) }
    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityScaleBinding.inflate(layoutInflater)
        setContentView(ui.root)

        ui.host.setText(settings.host)
        ui.port.setText(settings.port.toString())
        ui.timeout.setText(settings.timeout.toString())

        ui.weigh.setOnClickListener { run(ScaleProtocol.CMD_GET_MASSA) }
        ui.params.setOnClickListener { run(ScaleProtocol.CMD_GET_SCALE_PAR) }
    }

    private fun save() {
        settings.host = ui.host.text.toString()
        settings.port = ui.port.text.toString().toIntOrNull() ?: 5001
        settings.timeout = ui.timeout.text.toString().toIntOrNull() ?: 3000
    }

    private fun run(command: Int) {
        save()
        if (!settings.configured) {
            ui.log.text = getString(R.string.scale_no_host)
            return
        }
        ui.weigh.isEnabled = false
        ui.params.isEnabled = false
        ui.result.text = getString(R.string.scale_asking)
        ui.log.text = ""

        worker.execute {
            val known = settings.crcInit.takeIf { it >= 0 }
            val result = settings.client().ask(command, known)
            runOnUiThread {
                show(result)
                ui.weigh.isEnabled = true
                ui.params.isEnabled = true
            }
        }
    }

    private fun show(result: ScaleResult) {
        val reply = result.reply
        // Запоминаем подошедший вариант CRC, чтобы дальше не перебирать
        if (reply != null && reply !is ScaleProtocol.Reply.Unknown) settings.crcInit = result.crcInit
        // Полученную массу сразу отдаём наружу — её заберёт 1С
        if (reply is ScaleProtocol.Reply.Massa) State.setWeight(reply.grams, reply.stable)

        ui.result.text = when {
            result.error != null -> getString(R.string.scale_failed, result.error)
            reply is ScaleProtocol.Reply.Massa -> buildString {
                append(formatMass(reply.grams))
                append(if (reply.stable) "\n${getString(R.string.scale_stable)}"
                       else "\n${getString(R.string.scale_unstable)}")
                if (reply.net) append("  NET")
                if (reply.zero) append("  >0<")
                reply.tareGrams?.let { append("\n${getString(R.string.scale_tare, formatMass(it))}") }
            }
            reply is ScaleProtocol.Reply.Params -> reply.text
            reply is ScaleProtocol.Reply.Failed -> getString(R.string.scale_failed, reply.what)
            reply is ScaleProtocol.Reply.Unknown -> getString(R.string.scale_unknown, reply.command)
            else -> getString(R.string.scale_failed, "—")
        }

        ui.log.text = buildString {
            append("отправлено: ").append(ScaleProtocol.hex(result.sent)).append('\n')
            append("получено:   ")
            append(if (result.received.isEmpty()) "—" else ScaleProtocol.hex(result.received))
            append('\n')
            append("CRC начальное: 0x%04X".format(result.crcInit)).append('\n')
            append("время: ${result.millis} мс")
        }
    }

    private fun formatMass(grams: Double): String =
        if (kotlin.math.abs(grams) >= 1000)
            getString(R.string.scale_kg, grams / 1000)
        else
            getString(R.string.scale_g, grams)

    override fun onPause() {
        super.onPause()
        save()
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
    }
}
