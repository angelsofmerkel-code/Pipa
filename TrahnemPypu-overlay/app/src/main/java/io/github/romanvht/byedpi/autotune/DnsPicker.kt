package io.github.romanvht.byedpi.autotune

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Random

/**
 * Проверяет DNS-серверы: отвечает ли и возвращает ли для YouTube адреса из диапазонов Google
 * (провайдерская подмена DNS обычно отдаёт заглушку).
 */
object DnsPicker {
    val CANDIDATES = listOf(
        "77.88.8.8", "1.1.1.1", "8.8.8.8", "9.9.9.9", "94.140.14.14", "208.67.222.222",
        "77.88.8.1", "1.0.0.1", "8.8.4.4",
    )
    private val NAMES = listOf("www.youtube.com", "i.ytimg.com")
    private val GOOGLE = listOf(
        "142.250.", "142.251.", "172.217.", "216.58.", "74.125.", "64.233.", "173.194.",
        "209.85.", "108.177.", "172.253.", "192.178.", "216.239.", "66.249.", "142.247.",
    )

    private fun query(server: String, name: String): Pair<List<String>, Long>? {
        return try {
            val id = Random().nextInt(0xFFFF)
            val out = java.io.ByteArrayOutputStream()
            out.write(id shr 8); out.write(id and 0xFF)
            out.write(0x01); out.write(0x00)
            out.write(0); out.write(1); out.write(0); out.write(0); out.write(0); out.write(0); out.write(0); out.write(0)
            for (label in name.split('.')) {
                out.write(label.length)
                out.write(label.toByteArray(Charsets.US_ASCII))
            }
            out.write(0)
            out.write(0); out.write(1); out.write(0); out.write(1)
            val data = out.toByteArray()
            val t0 = System.nanoTime()
            DatagramSocket().use { s ->
                s.soTimeout = 1500
                s.send(DatagramPacket(data, data.size, InetAddress.getByName(server), 53))
                val buf = ByteArray(1500)
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                val ms = (System.nanoTime() - t0) / 1_000_000
                if (((buf[0].toInt() and 0xFF) shl 8 or (buf[1].toInt() and 0xFF)) != id) return null
                parse(buf, p.length) to ms
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun skipName(b: ByteArray, start: Int): Int {
        var i = start
        while (i < b.size) {
            val len = b[i].toInt() and 0xFF
            if (len == 0) return i + 1
            if (len and 0xC0 == 0xC0) return i + 2
            i += len + 1
        }
        return i
    }

    private fun parse(b: ByteArray, length: Int): List<String> {
        val ips = mutableListOf<String>()
        val qd = ((b[4].toInt() and 0xFF) shl 8) or (b[5].toInt() and 0xFF)
        val an = ((b[6].toInt() and 0xFF) shl 8) or (b[7].toInt() and 0xFF)
        var i = 12
        repeat(qd) { i = skipName(b, i) + 4 }
        repeat(an) {
            if (i >= length) return ips
            i = skipName(b, i)
            if (i + 10 > length) return ips
            val type = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
            val rdlen = ((b[i + 8].toInt() and 0xFF) shl 8) or (b[i + 9].toInt() and 0xFF)
            i += 10
            if (type == 1 && rdlen == 4 && i + 4 <= length) {
                ips += (0 until 4).joinToString(".") { (b[i + it].toInt() and 0xFF).toString() }
            }
            i += rdlen
        }
        return ips
    }

    private fun score(server: String): Long? {
        var total = 0L
        for (name in NAMES) {
            val (ips, ms) = query(server, name) ?: return null
            if (ips.none { ip -> GOOGLE.any { ip.startsWith(it) } }) return null
            total += ms
        }
        return total
    }

    /** Возвращает DNS для установки, либо null, если менять не нужно / ничего не подошло. */
    suspend fun pick(current: String): String? = withContext(Dispatchers.IO) {
        if (current.isNotBlank() && score(current) != null) return@withContext null
        CANDIDATES.mapNotNull { s -> score(s)?.let { s to it } }.minByOrNull { it.second }?.first
    }
}
