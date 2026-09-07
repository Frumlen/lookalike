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
        val info = store.infoOf(product)
        ui.summary.text = buildString {
            append(resources.getQuantityString(R.plurals.shots, shots.size, shots.size))
            append("   ·   ")
            // Без номера на весах касса не найдёт позицию — это надо видеть сразу
            if (info.plu.isBlank()) append(getString(R.string.chosen_no_plu))
            else append(getString(R.string.chosen_plu, info.plu))
            if (info.barcode.isNotBlank()) append("   ·   ").append(info.barcode)
        }

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

    /** Правка карточки: название для человека, номер и штрихкод для кассы. */
    private fun askRename() {
        val view = layoutInflater.inflate(R.layout.dialog_product, null)
        val nameField = view.findViewById<EditText>(R.id.name)
        val pluField = view.findViewById<EditText>(R.id.plu)
        val barcodeField = view.findViewById<EditText>(R.id.barcode)

        val info = store.infoOf(product)
        nameField.setText(product)
        pluField.setText(info.plu)
        barcodeField.setText(info.barcode)

        AlertDialog.Builder(this)
            .setTitle(R.string.edit_product)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val to = nameField.text.toString().trim()
                if (to.isNotEmpty() && to != product) {
                    store.rename(product, to)
                    product = to
                }
                store.setInfo(
                    product,
                    ProductInfo(
                        pluField.text.toString().trim(),
                        barcodeField.text.toString().trim()
                    )
                )
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_NAME = "product"
    }
}
