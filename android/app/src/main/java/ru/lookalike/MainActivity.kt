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

class MainActivity : AppCompatActivity() {

    private lateinit var ui: ActivityMainBinding
    private val worker = Executors.newSingleThreadExecutor()

    private var encoder: Encoder? = null
    private var store: IndexStore? = null

    /** Вектор последнего распознанного кадра — чтобы можно было приписать его к товару. */
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
        ui.shoot.setOnClickListener { if (addingTo != null) captureForProduct() else recognize() }
        ui.newProduct.setOnClickListener { askProductName() }
        ui.catalog.setOnClickListener { startActivity(Intent(this, CatalogActivity::class.java)) }
        ui.doneAdding.setOnClickListener { finishAdding() }

        worker.execute {
            val ms = measureTimeMillis {
                store = App.store(this)
                encoder = App.encoder(this)
            }
            runOnUiThread {
                ui.status.text = getString(R.string.ready, store?.size ?: 0, ms)
                ui.shoot.isEnabled = true
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera() else askCamera.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() {
        super.onResume()
        // Из каталога могли удалить товар — обновим счётчик
        store?.let { ui.status.text = getString(R.string.shots_total, it.size, it.catalog().size) }
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

    // ---------------------------------------------------------------- поиск

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
            runOnUiThread {
                showResults(matches, ms)
                ui.shoot.isEnabled = true
            }
        }
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
            if (position == 0) row.setBackgroundResource(R.color.top_row)
            row.setOnClickListener { offerToTeach(match.label) }
            ui.results.addView(row)
        }
    }

    /** Нажали на вариант — предлагаем запомнить этот кадр как ещё один снимок товара. */
    private fun offerToTeach(name: String) {
        val vector = lastEmbedding ?: return
        AlertDialog.Builder(this)
            .setTitle(name)
            .setMessage(R.string.teach_question)
            .setPositiveButton(R.string.teach_yes) { _, _ ->
                store?.add(vector, name, lastFrame)
                Toast.makeText(this, getString(R.string.taught, name), Toast.LENGTH_SHORT).show()
                store?.let { ui.status.text = getString(R.string.shots_total, it.size, it.catalog().size) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------- новый товар

    private fun askProductName() {
        val field = EditText(this).apply { hint = getString(R.string.product_hint) }
        AlertDialog.Builder(this)
            .setTitle(R.string.new_product)
            .setView(field)
            .setPositiveButton(R.string.start) { _, _ ->
                val name = field.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
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
            }
        }
    }

    private fun finishAdding() {
        val name = addingTo ?: return
        addingTo = null
        ui.doneAdding.visibility = View.GONE
        ui.shoot.setText(R.string.shoot)
        Toast.makeText(this, getString(R.string.added, name, addedCount), Toast.LENGTH_LONG).show()
        store?.let { ui.status.text = getString(R.string.shots_total, it.size, it.catalog().size) }
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
    }
}
