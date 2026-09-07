package ru.lookalike

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import ru.lookalike.databinding.ActivityMainBinding
import java.util.concurrent.Executors
import kotlin.system.measureTimeMillis

/**
 * Рабочий экран. Здесь делается всё, ради чего приложение существует:
 * распознать товар, взять вес с весов и держать оба значения наготове
 * для 1С. Остальные экраны — настройка и обслуживание.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var ui: ActivityMainBinding
    private val worker = Executors.newSingleThreadExecutor()

    private var encoder: Encoder? = null
    private var store: IndexStore? = null
    private val scaleSettings by lazy { ScaleSettings(this) }
    private val barcodes by lazy { BarcodeSettings(this) }

    /** Кадр и его вектор — чтобы можно было приписать снимок к товару. */
    private var lastEmbedding: FloatArray? = null
    private var lastFrame: Bitmap? = null

    /** Если не пусто — идёт добавление нового товара с этим названием. */
    private var addingTo: String? = null
    private var addedCount = 0

    private val askCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startCamera() else ui.status.text = getString(R.string.no_camera) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityMainBinding.inflate(layoutInflater)
        setContentView(ui.root)

        ui.status.text = getString(R.string.loading)
        ui.shoot.isEnabled = false
        ui.getWeight.isEnabled = false

        ui.shoot.setOnClickListener { if (addingTo != null) captureForProduct() else recognize() }
        ui.getWeight.setOnClickListener { fetchWeight(manual = true) }
        ui.doneAdding.setOnClickListener { finishAdding() }
        ui.newProduct.setOnClickListener { askProductName() }
        ui.catalog.setOnClickListener { open(CatalogActivity::class.java) }
        ui.scale.setOnClickListener { open(ScaleActivity::class.java) }
        ui.integration.setOnClickListener { open(IntegrationActivity::class.java) }

        worker.execute {
            val ms = measureTimeMillis {
                store = App.store(this)
                encoder = App.encoder(this)
            }
            runOnUiThread {
                ui.status.text = getString(R.string.ready_ms, ms)
                ui.shoot.isEnabled = true
                showState()
            }
        }

        // Сервер для 1С поднимается сам, если его включали раньше
        Servers.restoreIfEnabled(this)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera() else askCamera.launch(Manifest.permission.CAMERA)
    }

    private fun open(screen: Class<*>) = startActivity(Intent(this, screen))

    override fun onResume() {
        super.onResume()
        showState()
    }

    /** Всё, что видно на рабочем экране: вес, выбранный товар, состояние связи. */
    private fun showState() {
        val now = State.current

        val grams = now.weightGrams
        ui.weight.text = when {
            grams == null -> getString(R.string.weight_zero)
            kotlin.math.abs(grams) >= 1000 -> getString(R.string.scale_kg, grams / 1000)
            else -> getString(R.string.scale_g, grams)
        }
        ui.weightNote.text = when {
            !scaleSettings.configured -> getString(R.string.weight_no_scale)
            grams == null -> getString(R.string.weight_not_asked)
            now.stable -> getString(R.string.scale_stable)
            else -> getString(R.string.scale_unstable)
        }
        ui.getWeight.isEnabled = scaleSettings.configured

        ui.chosen.text = if (now.name.isBlank()) {
            getString(R.string.chosen_none)
        } else buildString {
            append(getString(R.string.chosen_name, now.name, now.score * 100))
            append('\n')
            when {
                now.plu.isBlank() ->
                    // Без номера касса товар не найдёт — говорим прямо
                    append(getString(R.string.chosen_no_plu))
                else -> {
                    val code = barcodes.build(now.plu, (grams ?: 0.0).toInt())
                    append(getString(R.string.chosen_plu, now.plu))
                    if (code.isNotBlank()) append("  ").append(code)
                }
            }
        }

        val base = store
        ui.links.text = buildString {
            append(if (Servers.running) getString(R.string.link_server_on, Servers.httpPort(this@MainActivity))
                   else getString(R.string.link_server_off))
            append("   ·   ")
            append(if (scaleSettings.configured) getString(R.string.link_scale_on, scaleSettings.host)
                   else getString(R.string.link_scale_off))
            if (base != null) append("   ·   ").append(getString(R.string.link_base, base.size))
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build()
                .also { it.setSurfaceProvider(ui.viewFinder.surfaceProvider) }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
        }, ContextCompat.getMainExecutor(this))
    }

    // ------------------------------------------------------------- вес

    /**
     * Спрашивает массу у настоящих весов. Вызывается кнопкой и сам —
     * сразу после распознавания, чтобы одно нажатие давало и товар, и вес.
     */
    private fun fetchWeight(manual: Boolean) {
        if (!scaleSettings.configured) {
            if (manual) Toast.makeText(this, R.string.weight_no_scale, Toast.LENGTH_LONG).show()
            return
        }
        ui.getWeight.isEnabled = false
        if (manual) ui.weightNote.text = getString(R.string.weight_asking)

        worker.execute {
            val known = scaleSettings.crcInit.takeIf { it >= 0 }
            val result = scaleSettings.client().ask(ScaleProtocol.CMD_GET_MASSA, known)
            val reply = result.reply
            if (reply is ScaleProtocol.Reply.Massa) {
                scaleSettings.crcInit = result.crcInit
                State.setWeight(reply.grams, reply.stable)
            }
            runOnUiThread {
                if (reply !is ScaleProtocol.Reply.Massa) {
                    val what = result.error ?: (reply as? ScaleProtocol.Reply.Failed)?.what ?: "—"
                    ui.weightNote.text = getString(R.string.scale_failed, what)
                }
                ui.getWeight.isEnabled = true
                showState()
            }
        }
    }

    // ------------------------------------------------------- распознавание

    private fun recognize() {
        val engine = encoder ?: return
        val base = store ?: return
        val frame: Bitmap = ui.viewFinder.bitmap ?: run {
            ui.status.text = getString(R.string.no_frame); return
        }
        ui.shoot.isEnabled = false
        ui.status.text = getString(R.string.thinking)
        ui.results.removeAllViews()

        worker.execute {
            val matches: List<Match>
            val ms = measureTimeMillis {
                val vector = engine.embed(frame)
                lastEmbedding = vector
                lastFrame = frame
                matches = base.search(vector, 5)
            }
            matches.firstOrNull()?.let { top -> select(top, base) }
            runOnUiThread {
                showResults(matches, ms)
                showState()
                ui.shoot.isEnabled = true
                // Одно нажатие — и товар, и вес
                if (scaleSettings.configured) fetchWeight(manual = false)
            }
        }
    }

    /** Товар, который уйдёт в 1С: название плюс его номер и штрихкод из базы. */
    private fun select(match: Match, base: IndexStore) {
        State.setProduct(match.label, base.infoOf(match.label), match.score)
    }

    private fun showResults(matches: List<Match>, ms: Long) {
        ui.status.text = getString(R.string.took, ms)
        matches.forEachIndexed { position, match ->
            val row = layoutInflater.inflate(R.layout.item_match, ui.results, false)
            row.findViewById<TextView>(R.id.name).text = match.label
            row.findViewById<TextView>(R.id.score).text =
                getString(R.string.percent, match.score * 100)
            val bar = row.findViewById<View>(R.id.bar)
            bar.layoutParams = (bar.layoutParams as LinearLayout.LayoutParams)
                .apply { weight = match.score.coerceIn(0f, 1f) }
            if (position == 0) row.setBackgroundResource(R.drawable.card_top)
            row.setOnClickListener { choose(match) }
            ui.results.addView(row)
        }
    }

    /** Выбор оператора важнее догадки: он и уходит в 1С. */
    private fun choose(match: Match) {
        store?.let { select(match, it) }
        showState()
        offerToTeach(match.label)
    }

    private fun offerToTeach(name: String) {
        val vector = lastEmbedding ?: return
        AlertDialog.Builder(this)
            .setTitle(name)
            .setMessage(R.string.teach_question)
            .setPositiveButton(R.string.teach_yes) { _, _ ->
                store?.add(vector, name, lastFrame)
                Toast.makeText(this, getString(R.string.taught, name), Toast.LENGTH_SHORT).show()
                showState()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // --------------------------------------------------------- новый товар

    private fun askProductName() {
        val view = layoutInflater.inflate(R.layout.dialog_product, null)
        val nameField = view.findViewById<EditText>(R.id.name)
        val pluField = view.findViewById<EditText>(R.id.plu)
        val barcodeField = view.findViewById<EditText>(R.id.barcode)

        AlertDialog.Builder(this)
            .setTitle(R.string.new_product)
            .setView(view)
            .setPositiveButton(R.string.start) { _, _ ->
                val name = nameField.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                store?.setInfo(
                    name,
                    ProductInfo(
                        pluField.text.toString().trim(),
                        barcodeField.text.toString().trim()
                    )
                )
                addingTo = name
                addedCount = 0
                ui.results.removeAllViews()
                ui.doneAdding.visibility = View.VISIBLE
                ui.shoot.setText(R.string.take_shot)
                ui.status.text = getString(R.string.adding, name, 0)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun captureForProduct() {
        val engine = encoder ?: return
        val name = addingTo ?: return
        val frame: Bitmap = ui.viewFinder.bitmap ?: return
        ui.shoot.isEnabled = false
        worker.execute {
            val vector = engine.embed(frame)
            store?.add(vector, name, frame)
            addedCount++
            runOnUiThread {
                ui.status.text = getString(R.string.adding, name, addedCount)
                ui.shoot.isEnabled = true
                showState()
            }
        }
    }

    private fun finishAdding() {
        val name = addingTo ?: return
        addingTo = null
        ui.doneAdding.visibility = View.GONE
        ui.shoot.setText(R.string.shoot)
        Toast.makeText(this, getString(R.string.added, name, addedCount), Toast.LENGTH_LONG).show()
        showState()
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
    }
}
