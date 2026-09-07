package ru.lookalike

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** Что приложение готово отдать наружу прямо сейчас. */
data class Current(
    val weightGrams: Double? = null,
    val stable: Boolean = false,
    val name: String = "",
    /** Номер товара на весах: из него собирается весовой штрихкод. */
    val plu: String = "",
    /** Обычный штрихкод товара, если он задан. */
    val barcode: String = "",
    val score: Float = 0f,
    val at: Long = System.currentTimeMillis()
)

/** Общее состояние: сюда пишут экраны, отсюда читают сервера. */
object State {
    private val ref = AtomicReference(Current())

    val current: Current get() = ref.get()

    fun setWeight(grams: Double?, stable: Boolean) {
        ref.set(ref.get().copy(weightGrams = grams, stable = stable, at = System.currentTimeMillis()))
    }

    fun setProduct(name: String, info: ProductInfo, score: Float) {
        ref.set(
            ref.get().copy(
                name = name, plu = info.plu, barcode = info.barcode,
                score = score, at = System.currentTimeMillis()
            )
        )
    }
}

/** Один адрес устройства: имя интерфейса и IPv4. */
data class NetAddress(val iface: String, val ip: String) {
    /** Wi-Fi и Ethernet годятся, мобильный интернет — нет: касса до него не достучится. */
    val local: Boolean
        get() = iface.startsWith("wlan") || iface.startsWith("eth") || iface.startsWith("ap")
}

/** Все адреса устройства, начиная с пригодных для локальной сети. */
fun localAddresses(): List<NetAddress> {
    val found = ArrayList<NetAddress>()
    try {
        for (ni in NetworkInterface.getNetworkInterfaces()) {
            if (ni.isLoopback || !ni.isUp) continue
            for (addr in ni.inetAddresses) {
                if (addr is Inet4Address) {
                    found.add(NetAddress(ni.name, addr.hostAddress ?: ""))
                }
            }
        }
    } catch (_: Exception) {
    }
    return found.sortedByDescending { it.local }
}

/**
 * Адрес устройства в локальной сети — его вписывают в настройках 1С.
 *
 * Именно локальной: если телефон сидит в мобильном интернете, его адрес
 * вида 10.x принадлежит сети оператора, и касса до него не достучится.
 */
fun localIp(): String = localAddresses().firstOrNull { it.local }?.ip ?: ""

/**
 * Два входа для внешних систем, работают одновременно.
 *
 * 1. HTTP — отдаёт вес и распознанный товар вместе, одним ответом.
 *    Читается из 1С обычным HTTP-запросом, годится и для проверки браузером.
 *
 * 2. TCP по «Протоколу 100» — приложение отвечает как весы Масса-К.
 *    Штатный драйвер весов в 1С подключается к нему без переделок,
 *    но получает только массу: штрихкод этот протокол не передаёт.
 */
class LocalServer(
    private val httpPort: Int,
    private val scalePort: Int,
    private val barcodes: BarcodeSettings,
    private val log: (String) -> Unit
) {

    private val pool = Executors.newCachedThreadPool()
    private var http: ServerSocket? = null
    private var scale: ServerSocket? = null

    @Volatile
    var running = false
        private set

    fun start() {
        if (running) return
        running = true
        pool.execute { listen(httpPort, ::serveHttp) { http = it } }
        pool.execute { listen(scalePort, ::serveScale) { scale = it } }
    }

    fun stop() {
        running = false
        runCatching { http?.close() }
        runCatching { scale?.close() }
        http = null
        scale = null
        log("остановлен")
    }

    private fun listen(port: Int, handle: (Socket) -> Unit, keep: (ServerSocket) -> Unit) {
        try {
            ServerSocket(port).use { server ->
                keep(server)
                log("слушаю порт $port")
                while (running) {
                    val socket = try {
                        server.accept()
                    } catch (_: Exception) {
                        break
                    }
                    pool.execute {
                        try {
                            handle(socket)
                        } catch (exc: Exception) {
                            log("порт $port: ${exc.javaClass.simpleName}")
                        } finally {
                            runCatching { socket.close() }
                        }
                    }
                }
            }
        } catch (exc: Exception) {
            // Гасим только этот порт: второй должен работать дальше,
            // иначе занятый HTTP уронил бы и связь с кассой по весам
            log("порт $port занять не вышло: ${exc.message}")
        }
    }

    // ------------------------------------------------------------- HTTP

    private fun serveHttp(socket: Socket) {
        socket.soTimeout = 5000
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        val line = reader.readLine() ?: return
        val path = line.split(' ').getOrNull(1)?.substringBefore('?') ?: "/"
        val now = State.current
        log("HTTP $path")

        // Весовой штрихкод собирается на лету: в нём зашита текущая масса
        val grams = (now.weightGrams ?: 0.0).toInt()
        val weightBarcode = barcodes.build(now.plu, grams)
        val product = """"name":${str(now.name)},"plu":${str(now.plu)},""" +
            """"barcode":${str(now.barcode)},"weight_barcode":${str(weightBarcode)},""" +
            """"score":${dec(now.score)}"""

        val body = when (path) {
            "/weight" -> """{"weight_g":${num(now.weightGrams)},"stable":${now.stable}}"""
            "/product" -> "{$product}"
            else -> """{"weight_g":${num(now.weightGrams)},"stable":${now.stable},""" +
                "$product," +
                """"age_ms":${System.currentTimeMillis() - now.at}}"""
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        socket.getOutputStream().apply {
            write(
                ("HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/json; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n\r\n").toByteArray()
            )
            write(bytes)
            flush()
        }
    }

    /**
     * Числа для JSON форматируем в английской локали. На русской настройке
     * телефона дробный разделитель — запятая, и JSON получается битым.
     */
    private fun num(value: Double?) =
        if (value == null) "null" else String.format(Locale.US, "%.0f", value)

    private fun dec(value: Float) = String.format(Locale.US, "%.4f", value)

    private fun str(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    // ------------------------------------------- эмуляция весов Масса-К

    private fun serveScale(socket: Socket) {
        socket.soTimeout = 30000
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        while (running && !socket.isClosed) {
            val head = ByteArray(5)
            var read = 0
            while (read < 5) {
                val n = input.read(head, read, 5 - read)
                if (n < 0) return
                read += n
            }
            if (head[0] != 0xF8.toByte() || head[1] != 0x55.toByte() ||
                head[2] != 0xCE.toByte()
            ) return

            val length = (head[3].toInt() and 0xFF) or ((head[4].toInt() and 0xFF) shl 8)
            val body = ByteArray(length)
            read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n < 0) return
                read += n
            }
            input.skip(2)                                   // CRC запроса не проверяем

            val command = body.firstOrNull()?.toInt()?.and(0xFF) ?: return
            log("весы: команда 0x%02X".format(command))
            output.write(answer(command))
            output.flush()
        }
    }

    private fun answer(command: Int): ByteArray = when (command) {
        ScaleProtocol.CMD_GET_MASSA -> {
            val now = State.current
            val grams = (now.weightGrams ?: 0.0).toInt()
            // Цена деления 1 г — тогда поле массы это прямо граммы
            val body = byteArrayOf(
                ScaleProtocol.CMD_ACK_MASSA.toByte(),
                (grams and 0xFF).toByte(),
                ((grams shr 8) and 0xFF).toByte(),
                ((grams shr 16) and 0xFF).toByte(),
                ((grams shr 24) and 0xFF).toByte(),
                1,                                          // Division: 1 г
                if (now.stable) 1 else 0,                   // Stable
                0,                                          // Net
                if (grams == 0) 1 else 0                    // Zero
            )
            frame(body)
        }
        else -> frame(byteArrayOf(ScaleProtocol.CMD_NACK.toByte()))
    }

    private fun frame(body: ByteArray): ByteArray {
        val out = ByteArray(3 + 2 + body.size + 2)
        out[0] = 0xF8.toByte(); out[1] = 0x55; out[2] = 0xCE.toByte()
        out[3] = (body.size and 0xFF).toByte()
        out[4] = ((body.size shr 8) and 0xFF).toByte()
        body.copyInto(out, 5)
        val crc = ScaleProtocol.crc16(out, 5, body.size, 0x0000)
        out[5 + body.size] = (crc and 0xFF).toByte()
        out[6 + body.size] = ((crc shr 8) and 0xFF).toByte()
        return out
    }
}
