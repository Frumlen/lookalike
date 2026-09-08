package ru.lookalike

import android.content.Context
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Связь с весами Масса-К по «Протоколу 100» (документ Мк.2.790.237РЭ).
 *
 * Кадр:  F8 55 CE | Len (int16) | Command | данные | CRC
 * CRC — CRC-16-CCITT, считается начиная с байта Command, длиной Len.
 *
 * Инициатор обмена всегда мы: открываем TCP-соединение, шлём команду,
 * читаем ответ, закрываем.
 */
object ScaleProtocol {

    private val HEADER = byteArrayOf(0xF8.toByte(), 0x55, 0xCE.toByte())

    const val CMD_GET_MASSA = 0x23
    const val CMD_ACK_MASSA = 0x24
    const val CMD_GET_SCALE_PAR = 0x75
    const val CMD_ACK_SCALE_PAR = 0x76
    const val CMD_ERROR = 0x28
    const val CMD_NACK = 0xF0

    /** Цена деления из поля Division — во сколько граммов обходится единица массы. */
    private val DIVISION = doubleArrayOf(0.1, 1.0, 10.0, 100.0, 1000.0)

    /**
     * В документации сказано «CRC-16-CCITT», но эта запись покрывает два
     * варианта начального значения. Пробуем оба и запоминаем сработавший.
     */
    val CRC_INITS = intArrayOf(0x0000, 0xFFFF)

    fun crc16(data: ByteArray, from: Int, length: Int, init: Int): Int {
        var crc = init
        for (i in from until from + length) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc
    }

    fun request(command: Int, crcInit: Int): ByteArray {
        val body = byteArrayOf(command.toByte())
        val out = ByteArray(3 + 2 + body.size + 2)
        HEADER.copyInto(out)
        out[3] = (body.size and 0xFF).toByte()          // Len, младший байт первым
        out[4] = ((body.size shr 8) and 0xFF).toByte()
        body.copyInto(out, 5)
        val crc = crc16(out, 5, body.size, crcInit)
        out[5 + body.size] = (crc and 0xFF).toByte()
        out[6 + body.size] = ((crc shr 8) and 0xFF).toByte()
        return out
    }

    /** Разобранный ответ весов. */
    sealed class Reply {
        data class Massa(
            val grams: Double,
            val stable: Boolean,
            val net: Boolean,
            val zero: Boolean,
            val tareGrams: Double?
        ) : Reply()

        data class Params(val text: String) : Reply()
        data class Failed(val code: Int, val what: String) : Reply()
        data class Unknown(val command: Int) : Reply()
    }

    fun parse(body: ByteArray): Reply {
        if (body.isEmpty()) return Reply.Unknown(-1)
        return when (val command = body[0].toInt() and 0xFF) {
            CMD_ACK_MASSA -> parseMassa(body)
            CMD_ACK_SCALE_PAR -> Reply.Params(
                decodeText(body, 1, body.size - 1)
                    .split("\r\n").filter { it.isNotBlank() }.joinToString("\n")
            )
            CMD_ERROR -> {
                val code = if (body.size > 1) body[1].toInt() and 0xFF else 0
                Reply.Failed(code, errorText(code))
            }
            CMD_NACK -> Reply.Failed(CMD_NACK, "весы не поняли команду")
            else -> Reply.Unknown(command)
        }
    }

    /** Весы шлют текст в кириллице; UTF-8 у них не в ходу, отсюда CP1251. */
    private fun decodeText(data: ByteArray, from: Int, length: Int): String = try {
        String(data, from, length, charset("windows-1251"))
    } catch (_: Exception) {
        String(data, from, length, Charsets.ISO_8859_1)
    }

    private fun parseMassa(body: ByteArray): Reply {
        if (body.size < 9) return Reply.Unknown(CMD_ACK_MASSA)
        val weight = int32(body, 1)
        val division = body[5].toInt() and 0xFF
        val step = DIVISION.getOrElse(division) { 1.0 }
        // Поле тары есть не у всех моделей — берём, только если оно пришло
        val tare = if (body.size >= 13) int32(body, 9) * step else null
        return Reply.Massa(
            grams = weight * step,
            stable = body[6].toInt() != 0,
            net = body[7].toInt() != 0,
            zero = body[8].toInt() != 0,
            tareGrams = tare
        )
    }

    private fun int32(data: ByteArray, at: Int): Int =
        (data[at].toInt() and 0xFF) or
            ((data[at + 1].toInt() and 0xFF) shl 8) or
            ((data[at + 2].toInt() and 0xFF) shl 16) or
            (data[at + 3].toInt() shl 24)

    private fun errorText(code: Int) = when (code) {
        0x07 -> "команда не поддерживается"
        0x08 -> "нагрузка больше предела"
        0x09 -> "весы не в режиме взвешивания"
        0x0A -> "ошибка входных данных"
        0x0B -> "ошибка сохранения данных"
        0x10 -> "интерфейс Wi-Fi не поддерживается"
        0x11 -> "интерфейс Ethernet не поддерживается"
        0x15 -> "установка нуля невозможна"
        0x17 -> "нет связи с модулем взвешивания"
        0x18 -> "на платформе был груз при включении"
        0x19 -> "весы неисправны"
        else -> "код 0x%02X".format(code)
    }

    fun hex(data: ByteArray, limit: Int = 64): String =
        data.take(limit).joinToString(" ") { "%02X".format(it) } +
            if (data.size > limit) " …" else ""
}

/** Что вернул один обмен с весами — для показа на экране проверки. */
data class ScaleResult(
    val reply: ScaleProtocol.Reply?,
    val sent: ByteArray,
    val received: ByteArray,
    val crcInit: Int,
    val error: String? = null,
    val millis: Long = 0
)

class ScaleClient(
    private val host: String,
    private val port: Int,
    private val timeoutMs: Int = 3000
) {

    /** Понятный текст вместо имени класса исключения. */
    private fun explain(exc: Exception): String {
        val text = exc.message.orEmpty()
        return when {
            "EHOSTUNREACH" in text || "No route to host" in text ->
                "по адресу $host никто не отвечает. Устройства с таким адресом " +
                    "нет в сети: проверьте, включены ли весы и верен ли адрес"
            "ECONNREFUSED" in text || "Connection refused" in text ->
                "устройство $host есть, но порт $port закрыт. Проверьте номер порта " +
                    "и включён ли обмен по сети в настройках весов"
            "ETIMEDOUT" in text || exc is java.net.SocketTimeoutException ->
                "$host не ответил за $timeoutMs мс. Возможно, весы в другой сети " +
                    "или отвечает не то устройство"
            "ENETUNREACH" in text ->
                "сеть недоступна — проверьте Wi-Fi на телефоне"
            else -> "${exc.javaClass.simpleName}: ${text.ifBlank { "нет ответа" }}"
        }
    }

    fun ask(command: Int, crcInit: Int? = null): ScaleResult {
        val inits = crcInit?.let { intArrayOf(it) } ?: ScaleProtocol.CRC_INITS
        var last: ScaleResult? = null
        for (init in inits) {
            val result = exchange(command, init)
            // Ответ понят — вариант CRC подошёл, дальше не перебираем
            if (result.reply != null && result.reply !is ScaleProtocol.Reply.Unknown) return result
            last = result
        }
        return last!!
    }

    private fun exchange(command: Int, crcInit: Int): ScaleResult {
        val request = ScaleProtocol.request(command, crcInit)
        val started = System.currentTimeMillis()
        try {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.soTimeout = timeoutMs
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                socket.getOutputStream().apply { write(request); flush() }

                val stream = DataInputStream(socket.getInputStream())
                val head = ByteArray(5)
                stream.readFully(head)
                if (head[0] != 0xF8.toByte() || head[1] != 0x55.toByte() ||
                    head[2] != 0xCE.toByte()
                ) {
                    return ScaleResult(null, request, head, crcInit,
                        "ответ не похож на кадр весов", System.currentTimeMillis() - started)
                }
                val length = (head[3].toInt() and 0xFF) or ((head[4].toInt() and 0xFF) shl 8)
                val body = ByteArray(length)
                stream.readFully(body)
                val crc = ByteArray(2)
                stream.readFully(crc)

                val full = head + body + crc
                return ScaleResult(
                    ScaleProtocol.parse(body), request, full, crcInit,
                    millis = System.currentTimeMillis() - started
                )
            }
        } catch (exc: Exception) {
            return ScaleResult(
                null, request, ByteArray(0), crcInit, explain(exc),
                System.currentTimeMillis() - started
            )
        }
    }
}

/**
 * Поиск весов в своей подсети.
 *
 * Адрес весов обычно неизвестен, а гадать дорого: пробегаем все адреса
 * и спрашиваем массу. Кто ответил кадром Масса-К — тот и весы.
 */
object ScaleFinder {

    /** Найденный узел. `scale` — ответил как весы, остальные просто живы. */
    data class Found(val ip: String, val what: String, val scale: Boolean)

    fun scan(
        selfIp: String,
        port: Int,
        onFound: (Found) -> Unit,
        onProgress: (Int, Int) -> Unit,
        stop: () -> Boolean
    ) {
        val prefix = selfIp.substringBeforeLast('.', "")
        if (prefix.isEmpty()) return
        val own = selfIp.substringAfterLast('.').toIntOrNull() ?: -1

        val pool = java.util.concurrent.Executors.newFixedThreadPool(THREADS)
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val total = 254

        for (last in 1..254) {
            pool.execute {
                if (!stop() && last != own) probe("$prefix.$last", port)?.let(onFound)
                onProgress(done.incrementAndGet(), total)
            }
        }
        pool.shutdown()
        pool.awaitTermination(2, java.util.concurrent.TimeUnit.MINUTES)
    }

    /**
     * Проверка одного адреса: коротко стучимся и спрашиваем массу.
     *
     * Отказ в соединении — это тоже находка: значит устройство по адресу
     * есть, просто не слушает наш порт. Без этого при пустом результате
     * непонятно, работает ли поиск вообще.
     */
    private fun probe(ip: String, port: Int): Found? {
        try {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(ip, port), CONNECT_MS)
                socket.soTimeout = READ_MS
                socket.getOutputStream().apply {
                    write(ScaleProtocol.request(ScaleProtocol.CMD_GET_MASSA, 0x0000))
                    flush()
                }
                val head = ByteArray(5)
                if (socket.getInputStream().read(head) < 5) {
                    return Found(ip, "порт открыт, но молчит", false)
                }
                if (head[0] != 0xF8.toByte() || head[1] != 0x55.toByte() ||
                    head[2] != 0xCE.toByte()
                ) return Found(ip, "порт открыт, но это не Масса-К", false)
                return Found(ip, "весы Масса-К", true)
            }
        } catch (exc: Exception) {
            val text = exc.message.orEmpty()
            return if ("ECONNREFUSED" in text || "Connection refused" in text) {
                Found(ip, "устройство есть, порт $port закрыт", false)
            } else {
                null                                    // узла нет либо не ответил
            }
        }
    }

    private const val THREADS = 48
    private const val CONNECT_MS = 400
    private const val READ_MS = 700
}

/** Настройки подключения к весам. */
class ScaleSettings(context: Context) {

    private val prefs = context.getSharedPreferences("scale", Context.MODE_PRIVATE)

    var host: String
        get() = prefs.getString("host", "") ?: ""
        set(value) = prefs.edit().putString("host", value.trim()).apply()

    var port: Int
        get() = prefs.getInt("port", 5001)
        set(value) = prefs.edit().putInt("port", value).apply()

    var timeout: Int
        get() = prefs.getInt("timeout", 3000)
        set(value) = prefs.edit().putInt("timeout", value).apply()

    /** Вариант CRC, который подошёл при проверке; -1 — ещё не определён. */
    var crcInit: Int
        get() = prefs.getInt("crc", -1)
        set(value) = prefs.edit().putInt("crc", value).apply()

    val configured get() = host.isNotBlank()

    fun client() = ScaleClient(host, port, timeout)
}
