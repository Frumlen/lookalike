package ru.lookalike

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.nio.FloatBuffer

/** Один вариант ответа: название товара и насколько он похож. */
data class Match(val label: String, val score: Float)

/**
 * Кадр -> вектор. Модель не обучается: DINOv2 взята с готовыми весами
 * и работает как преобразователь изображения в набор чисел.
 */
class Encoder(context: Context) {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceAtMost(4))
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = env.createSession(context.assets.open(MODEL).readBytes(), options)
    }

    fun embed(bitmap: Bitmap): FloatArray {
        val input = preprocess(bitmap)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), INPUT_SHAPE).use { tensor ->
            session.run(mapOf("pixel_values" to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                return (result[0].value as Array<FloatArray>)[0].copyOf()
            }
        }
    }

    /**
     * Подготовка кадра ровно так же, как это делает build_index.py на компьютере:
     * короткую сторону к 256, вырезать центр 224x224, вычесть среднее и поделить
     * на разброс. Иначе векторы окажутся из разных пространств.
     */
    private fun preprocess(source: Bitmap): FloatArray {
        val scale = RESIZE.toFloat() / minOf(source.width, source.height)
        val scaled = Bitmap.createScaledBitmap(
            source, Math.round(source.width * scale), Math.round(source.height * scale), true
        )
        val left = (scaled.width - CROP) / 2
        val top = (scaled.height - CROP) / 2
        val cropped = Bitmap.createBitmap(scaled, left, top, CROP, CROP)

        val pixels = IntArray(CROP * CROP)
        cropped.getPixels(pixels, 0, CROP, 0, 0, CROP, CROP)
        if (cropped !== scaled) cropped.recycle()
        if (scaled !== source) scaled.recycle()

        val out = FloatArray(3 * CROP * CROP)
        val plane = CROP * CROP
        for (i in pixels.indices) {
            val pixel = pixels[i]
            out[i] = ((pixel shr 16 and 0xFF) / 255f - MEAN[0]) / STD[0]
            out[plane + i] = ((pixel shr 8 and 0xFF) / 255f - MEAN[1]) / STD[1]
            out[2 * plane + i] = ((pixel and 0xFF) / 255f - MEAN[2]) / STD[2]
        }
        return out
    }

    fun close() = session.close()

    companion object {
        private const val MODEL = "dinov2_small.onnx"
        private const val RESIZE = 256
        private const val CROP = 224
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
        private val INPUT_SHAPE = longArrayOf(1, 3, CROP.toLong(), CROP.toLong())
    }
}
