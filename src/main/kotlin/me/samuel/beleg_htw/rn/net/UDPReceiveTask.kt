package me.samuel.beleg_htw.rn.net

import com.google.common.flogger.FluentLogger
import com.google.common.hash.Hashing
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.samuel.beleg_htw.rn.inRangeWithOverflow
import me.samuel.beleg_htw.rn.toHex
import kotlin.time.Duration.Companion.seconds

class UDPReceiveTask(
    val transferName: String,
    val tranferSize: Long,
    val session: ShortSessionManagedSocket.Session,
    private val writeTask: suspend (String, ByteReadChannel) -> Unit,
    sendWindow: Int
) {


    private val sendWindow = sendWindow.coerceIn(1..128)
    private val sendWindowUByte = sendWindow.toUByte()
    private val transferHasher = Hashing.crc32().newHasher()
    private val windowLock = Mutex()
    private var packetWindState = WindState(1, this.sendWindow, 64)

    val channel = ByteChannel(true)

    //even statflows are threadsafe this prevents from dropping packets when removeing from packetmap
    private val statefulPacketMap = MutableStateFlow(mapOf<Int, ByteReadPacket>())
    suspend fun sendAck(int: Int, crc32: ByteArray? = null) {
        require(int in 0..UByte.MAX_VALUE.toInt())
        session.send(BytePacketBuilder().also { builder ->
            builder.writeUByte(int.toUByte())
            builder.writeUByte(sendWindowUByte)
            if (crc32 != null) builder.writeFully(crc32)
            else builder.writeFully(ByteArray(4))

        }.build())
    }

    suspend fun runJob() {
        logger.atInfo().log("Start receiving...")
        sendAck(0)
        coroutineScope {
            launch { writeTask.invoke(transferName, channel) }

            val dataReceiveTask = launch {
                logger.atFine().log("Start packet collector...")
                session.receiveFlow.collect { datagram ->
                    launch {
                        val packetID = datagram.readUByte().toInt()
                        logger.atFine().log("Received packet $packetID...")
                        windowLock.withLock {
                            when {
                                packetWindState.doOrder(packetID) -> {
                                    logger.atFine().log("Packet $packetID will be ordered soon")
                                    statefulPacketMap.update { it + (packetID to datagram) }
                                }

                                !packetWindState.dropAck(packetID) -> {
                                    logger.atFine().log("Packet $packetID out if window -> ack and drop")
                                    sendAck(packetID)

                                }

                                else -> logger.atWarning()
                                    /*this strategy exists in case of a bug in client or violation of the protocol
                                     * in which the client sends more packets than the window size
                                     * this clould lead to the acknolage of a packet
                                     */
                                    .log("Packet $packetID out of window and in cleanup zone -> drop and no ack")
                            }
                        }
                    }
                }
            }
            //acknowledge first packet

            var size = 0L
            suspend fun receiveNext(): Pair<Int, ByteArray> {
                val pos = windowLock.withLock { packetWindState.ackN }
                statefulPacketMap.first { it.containsKey(pos) }
                val snapShot = statefulPacketMap.getAndUpdate { it - pos }
                val data = snapShot.getValue(pos).readBytes()
                windowLock.withLock {
                    packetWindState = packetWindState.copy(ackN = (pos + 1) % PACKET_ID_OVERFLOW)
                }
                size += data.size
                return Pair(pos, data)
            }


            try {
                do {
                    val (pos, data) = withTimeout(10.seconds) { receiveNext() }
                    logger.atFine().log("Got Data id=$pos size=${data.size}")

                    launch { sendAck(pos) }
                    writeOut(data)

                } while (size < tranferSize)
                logger.atFine().log("Got all data -> waiting for final packet")
                val (lastPacketID, finalChecksum) = receiveNext()

                //TODO check checksum
                val expetedChecksum = transferHasher.hash().asBytes().reversedArray()

                if (finalChecksum.contentEquals(expetedChecksum)) {
                    logger.atInfo().log("Checksum matches ")
                } else {
                    logger.atWarning()
                        .log("Checksum does not match ${finalChecksum.toHex()} != ${expetedChecksum.toHex()}")
                }
                launch { sendAck(lastPacketID, expetedChecksum) }
                windowLock.withLock { packetWindState = WindState(-1, 0, 0) }
                logger.atFine().log("transfer complete waiting another 10 seconds in case last ack is lost")
                delay(10.seconds)
            } catch (e: Exception) {
                logger.atSevere().withCause(e).log("Error while receiving")
            } finally {
                session.close()
                channel.close()
                dataReceiveTask.cancelAndJoin()
                logger.atInfo().log("Session closed")
            }
        }
    }

    private suspend fun writeOut(bytes: ByteArray) {
        transferHasher.putBytes(bytes)
        channel.writeFully(bytes)
    }

    companion object {
        val logger = FluentLogger.forEnclosingClass()
    }
}

data class WindState(
    val ackN: Int,
    val windowSize: Int,
    val dropOffset: Int,
) {
    val dropAckN = (ackN + dropOffset) % PACKET_ID_OVERFLOW
    val maxWindowN: Int = (ackN + windowSize) % PACKET_ID_OVERFLOW

    fun isNext(packetID: Int) = packetID == (ackN + 1) % PACKET_ID_OVERFLOW
    fun doOrder(packetID: Int): Boolean = inRangeWithOverflow(ackN, maxWindowN, PACKET_ID_OVERFLOW, packetID)
    fun dropAck(packetID: Int): Boolean = inRangeWithOverflow(maxWindowN, dropAckN, PACKET_ID_OVERFLOW, packetID)

}