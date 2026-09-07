package ru.lookalike

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import ru.lookalike.databinding.ActivityCatalogBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Список товаров: сколько снимков у каждого, можно переименовать или удалить. */
class CatalogActivity : AppCompatActivity() {

    private lateinit var ui: ActivityCatalogBinding
    private val store by lazy { App.store(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityCatalogBinding.inflate(layoutInflater)
        setContentView(ui.root)
        ui.export.setOnClickListener { shareBase() }
        ui.loadBase.setOnClickListener { pickFile.launch(arrayOf("application/zip", "*/*")) }
        ui.wipe.setOnClickListener { askWipe() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val items = store.catalog()
        ui.summary.text = getString(R.string.catalog_summary, items.size, store.size)
        ui.list.removeAllViews()
        items.forEach { (name, count) ->
            val row = layoutInflater.inflate(R.layout.item_product, ui.list, false)
            row.findViewById<TextView>(R.id.name).text = name
            row.findViewById<TextView>(R.id.count).text =
                resources.getQuantityString(R.plurals.shots, count, count)
            if (count < 4) row.findViewById<TextView>(R.id.count)
                .setTextColor(getColor(R.color.warn))
            row.setOnClickListener {
                startActivity(
                    android.content.Intent(this, ProductActivity::class.java)
                        .putExtra(ProductActivity.EXTRA_NAME, name)
                )
            }
            row.setOnLongClickListener { menuFor(name, count); true }
            ui.list.addView(row)
        }
    }

    private fun menuFor(name: String, count: Int) {
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(arrayOf(getString(R.string.rename), getString(R.string.delete))) { _, which ->
                if (which == 0) askRename(name) else confirmDelete(name, count)
            }
            .show()
    }

    private fun askRename(name: String) {
        val field = EditText(this).apply { setText(name) }
        AlertDialog.Builder(this)
            .setTitle(R.string.rename)
            .setView(field)
            .setPositiveButton(R.string.save) { _, _ ->
                val to = field.text.toString().trim()
                if (to.isNotEmpty() && to != name) {
                    store.rename(name, to)
                    refresh()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(name: String, count: Int) {
        AlertDialog.Builder(this)
            .setTitle(name)
            .setMessage(getString(R.string.delete_question, count))
            .setPositiveButton(R.string.delete) { _, _ ->
                store.removeClass(name)
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Вся база одним файлом. Дальше её можно отправить куда угодно:
     * на другой телефон, на компьютер, в сообщение.
     */
    private fun shareBase() {
        val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val file = File(cacheDir, "lookalike-base-$stamp.zip")
        Toast.makeText(this, R.string.packing, Toast.LENGTH_SHORT).show()
        Thread {
            store.exportZip(file)
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            runOnUiThread {
                startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "application/zip"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },
                        getString(R.string.export)
                    )
                )
            }
        }.start()
    }

    /**
     * Очистка базы. Спрашиваем дважды: действие необратимое, а кнопка
     * находится рядом с обычными.
     */
    private fun askWipe() {
        if (store.size == 0) {
            Toast.makeText(this, R.string.wipe_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val products = store.catalog().size
        AlertDialog.Builder(this)
            .setTitle(R.string.wipe)
            .setMessage(getString(R.string.wipe_first, products, store.size))
            .setPositiveButton(R.string.next) { _, _ -> confirmWipe() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmWipe() {
        AlertDialog.Builder(this)
            .setTitle(R.string.wipe_sure_title)
            .setMessage(R.string.wipe_sure)
            .setPositiveButton(R.string.wipe_do) { _, _ ->
                store.clear()
                refresh()
                Toast.makeText(this, R.string.wipe_done, Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private val pickFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) confirmImport(uri) }

    private fun confirmImport(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle(R.string.import_base)
            .setMessage(R.string.import_question)
            .setPositiveButton(R.string.import_do) { _, _ ->
                Thread {
                    val error = try {
                        contentResolver.openInputStream(uri)!!.use { store.importZip(it) }
                        null
                    } catch (exc: Exception) {
                        "${exc.javaClass.simpleName}: ${exc.message}"
                    }
                    runOnUiThread {
                        if (error == null) {
                            Toast.makeText(this, R.string.import_done, Toast.LENGTH_LONG).show()
                            refresh()
                        } else {
                            AlertDialog.Builder(this)
                                .setTitle(R.string.import_failed)
                                .setMessage(error)
                                .setPositiveButton(android.R.string.ok, null)
                                .show()
                        }
                    }
                }.start()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
