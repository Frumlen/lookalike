package ru.lookalike

import android.content.Context

/**
 * Модель и база нужны обоим экранам, а модель поднимается секунды —
 * поэтому держим по одному экземпляру на всё приложение.
 */
object App {
    private var encoderRef: Encoder? = null
    private var storeRef: IndexStore? = null

    fun encoder(context: Context): Encoder =
        encoderRef ?: Encoder(context.applicationContext).also { encoderRef = it }

    fun store(context: Context): IndexStore =
        storeRef ?: IndexStore(context.applicationContext).also { storeRef = it }
}
