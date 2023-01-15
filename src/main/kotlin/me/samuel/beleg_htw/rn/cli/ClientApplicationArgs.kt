package me.samuel.beleg_htw.rn.cli

import com.google.common.flogger.FluentLogger
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.util.cio.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.samuel.beleg_htw.rn.createEmptyWriteChannel
import me.samuel.beleg_htw.rn.dumbWrite
import me.samuel.beleg_htw.rn.getFragmentSize
import me.samuel.beleg_htw.rn.net.ShortSessionManagedSocket
import me.samuel.beleg_htw.rn.net.UdpSendTask
import me.samuel.beleg_htw.rn.setLoggingLevel
import org.slf4j.event.Level
import picocli.CommandLine
import picocli.CommandLine.*
import java.io.File
import kotlin.system.exitProcess

@Command(
    name = "client"
)
class ClientApplicationArgs : Runnable {


    @Parameters(
        index = "0", paramLabel = "<host>"
    )
    lateinit var targetHost: String

    @Parameters(
        index = "1", paramLabel = "<port>",
    )
    var targetPort: Int = 0

    @Parameters(
        index = "2",
        paramLabel = "<file>",
        defaultValue = ""
    )
    lateinit var fileToSendString: String

    @Parameters(
        index = "3",
        paramLabel = "[sendmode]",
        defaultValue = "gbn",
        showDefaultValue = CommandLine.Help.Visibility.ALWAYS,
    )
    lateinit var sendMode: SendMode

    @Option(
        names = ["--unsigned-extended"],
    )
    var signExtededMode = false //use unsigned limits

    @Option(
        names = ["--session-id"]
    )
    var forceSessionID: Short? = null

    @Option(
        names = ["--dry-run"],
        paramLabel = "<size>"
    )
    var dumbBytes: Long? = null

    @Option(
        names = ["-l", "--log-level"],
        paramLabel = "<level>",
        defaultValue = "INFO",
    )
    lateinit var level: Level
    override fun run() {
        setLoggingLevel(level)
        runBlocking {

            val fileToSend = fileToSendString
                .takeUnless { it.isBlank() }
                ?.let { File(it) }
                ?.takeIf { it.exists() && it.isFile }

            val fillDumb = dumbBytes != null
            val tranactionName = fileToSend?.name
                ?: fileToSendString.takeUnless { it.isBlank() }
                ?: "dummy-${(Long.MIN_VALUE..Long.MAX_VALUE).random()}"

            if (!tranactionName.matches(FILE_NAME_REGEX)) {
                logger.atSevere().log("Invalid file name: $tranactionName (must match ${FILE_NAME_REGEX.pattern})")
                exitProcess(1)
            }

            val tranactionSize = dumbBytes ?: fileToSend?.length() ?: 0

            val channel = when {
                dumbBytes != null -> createEmptyWriteChannel()
                fileToSend != null -> fileToSend.readChannel()
                else -> {
                    error("file not found or not a file")
                }
            }

            if (targetPort !in 1..65535) {
                println("Port must be in range 1-65535")
                exitProcess(1)
            }
            val address = try {
                InetSocketAddress(targetHost, targetPort)
            } catch (e: Exception) {
                println("Invalid host or port")
                exitProcess(1)
            }
            val fragmentSize = address.getFragmentSize()
            aSocket(ActorSelectorManager(Dispatchers.IO)).udp().bind().use { datagramSocket ->
                ShortSessionManagedSocket(datagramSocket).use { sessionSocket ->
                    val jobs = buildSet {
                        add(launch { sessionSocket.listen() })
                        add(launch { sessionSocket.warnUnsessionedPackets() })
                        if (fillDumb) {
                            add(launch { (channel as? ByteWriteChannel)?.dumbWrite(tranactionSize, fragmentSize) })
                        }
                    }

                    logger.atInfo().log(
                        "expect to transfer %d fragments with an size each %d bytes",
                        (tranactionSize / fragmentSize) + (if (tranactionSize % fragmentSize == 0L) 0 else 1),
                        fragmentSize
                    )


                    sessionSocket.registerSession(address, forceSessionID).use { session ->
                        val sendTask = UdpSendTask(
                            session,
                            tranactionName,
                            tranactionSize,
                            channel,
                            signExtededMode,
                            (if (sendMode == SendMode.GBN) Short.MAX_VALUE.toInt() else 1)
                        )
                        sendTask.runJob()
                    }
                    jobs.forEach { it.cancelAndJoin() }
                }
            }
        }
    }

    companion object {
        private val logger = FluentLogger.forEnclosingClass()
        private val FILE_NAME_REGEX = Regex("[a-zA-ZäöüßÄÖÜ0-9_\\\\.\\-\\[\\]]+")
    }
}