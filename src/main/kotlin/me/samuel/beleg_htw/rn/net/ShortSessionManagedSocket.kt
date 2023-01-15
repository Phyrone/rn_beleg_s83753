package me.samuel.beleg_htw.rn.net

import io.ktor.network.sockets.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable

class ShortSessionManagedSocket(
    private val socket: BoundDatagramSocket,
) : Closeable {


    private val sharedIncommingPacketFlow = MutableSharedFlow<Datagram>()
    val unsessionedPackets =
        sharedIncommingPacketFlow.filter {
            !sessions.contains(
                it.packet.copy().let {
                    val v = it.readShort()
                    it.release()
                    v
                }
            )
        }

    suspend fun listen() {

        //sharedIncommingPacketFlow.emitAll(socket.incoming)

        while (true) {
            val datagram = socket.receive()
            sharedIncommingPacketFlow.emit(datagram)
        }
        //sharedIncommingPacketFlow.emitAll(socket.incoming)
    }

    private val sessionsLock = Mutex()
    private val sessions = mutableSetOf<Short>()


    private fun createRandomSessionID(): Short {
        if (sessions.size >= UShort.MAX_VALUE.toInt()) {
            throw IllegalStateException("No more sessions available")
        }
        while (true) {
            val candidate = (Short.MIN_VALUE..Short.MAX_VALUE).random().toShort()
            //TODO more efficient way create non colliding session id
            if (sessions.contains(candidate)) continue
            return candidate
        }
    }

    suspend fun warnUnsessionedPackets() {
        unsessionedPackets.collect { datagram ->
            val sessionID = datagram.packet.copy().readShort()
            if (!sessions.contains(sessionID)) {
                //TODO replace with logger
                println("Received unsessioned packet $sessionID <-> ${sessions.joinToString(",", "[", "]")}")
            }
        }
    }

    suspend fun registerSession(address: SocketAddress, knownSessionID: Short? = null): Session {
        val session: Session
        sessionsLock.withLock {
            if (socket.isClosed) throw IllegalStateException("Socket is closed")

            val sessionID = knownSessionID ?: createRandomSessionID()
            session = Session(sessionID, address)
            sessions.add(sessionID)
        }
        return session
    }

    private val nonSessionedPackets = Channel<Triple<Short, SocketAddress, ByteReadPacket>>()

    inner class Session(
        private val sessionID: Short,
        address: SocketAddress,
    ) : Closeable {

        val targetAddress = MutableStateFlow(address)

        //a flow with all packets associated with this session
        private val rawSessionIncommingFlow = sharedIncommingPacketFlow
            .filter { datagram ->
                val packetSessionID = datagram.packet.copy().let { packet ->
                    val v = packet.readShort();
                    packet.release();
                    v
                }
                packetSessionID == this.sessionID
            }

        val receiveFlow = rawSessionIncommingFlow.map { datagram ->
            datagram.also {
                targetAddress.emit(it.address)
                //targetAddress = it.address
            }.packet.copy().also {
                it.readShort()
            }
        }.catch { /* drop packets with error (basicly packets with less than 2 bytes) */ }


        /**
         * send a packet to the partner,
         * session id will prefixed to the packet,
         * address of the oponent is used automatically
         */
        suspend fun send(payload: ByteReadPacket) {
            socket.send(Datagram(buildPacket {
                writeShort(sessionID)
                writePacket(payload)
            }, targetAddress.first()))
        }


        /**
         * close session for receiving sending is still possible but not recommended
         */
        override fun close() {
            runBlocking {
                sessionsLock.withLock {
                    sessions.remove(sessionID)
                }
            }
        }
    }

    operator fun contains(sessionID: Short): Boolean = sessions.contains(sessionID)

    override fun close() {
        runBlocking {
            sessionsLock.withLock {
                //sessions.values.forEach { it.close() }
                sessions.clear()
                nonSessionedPackets.close()
                socket.close()
            }
        }
    }
}