package me.samuel.beleg_htw.rn.cli

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.util.cio.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.samuel.beleg_htw.rn.net.ShortSessionManagedSocket
import me.samuel.beleg_htw.rn.net.UDPReceiveServer
import me.samuel.beleg_htw.rn.setLoggingLevel
import org.slf4j.event.Level
import picocli.CommandLine.*
import java.io.File


@Command(
    name = "server"
)
class ServerApplicationArgs : Runnable {

    @Parameters(index = "0", description = ["The port to listen on"])
    var port: Int = 0

    @Option(names = ["--host"])
    var host: String? = null

    @Option(names = ["--dry-run"], description = ["received data will droped and not written to file"])
    var dryRun = false

    @Option(names = ["--root-dir"], description = [])
    var rootDir = File(".")

    @Option(names = ["--send-window-size"])
    var sendWindow = 10

    @Option(
        names = ["-l", "--log-level"],
        paramLabel = "<level>",
        defaultValue = "INFO",
    )
    lateinit var level: Level

    override fun run() {
        setLoggingLevel(level)
        val localAddress = InetSocketAddress(host ?: "0.0.0.0", port)

        val channel: suspend (String, ByteReadChannel) -> Unit = when {
            dryRun -> { _, readChannel ->
                while (readChannel.isClosedForRead.not()) {
                    readChannel.discard(Long.MAX_VALUE)
                }
            }

            else -> { name, readChannel ->
                val tempFile = File.createTempFile("ft-receiving", name)
                tempFile.writeChannel().use {
                    readChannel.copyTo(this)
                }
                tempFile.delete()
                tempFile.renameTo(File(rootDir, name))
            }
        }


        aSocket(ActorSelectorManager(Dispatchers.IO)).udp().bind(localAddress).use { socket ->
            runBlocking {
                val sessionManagedDatagrammSocket = ShortSessionManagedSocket(socket)
                val server =
                    UDPReceiveServer(sessionManagedDatagrammSocket, channel, false, sendWindow)
                server.listen()
            }
        }
    }
}