package me.samuel.beleg_htw.rn.extra

import com.google.common.flogger.FluentLogger
import io.ktor.network.sockets.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.samuel.beleg_htw.rn.net.ShortSessionManagedSocket
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

data class ClumsyProxy(
    val socket: ShortSessionManagedSocket,
    val serverAddress: SocketAddress,
    val delay: Long,
    val jitter: Long,
    val dropChance: Double,
    val duplicateChance: Double
) {


    suspend fun run() = coroutineScope {
        logger.atInfo().log("Ready...")
        socket.unsessionedPackets.collect { datagram ->
            val sessionID = datagram.packet.copy().let { val v = it.readShort(); it.release(); v }
            val clientSession = socket.registerSession(datagram.address, sessionID)
            val serverSession = socket.registerSession(serverAddress)
            launch {
                val timeout = MutableStateFlow(0L)
                launch {
                    delay(1.seconds);
                    timeout.update { it + 1 }
                }
                launch {
                    serverSession.use { serverSession ->
                        timeout.emit(0)
                        launch {
                            clientSession.receiveFlow.collect {
                                handlePacketClumsy(
                                    clientSession,
                                    serverSession,
                                    it, true
                                )
                            }
                        }
                    }
                }
                launch {
                    serverSession.use { serverSession ->
                        timeout.emit(0)
                        launch {
                            serverSession.receiveFlow.collect {
                                handlePacketClumsy(
                                    serverSession,
                                    clientSession,
                                    it, false
                                )
                            }
                        }
                    }
                }
                timeout.first { it >= SESSION_TIMEOUT }
                cancel("Session timed out")
            }
        }

    }

    private fun getLogIdentifier(
        fromSession: ShortSessionManagedSocket.Session,
        toSession: ShortSessionManagedSocket.Session,
        isClientToServer: Boolean
    ): String = if (isClientToServer)
        "${fromSession.targetAddress.value} -> ${toSession.targetAddress.value}"
    else {
        "${fromSession.targetAddress.value} <- ${toSession.targetAddress.value}"
    }


    suspend fun handlePacketClumsy(
        fromSession: ShortSessionManagedSocket.Session,
        toSession: ShortSessionManagedSocket.Session,
        packet: ByteReadPacket,
        clientToServer: Boolean
    ) {
        coroutineScope {
            when (Scenario.get(dropChance, duplicateChance)) {
                Scenario.NONE -> {
                    sendWithDelay(fromSession, toSession, packet, clientToServer)
                }

                Scenario.DUP -> {
                    launch {
                        logger.atInfo().log(
                            "[%s] Duplicating packet",
                            getLogIdentifier(fromSession, toSession, clientToServer)
                        )
                    }

                    val dublicated = packet.copy()
                    coroutineScope {
                        launch { sendWithDelay(fromSession, toSession, packet, clientToServer) }
                        launch { sendWithDelay(fromSession, toSession, dublicated, clientToServer) }
                    }

                }

                Scenario.DROP -> {
                    logger.atInfo().log(
                        "[%s] Dropping packet",
                        getLogIdentifier(fromSession, toSession, clientToServer),
                    )

                }
            }
        }

    }

    private fun getDelay() = (Random.nextDouble(-1.0, 1.0) * jitter + delay)
    private suspend fun sendWithDelay(
        fromSession: ShortSessionManagedSocket.Session,
        toSession: ShortSessionManagedSocket.Session,
        packet: ByteReadPacket,
        clientToServer: Boolean
    ) {
        val delay = getDelay().milliseconds
        logger.atFine().log(
            "[%s] delaying packet by %s",
            getLogIdentifier(fromSession, toSession, clientToServer),
            delay
        )
        delay(delay)
        toSession.send(packet)
    }


    enum class Scenario {
        NONE, DROP, DUP;

        companion object {
            operator fun get(dropChance: Double, duplicateChance: Double): Scenario {
                val random = Math.random()
                return when {
                    random < dropChance -> DROP
                    random < dropChance + duplicateChance -> DUP
                    else -> NONE
                }
            }
        }
    }

    companion object {
        private val logger = FluentLogger.forEnclosingClass()
        const val SESSION_TIMEOUT = 10000L
    }

}
