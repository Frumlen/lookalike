package ru.lookalike

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * База эталонов, которую можно править прямо на телефоне.
 *
 * Каждая запись — вектор, название товара и миниатюра снимка. Обучения нет:
 * добавить товар значит дописать несколько записей, удалить — стереть их.
 *
 * При первом запуске содержимое берётся из assets, дальше живёт в памяти
 * приложения. Ссылка на миниатюру имеет префикс: "a/" — лежит в assets,
 * "f/" — снято на телефоне и лежит в файлах приложения.
 */
class IndexStore(private val context: Context) {

    private val vectors = ArrayList<FloatArray>()
    private val labels = ArrayList<String>()
    private val thumbs = ArrayList<String>()
    var dim: Int = 0
        private set

    private val binFile get() = File(context.filesDir, "index.bin")
    private val metaFile get() = File(context.filesDir, "index.json")
    private val thumbDir get() = File(context.filesDir, "thumbs").apply { mkdirs() }

    init {
        if (binFile.exists() && metaFile.exists()) load() else seedFromAssets()
    }

    private fun readVectors(raw: ByteArray, meta: JSONObject, names: JSONArray, thumbList: JSONArray?) {
        dim = meta.getInt("dim")
        val floats = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        for (n in 0 until names.length()) {
            val vector = FloatArray(dim)
            floats.get(vector)
            vectors.add(vector)
            labels.add(names.getString(n))
            thumbs.add(thumbList?.optString(n) ?: "")
        }
    }

    private fun seedFromAssets() {
        val meta = JSONObject(context.assets.open("index.json").bufferedReader().readText())
        readVectors(
            context.assets.open("index.bin").readBytes(),
            meta,
            meta.getJSONArray("labels"),
            meta.optJSONArray("thumbs")
        )
        save()
    }

    private fun load() {
        val meta = JSONObject(metaFile.readText())
        readVectors(binFile.readBytes(), meta, meta.getJSONArray("labels"),
            meta.optJSONArray("thumbs"))
    }

    fun save() {
        val buffer = ByteBuffer.allocate(vectors.size * dim * 4).order(ByteOrder.LITTLE_ENDIAN)
        vectors.forEach { vector -> vector.forEach { buffer.putFloat(it) } }
        binFile.writeBytes(buffer.array())
        metaFile.writeText(
            JSONObject()
                .put("dim", dim)
                .put("labels", JSONArray(labels))
                .put("thumbs", JSONArray(thumbs))
                .toString()
        )
    }

    /**
     * Вся база одним файлом: векторы, названия и снимки. Файл самодостаточный —
     * его можно открыть на другом телефоне, положить на компьютер или передать
     * на другое устройство.
     */
    fun exportZip(target: File): File {
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            val buffer = ByteBuffer.allocate(vectors.size * dim * 4).order(ByteOrder.LITTLE_ENDIAN)
            vectors.forEach { vector -> vector.forEach { buffer.putFloat(it) } }
            zip.putNextEntry(ZipEntry("vectors.bin"))
            zip.write(buffer.array())
            zip.closeEntry()

            // Снимки перенумеровываем подряд: файл не должен зависеть от того,
            // что лежит в assets конкретной сборки
            val names = ArrayList<String>(thumbs.size)
            thumbs.forEachIndexed { n, thumb ->
                val bytes = readThumbBytes(thumb)
                if (bytes == null) {
                    names.add("")
                } else {
                    val name = "$n.jpg"
                    zip.putNextEntry(ZipEntry("thumbs/$name"))
                    zip.write(bytes)
                    zip.closeEntry()
                    names.add(name)
                }
            }

            zip.putNextEntry(ZipEntry("meta.json"))
            zip.write(
                JSONObject()
                    .put("dim", dim)
                    .put("count", vectors.size)
                    .put("labels", JSONArray(labels))
                    .put("thumbs", JSONArray(names))
                    .toString().toByteArray()
            )
            zip.closeEntry()
        }
        return target
    }

    private fun readThumbBytes(thumb: String): ByteArray? = try {
        when {
            thumb.startsWith("a/") -> context.assets.open("thumbs/${thumb.substring(2)}").use { it.readBytes() }
            thumb.startsWith("f/") -> File(thumbDir, thumb.substring(2)).takeIf { it.exists() }?.readBytes()
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    /** Принять базу из файла, созданного exportZip. Прежняя заменяется целиком. */
    fun importZip(input: InputStream) {
        var meta: JSONObject? = null
        var raw: ByteArray? = null
        val incoming = HashMap<String, ByteArray>()

        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val bytes = zip.readBytes()
                when {
                    entry.name == "meta.json" -> meta = JSONObject(String(bytes))
                    entry.name == "vectors.bin" -> raw = bytes
                    entry.name.startsWith("thumbs/") ->
                        incoming[entry.name.substringAfter('/')] = bytes
                }
                zip.closeEntry()
            }
        }
        val info = meta ?: throw IllegalArgumentException("нет meta.json")
        val bin = raw ?: throw IllegalArgumentException("нет vectors.bin")

        thumbDir.listFiles()?.forEach { it.delete() }
        vectors.clear(); labels.clear(); thumbs.clear()

        dim = info.getInt("dim")
        val names = info.getJSONArray("labels")
        val thumbNames = info.optJSONArray("thumbs")
        val floats = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        for (n in 0 until names.length()) {
            val vector = FloatArray(dim)
            floats.get(vector)
            vectors.add(vector)
            labels.add(names.getString(n))
            val source = thumbNames?.optString(n).orEmpty()
            val bytes = incoming[source]
            if (bytes == null) {
                thumbs.add("")
            } else {
                val name = "${UUID.randomUUID()}.jpg"
                File(thumbDir, name).writeBytes(bytes)
                thumbs.add("f/$name")
            }
        }
        save()
    }

    // ------------------------------------------------------------ правка

    fun add(vector: FloatArray, name: String, shot: Bitmap?) {
        vectors.add(vector.copyOf())
        labels.add(name)
        thumbs.add(shot?.let { saveThumb(it) } ?: "")
        save()
    }

    private fun saveThumb(shot: Bitmap): String {
        val side = minOf(shot.width, shot.height)
        val square = Bitmap.createBitmap(
            shot, (shot.width - side) / 2, (shot.height - side) / 2, side, side
        )
        val small = Bitmap.createScaledBitmap(square, THUMB, THUMB, true)
        val name = "${UUID.randomUUID()}.jpg"
        File(thumbDir, name).outputStream().use {
            small.compress(Bitmap.CompressFormat.JPEG, 80, it)
        }
        if (square !== shot) square.recycle()
        if (small !== square) small.recycle()
        return "f/$name"
    }

    /** Удалить один снимок. Индексы — те, что вернул shotsOf(). */
    fun removeShot(index: Int) {
        dropThumbFile(thumbs[index])
        vectors.removeAt(index)
        labels.removeAt(index)
        thumbs.removeAt(index)
        save()
    }

    fun removeClass(name: String) {
        for (n in labels.indices.reversed()) {
            if (labels[n] == name) {
                dropThumbFile(thumbs[n])
                vectors.removeAt(n); labels.removeAt(n); thumbs.removeAt(n)
            }
        }
        save()
    }

    private fun dropThumbFile(thumb: String) {
        if (thumb.startsWith("f/")) File(thumbDir, thumb.substring(2)).delete()
    }

    fun rename(from: String, to: String) {
        for (n in labels.indices) if (labels[n] == from) labels[n] = to
        save()
    }

    // ------------------------------------------------------------ чтение

    /** Названия товаров и число снимков у каждого, по алфавиту. */
    fun catalog(): List<Pair<String, Int>> =
        labels.groupingBy { it }.eachCount().toList().sortedBy { it.first }

    /** Номера записей, относящихся к товару. */
    fun shotsOf(name: String): List<Int> = labels.indices.filter { labels[it] == name }

    fun labelAt(index: Int): String = labels[index]

    /** Миниатюра записи; null, если её нет. */
    fun thumbAt(index: Int): Bitmap? {
        val thumb = thumbs.getOrNull(index) ?: return null
        return try {
            when {
                thumb.startsWith("a/") ->
                    context.assets.open("thumbs/${thumb.substring(2)}").use {
                        BitmapFactory.decodeStream(it)
                    }
                thumb.startsWith("f/") ->
                    BitmapFactory.decodeFile(File(thumbDir, thumb.substring(2)).path)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    val size get() = vectors.size

    /** Ближайшие товары: по каждому берём его лучший снимок. */
    fun search(query: FloatArray, top: Int): List<Match> {
        val best = HashMap<String, Float>()
        for (n in vectors.indices) {
            val vector = vectors[n]
            var dot = 0f
            for (d in 0 until dim) dot += vector[d] * query[d]
            val name = labels[n]
            if (dot > (best[name] ?: -1f)) best[name] = dot
        }
        return best.entries.sortedByDescending { it.value }.take(top)
            .map { Match(it.key, it.value) }
    }

    private companion object {
        const val THUMB = 224
    }
}
