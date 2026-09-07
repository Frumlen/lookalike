package ru.lookalike

import android.content.Context

/**
 * Весовой штрихкод — то, чем весы с печатью этикеток сообщают кассе,
 * какой товар и сколько взвесили.
 *
 * Обычный вид в российской рознице: 13 цифр `2 ККККК ВВВВВ К`, где
 * 2 — признак весового товара, ККККК — номер товара на весах (PLU),
 * ВВВВВ — масса в граммах, К — контрольная цифра.
 *
 * Разметка задаётся в 1С (шаблон весового штрихкода) и здесь настраивается
 * так же — иначе касса прочитает не то.
 */
object Barcode {

    /** Контрольная цифра EAN-13 по первым двенадцати. */
    fun checkDigit(twelve: String): Int {
        var sum = 0
        for (i in twelve.indices) {
            val digit = twelve[i] - '0'
            sum += if (i % 2 == 0) digit else digit * 3
        }
        return (10 - sum % 10) % 10
    }

    /**
     * Собирает весовой штрихкод. Пустая строка — если номер товара не задан
     * или не помещается в отведённые разряды: лучше не отдать ничего,
     * чем отдать штрихкод чужого товара.
     */
    fun weight(prefix: String, plu: String, grams: Int, pluDigits: Int, weightDigits: Int): String {
        val digitsOnly = plu.filter { it.isDigit() }
        if (digitsOnly.isEmpty() || digitsOnly.length > pluDigits) return ""

        val safeGrams = grams.coerceAtLeast(0)
        val weightPart = safeGrams.toString().padStart(weightDigits, '0')
        if (weightPart.length > weightDigits) return ""            // масса не влезла

        val body = prefix + digitsOnly.padStart(pluDigits, '0') + weightPart
        if (body.length != 12) return ""                           // шаблон не даёт EAN-13
        return body + checkDigit(body)
    }
}

/** Настройки весового штрихкода: должны совпадать с шаблоном в 1С. */
class BarcodeSettings(context: Context) {

    private val prefs = context.getSharedPreferences("barcode", Context.MODE_PRIVATE)

    var prefix: String
        get() = prefs.getString("prefix", "2") ?: "2"
        set(value) = prefs.edit().putString("prefix", value.filter { it.isDigit() }).apply()

    var pluDigits: Int
        get() = prefs.getInt("plu", 5)
        set(value) = prefs.edit().putInt("plu", value).apply()

    /** По умолчанию шесть: с префиксом и номером это даёт ровно 12 цифр EAN-13. */
    var weightDigits: Int
        get() = prefs.getInt("weight", 6)
        set(value) = prefs.edit().putInt("weight", value).apply()

    /** Шаблон одной строкой, как он выглядит в настройках 1С. */
    val template: String
        get() = prefix + "К".repeat(pluDigits) + "В".repeat(weightDigits) + "К"

    /** Разметка обязана давать 12 цифр до контрольной, иначе это не EAN-13. */
    val valid: Boolean
        get() = prefix.length + pluDigits + weightDigits == 12

    /** Пример готового штрихкода — чтобы сверить с тем, что настроено в 1С. */
    fun sample(): String = build("55", 1234).ifBlank { "—" }

    fun build(plu: String, grams: Int): String =
        Barcode.weight(prefix, plu, grams, pluDigits, weightDigits)
}
