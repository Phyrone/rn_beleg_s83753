package me.samuel.beleg_htw.rn.net

import com.google.common.flogger.FluentLogger
import com.google.common.hash.Hashing
import io.ktor.network.sockets.*
import io.ktor.util.cio.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import me.samuel.beleg_htw.rn.toHex

class UDPReceiveServer(
    private val sessions: ShortSessionManagedSocket,
    private val channelProvider: suspend (String, ByteReadChannel) -> Unit,
    private val ignoreHashCheck: Boolean,
    private var sendWindow: Int = 10,
) {

    suspend fun listen() {
        coroutineScope {
            launch { sessions.listen() }
            logger.atInfo().log("Ready...")
            sessions.unsessionedPackets.collect { datagram ->
                launch {
                    runCatching {
                        handleInit(datagram).runJob()
                    }.onFailure {
                        logger.atWarning()
                            .withCause(it)
                            .log("An error occurred while handling an unsession packet (init packet candidate)")
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    private suspend fun handleInit(datagram: Datagram): UDPReceiveTask {
        val packet = datagram.packet

        require(packet.remaining >= 2 + 1 + 5 + 8 + 2 + 4) { "Packet too small" }


        val checksumDataPacket = packet.copy()
        checksumDataPacket.discardExact(3)//drop session id and packet id
        val checksumData = checksumDataPacket.readBytes(checksumDataPacket.remaining.toInt() - 4)
        checksumDataPacket.release()

        val sessionID = packet.readShort()
        require(sessionID !in sessions) { "Session ID already in use" }

        val packetID = packet.readByte()
        require(packetID == 0.toByte()) { "Packet ID must be 0" }

        val startSequenceBytes = packet.readBytes(START_STRING_BYTES.size)
        require(startSequenceBytes.contentEquals(START_STRING_BYTES)) { "Start sequence does not match" }

        val transferSize = packet.readULong()
        require(transferSize > 0u) { "Transfer size must be greater than 0" }

        val transferNameLength = packet.readUShort()
        require(transferNameLength > 0u) { "Transfer name length must be greater than 0" }

        val transferNameBytes = packet.readBytes(transferNameLength.toInt())
        if (transferNameLength > UByte.MAX_VALUE) {
            //TODO replace with logger
            println("Warning: transfer name length is bigger than ${UByte.MAX_VALUE} bytes, wihch is gracefully accepted by the server but not by the specification")
        }
        if (transferSize > Long.MAX_VALUE.toULong()) {
            //TODO replace with logger
            error("ERROR: transfer size is bigger than ${Long.MAX_VALUE} bytes, wich is not supported by the specification amd the server, aborting")
        }

        val transferChecksum = packet.readBytes(4)
        //validate crc32 checksum
        val expectedChecksum = Hashing.crc32().newHasher()
            .putBytes(checksumData)
            .hash().asBytes().reversedArray()


        val hashMatch = expectedChecksum.contentEquals(transferChecksum)

        when {
            hashMatch -> {}
            !ignoreHashCheck -> {
                //TODO replace with logger
                error("ERROR: Hash mismatch, aborting  ${transferChecksum.toHex()}(packet) != ${expectedChecksum.toHex()} (expected)")
            }

            else -> logger.atWarning()
                .log("Hash mismatch, but ignoring it. ${transferChecksum.toHex()}(packet) != ${expectedChecksum.toHex()} (expected)")
        }

        val transferName = transferNameBytes.decodeToString()
        val session = sessions.registerSession(datagram.address, sessionID)
        logger.atInfo().log(
            "Got new Session: id=$sessionID  name=$transferName size=$transferSize crc32=${expectedChecksum.toHex()}"
        )
        return UDPReceiveTask(
            transferName,
            transferSize.toLong(),
            session,
            channelProvider,
            sendWindow
        )
    }


    companion object {
        private val logger = FluentLogger.forEnclosingClass()
    }
}