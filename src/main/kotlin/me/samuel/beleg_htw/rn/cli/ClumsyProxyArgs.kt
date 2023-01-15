package me.samuel.beleg_htw.rn.cli

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.samuel.beleg_htw.rn.extra.ClumsyProxy
import me.samuel.beleg_htw.rn.net.ShortSessionManagedSocket
import me.samuel.beleg_htw.rn.setLoggingLevel
import org.slf4j.event.Level
import picocli.CommandLine
import picocli.CommandLine.Command

@Command(
    name = "clumsy-proxy",
    description = [
        "an proxy for the udp file transfer protocol simulating packet loss,delay,reorder and duplication",
        "keep in mind that it uses 2 sessions to forward packets",
        "so the session id the server receiving differs from the one the client sent"
    ]

)
class ClumsyProxyArgs : Runnable {

    @CommandLine.Parameters(index = "0", description = ["The port to listen on"])
    var port: Int = 0

    @CommandLine.Parameters(index = "1", description = ["the host to connect to"])
    lateinit var targetHost: String

    @CommandLine.Parameters(index = "2", description = ["the port to connect to"])
    var targetPort: Int = 0

    @CommandLine.Option(names = ["--host"])
    var host: String? = null

    @CommandLine.Option(
        names = ["--drop-chance"],
        description = ["the chance that a packet will be dropped"],
        defaultValue = "0.1"
    )
    var dropChance = 0.0

    @CommandLine.Option(
        names = ["--duplicate-chance"],
        description = ["the chance that a packet will be duplicated"],
        defaultValue = "0.1"
    )
    var duplicateChance = 0.0

    @CommandLine.Option(
        names = ["--delay"],
        description = ["the delay in ms"],
        defaultValue = "10"
    )
    var delay: Long = 0

    @CommandLine.Option(
        names = ["--jitter"],
        description = ["the jitter in ms", "it should be used when packet intended to be reordered"],
        defaultValue = "2"
    )
    var jitter: Long = 0


    @CommandLine.Option(
        names = ["-l", "--log-level"],
        paramLabel = "<level>",
        defaultValue = "INFO",
    )
    lateinit var level: Level

    override fun run() {
        setLoggingLevel(level)
        val serverAddress = InetSocketAddress(targetHost, targetPort)
        aSocket(ActorSelectorManager(Dispatchers.IO)).udp().bind(InetSocketAddress(host ?: "0.0.0.0", port))
            .use { socket ->
                ShortSessionManagedSocket(socket).use {
                    runBlocking {
                        launch { it.listen() }
                        ClumsyProxy(it, serverAddress, delay, jitter, dropChance, duplicateChance).run()
                    }
                }
            }
    }
}