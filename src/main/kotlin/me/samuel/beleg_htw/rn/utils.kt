package me.samuel.beleg_htw.rn

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import me.samuel.beleg_htw.rn.net.FALLBACK_MTU
import me.samuel.beleg_htw.rn.net.FT_HEADER_SIZE
import me.samuel.beleg_htw.rn.net.IPV6_OVERHEAD
import me.samuel.beleg_htw.rn.net.UDP_OVERHEAD
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.*
import kotlin.random.Random


suspend fun createEmptyWriteChannel() = ByteChannel(true)
suspend fun ByteWriteChannel.dumbWrite(size: Long, blockSize: Int) {
    require(size >= 0) { "Size must be positive" }
    require(blockSize > 0) { "Block size must be positive" }

    (1 until (size / blockSize)).forEach { block ->
        val bytes = ByteArray(blockSize)
        Random.nextBytes(bytes)
        writeFully(bytes)
        flush()
    }
    val rest = (size % 1024).toInt()
    if (rest > 0) {
        val bytes = ByteArray(rest)
        Random.nextBytes(bytes)
        writeFully(bytes)
    }
    flush()
    close()
}

fun inRangeWithOverflow(start: Int, end: Int, overflowValue: Int, value: Int): Boolean {
    require(start in 0..overflowValue)
    require(end in 0..overflowValue)
    require(value in 0..overflowValue)
    return if (start < end) {
        (value in start..end)
    } else {
        (value in start..overflowValue || value in 0..end)
    }
}

fun ByteArray.toHex(): String {
    val sb = StringBuilder()
    for (b in this) {
        sb.append(String.format(Locale.getDefault(), "%02X ", b))
    }
    return sb.toString()
}

fun SocketAddress.getMtu(): Int = (this as? InetSocketAddress)?.let { address ->
    NetworkInterface.getByInetAddress(InetAddress.getByName(address.hostname))?.mtu
} ?: FALLBACK_MTU

fun SocketAddress.getPacketSize(): Int = getMtu() - UDP_OVERHEAD - IPV6_OVERHEAD //ipv6 to be safe
fun SocketAddress.getFragmentSize(): Int = getPacketSize() - FT_HEADER_SIZE

fun setLoggingLevel(level: org.slf4j.event.Level) {
    (LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as? Logger)?.level =
        Level.convertAnSLF4JLevel(level)
}