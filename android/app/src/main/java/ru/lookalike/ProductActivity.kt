package ru.lookalike

import android.app.AlertDialog
import android.os.Bundle
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import ru.lookalike.databinding.ActivityProductBinding

/** Снимки одного товара: посмотреть, что запомнила система, и убрать лишнее. */
class ProductActivity : AppCompatActivity() {

    private lateinit var ui: ActivityProductBinding
    private val store by lazy { App.store(this) }
    private lateinit var product: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityProductBinding.inflate(layoutInflater)
        setContentView(ui.root)

        product = intent.getStringExtra(EXTRA_NAME).orEmpty()
        ui.rename.setOnClickListener { askRename() }
        refresh()
    }

    private fun refresh() {
        if (store.shotsOf(product).isEmpty()) { finish(); return }
        title = product
        ui.name.text = product
        val shots = store.shotsOf(product)
        ui.summary.text = resources.getQuantityString(R.plurals.shots, shots.size, shots.size)

        ui.grid.removeAllViews()
        ui.grid.columnCount = 3
        shots.forEach { index ->
            val cell = layoutInflater.inflate(R.layout.item_shot, ui.grid, false)
            cell.clipToOutline = true          // скругляет картинку по фону ячейки
            val image = cell.findViewById<ImageView>(R.id.shot)
            val bitmap = store.thumbAt(index)
            if (bitmap != null) image.setImageBitmap(bitmap)
            else cell.findViewById<TextView>(R.id.missing).text = getString(R.string.no_thumb)
            cell.setOnClickListener { confirmDeleteShot(index) }

            // Ячейки растягиваем на треть ширины
            cell.layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(6, 6, 6, 6)
            }
            ui.grid.addView(cell)
        }
    }

    private fun confirmDeleteShot(index: Int) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_shot)
            .setMessage(R.string.delete_shot_question)
            .setPositiveButton(R.string.delete) { _, _ ->
                store.removeShot(index)
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun askRename() {
        val field = EditText(this).apply { setText(product) }
        AlertDialog.Builder(this)
            .setTitle(R.string.rename)
            .setMessage(R.string.rename_hint)
            .setView(field)
            .setPositiveButton(R.string.save) { _, _ ->
                val to = field.text.toString().trim()
                if (to.isNotEmpty() && to != product) {
                    store.rename(product, to)
                    product = to
                    refresh()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_NAME = "product"
    }
}
