package me.samuel.beleg_htw.rn.net

import com.google.common.flogger.FluentLogger
import com.google.common.hash.Hasher
import com.google.common.hash.Hashing
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.samuel.beleg_htw.rn.getFragmentSize
import me.samuel.beleg_htw.rn.toHex
import java.io.Closeable
import kotlin.math.pow
import kotlin.system.exitProcess
import kotlin.system.measureTimeMillis
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class UdpSendTask(
    private val session: ShortSessionManagedSocket.Session,
    private val fileName: String,
    private val finalSize: Long,
    private val fileReadChannel: ByteReadChannel,
    private val allowExtend: Boolean = false,
    maxWindowSize: Int = Short.MAX_VALUE.toInt(), /* set to 1 for stop and wait */
) : Closeable {

    private val maxWindowSizeLimit: Int = maxWindowSize.coerceIn(1..Short.MAX_VALUE.toInt())

    private val roundTripTime = MutableStateFlow(RoudTripTime(arrayOf(BASE_TIMEOUT)))

    data class RoudTripTime(
        val history: Array<Long>
    ) {
        val average = history.average().toLong()
        operator fun plus(time: Long) =
            RoudTripTime(history.let {
                if (it.size > HISTORY_SIZE)
                    it.copyOfRange(it.size - HISTORY_SIZE, it.size)
                else it
            } + time)

        companion object {
            private const val HISTORY_SIZE = 60
        }
    }

    private suspend fun sendHello() {
        val fileNameBytes = fileName.toByteArray(Charsets.UTF_8)
        if (allowExtend)
            require(fileNameBytes.size <= UShort.MAX_VALUE.toInt()) { "Filename too long (max ${UShort.MAX_VALUE} bytes but is ${fileNameBytes.size})" }
        else
            require(fileNameBytes.size <= Short.MAX_VALUE.toInt()) { "Filename too long (max ${Short.MAX_VALUE} bytes but is ${fileNameBytes.size})" }


        val packetWithoutChecksum = buildPacket {
            writeFully(START_STRING_BYTES)
            writeLong(finalSize)
            writeUShort(fileNameBytes.size.toUShort())
            writeFully(fileNameBytes)
        }
        val checksum = Hashing.crc32()
            .hashBytes(packetWithoutChecksum.copy().let { val b = it.readBytes();it.release();b })
            .asBytes().reversedArray()

        assert(checksum.size == 4)

        val packetWithChecksum = buildPacket {
            writePacket(packetWithoutChecksum)
            writeFully(checksum)
        }

        sendSafe(packetWithChecksum, nextSendId())
    }

    private suspend fun sendLast(crcHasher: Hasher) {
        val checksum = crcHasher.hash().asBytes().reversedArray()
        assert(checksum.size == 4) { "crcHash must be 4 bytes long" }

        receiveFinalAck = true
        sendSafe(buildPacket {
            writeFully(checksum)
        }, nextSendId())

        val crc = withTimeout(10.seconds) { lastCRC32.first { it.isNotEmpty() } }
        if (checksum.contentEquals(crc))
            logger.atInfo().log("CRC32 Checksum matches")
        else {
            logger
                .atSevere()
                .log(
                    "CRC32 Checksum does not match file courrupted! %s (expected) != %s (received)",
                    checksum.toHex(),
                    crc.toHex()
                )
            exitProcess(1)
        }

    }

    suspend fun runJob() {
        var fragmentSize: Int = FALLBACK_MTU - UDP_OVERHEAD - IPV6_OVERHEAD - FT_HEADER_SIZE

        val progressState = MutableStateFlow(0L)

        coroutineScope {

            val jobs = buildSet {
                add(launch {
                    sendWindowState.collect { status ->
                        logger.atFine().log("%s", status)
                    }
                })
                add(launch { runReceiver() })
                add(launch {
                    session.targetAddress.collect {
                        fragmentSize = it.getFragmentSize()
                    }
                })

            }.toMutableSet()
            try {
                logger.atInfo().log("Connecting...")
                sendHello()
                logger.atInfo().log("Connected")
                jobs.add(launch {
                    try {
                        while (true) {
                            logger.atInfo().log(
                                "Progress: %d/%d (%.2f%%)",
                                progressState.value,
                                finalSize,
                                progressState.value.toDouble() / finalSize * 100
                            )
                            delay(500)
                        }
                    } finally {
                        logger.atInfo().log(
                            "Progress: %d/%d (%.2f%%)",
                            progressState.value,
                            finalSize,
                            progressState.value.toDouble() / finalSize * 100
                        )
                    }
                })
                var fileHash = Hashing.crc32().newHasher()

                val sendTasks = mutableSetOf<Job>()
                var bytesInSend: Long = 0
                while (bytesInSend < finalSize) {
                    val fragment = fileReadChannel.readRemaining(fragmentSize.toLong()).readBytes()
                    val packetID = nextSendId()
                    //suspend when window is full
                    sendWindowState.first { it.canSend(packetID) }
                    sendWindowState.update { it.copy(send = packetID) }
                    bytesInSend += fragment.size
                    //currentSendWindow.first { currentWindowSize -> currentWindowSize <= maxWindowSize }
                    //currentSendWindow.update { it + 1 }
                    fileHash = fileHash.putBytes(fragment)
                    sendTasks.add(launch {
                        sendFragment(packetID, fragment)
                        progressState.update { it + fragment.size }
                    })
                }
                logger.atFine().log("Waiting for Upload to finish...")
                sendTasks.joinAll()
                sendLast(fileHash)
            } finally {
                jobs.forEach { it.cancelAndJoin() }
            }

        }
    }

    private var receiveFinalAck = false
    private val lastCRC32 = MutableStateFlow(byteArrayOf())
    private suspend fun runReceiver(): Nothing {
        session.receiveFlow.collect { packet ->
            val packetID = packet.readByte().toUByte()
            val currentMaxWindowSize = packet.readByte().toUByte().toInt()
            if (receiveFinalAck) {
                val crc = packet.readBytes(Int.SIZE_BYTES)
                if (!crc.contentEquals(byteArrayOf(0, 0, 0, 0))) {
                    lastCRC32.emit(crc)
                    logger.atFiner().log(
                        "Received ack for %s with window size %s and crc %s",
                        packetID,
                        currentMaxWindowSize,
                        crc.toHex()
                    )
                } else {
                    logger.atWarning().log(
                        "expected an crc hash with ack packet %s  but got an empty one",
                        packetID,
                    )
                }
            }
            logger.atFiner().log("Received ack for %s with window size %s", packetID, currentMaxWindowSize)

            updateACK(packetID, currentMaxWindowSize)
        }
        error("Receiver job should never end")
    }


    private suspend fun sendFragment(packetID: Int, fragment: ByteArray) {
        sendSafe(buildPacket { writeFully(fragment) }, packetID)
    }

    private var cid = 0;
    private val cidLock = Mutex()
    suspend fun nextSendId(): Int = cidLock.withLock {
        val ret = cid
        cid = (cid + 1) % PACKET_ID_OVERFLOW
        ret
    }


    private suspend fun sendSafe(payload: ByteReadPacket, packetID: Int) {
        val packet = buildPacket {
            writeByte(packetID.toByte())
            writePacket(payload)
        }

        for (attempt in 0 until MAX_RESEND_ATTEMPTS) {
            val bTimeout = roundTripTime.value.average * 2

            val timeout = bTimeout * (TIMEOUT_MULTIPLIER.pow(attempt.toDouble()).toLong())
            session.send(packet.copy())
            logger.atFine().log(
                "%d: Sent packet with timeout %s (%d of $MAX_RESEND_ATTEMPTS)",
                packetID,
                timeout.milliseconds,
                attempt
            )
            val millis = withTimeoutOrNull(timeout) {
                measureTimeMillis {
                    waitForNAck(packetID)
                    logger.atFine().log(
                        "%d: Packet Sent succesfuly (%d of $MAX_RESEND_ATTEMPTS)",
                        packetID,
                        attempt
                    )
                }
            }
            if (millis != null) {
                roundTripTime.update { it + millis }
                return
            } else {
                logger.atFine()
                    .log("%d: Timeout %s reached (%d of $MAX_RESEND_ATTEMPTS)", packetID, timeout.milliseconds, attempt)
            }

        }
        handleFullTimeout()
    }

    //contains the range of send packets that are not acked yet
    private val sendWindowState = MutableStateFlow(SendWindowStatus(0, -1, 1))

    private fun handleFullTimeout(): Nothing {
        logger.atSevere().log("Full timeout")
        exitProcess(1)
    }

    private suspend fun waitForNAck(packetID: Int) {
        sendWindowState.first { status ->
            status.hashAckReached(packetID)
        }
    }

    private fun updateACK(packetID: UByte, maxWindowSize: Int) {
        sendWindowState.update { it.tryUpdateAck(packetID.toInt(), maxWindowSize.coerceIn(1..maxWindowSizeLimit)) }
    }

    override fun close() {
        session.close()
    }

    companion object {
        private val logger = FluentLogger.forEnclosingClass()
    }

}


//represents a range of packets that are send but not ackeknoleged yet as well as its maximum size
private data class SendWindowStatus(val send: Int, val ack: Int, val currentMaxWindowSize: Int) {
    val windowSizeSatisfied = size() <= currentMaxWindowSize

    fun hashAckReached(checkAck: Int): Boolean =
        checkAck !in this || ack == checkAck //in ack..send -> means window still open at this position

    /**
     * @return true when packetid is next in line
     */
    fun canSend(packetID: Int): Boolean =
        ((send + 1) % PACKET_ID_OVERFLOW) == packetID && windowSizeSatisfied


    private fun hasOverflowBreak() = send < ack


    fun tryUpdateAck(newAckID: Int, currentMaxWindowSize: Int) =
        if (newAckID in this) /*ack id must be in window or drop*/ {
            val newAcceptedAck = if (hasOverflowBreak()) {
                when (newAckID) {
                    in (ack until PACKET_ID_OVERFLOW) -> if (newAckID > ack) newAckID else null
                    else -> newAckID
                }
            } else newAckID.coerceAtLeast(ack)
            if (newAcceptedAck != null) copy(
                ack = newAcceptedAck,
                currentMaxWindowSize = currentMaxWindowSize
            ) else this
        } else this

    fun size() = if (hasOverflowBreak()) {
        (send - ack) + PACKET_ID_OVERFLOW
    } else send - ack

    operator fun contains(packetID: Int) = when {
        hasOverflowBreak() -> packetID in ack until PACKET_ID_OVERFLOW || packetID in 0..send
        else -> packetID in ack..send
        //send > ack -> packetID in ack..send
        //send < ack -> packetID in ack..PACKET_ID_OVERFLOW || packetID in 0..send
        //else -> packetID == send
    }
}