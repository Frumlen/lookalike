package ru.lookalike

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** Что приложение готово отдать наружу прямо сейчас. */
data class Current(
    val weightGrams: Double? = null,
    val stable: Boolean = false,
    val code: String = "",
    val name: String = "",
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

    fun setProduct(code: String, name: String, score: Float) {
        ref.set(ref.get().copy(code = code, name = name, score = score, at = System.currentTimeMillis()))
    }
}

/** Адрес устройства в локальной сети — его вписывают в настройках 1С. */
fun localIp(): String {
    try {
        for (ni in NetworkInterface.getNetworkInterfaces()) {
            if (ni.isLoopback || !ni.isUp) continue
            for (addr in ni.inetAddresses) {
                if (addr is Inet4Address) return addr.hostAddress ?: ""
            }
        }
    } catch (_: Exception) {
    }
    return ""
}

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
            log("порт $port занять не вышло: ${exc.message}")
            running = false
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

        val body = when (path) {
            "/weight" -> """{"weight_g":${num(now.weightGrams)},"stable":${now.stable}}"""
            "/product" -> """{"code":${str(now.code)},"name":${str(now.name)},"score":${"%.4f".format(now.score)}}"""
            else -> """{"weight_g":${num(now.weightGrams)},"stable":${now.stable},""" +
                """"code":${str(now.code)},"name":${str(now.name)},""" +
                """"score":${"%.4f".format(now.score)},"age_ms":${System.currentTimeMillis() - now.at}}"""
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

    private fun num(value: Double?) = if (value == null) "null" else "%.0f".format(value)

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
