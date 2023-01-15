@file:JvmName("Main")

package me.samuel.beleg_htw.rn

import me.samuel.beleg_htw.rn.cli.ApplicationArgs
import org.fusesource.jansi.AnsiConsole
import picocli.CommandLine
import java.io.File
import java.net.MalformedURLException
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (System.getProperty("jansi.skip")?.toBooleanStrictOrNull() != true)
        AnsiConsole.systemInstall()
    val commandLine = CommandLine(ApplicationArgs::class.java)
    commandLine.isCaseInsensitiveEnumValuesAllowed = true

    try {
        commandLine.commandName = System.getenv("CLI_COMMAND_NAME")
            ?: File(ApplicationArgs::class.java.protectionDomain.codeSource.location.toURI()).name
    } catch (_: MalformedURLException) {
    }

    exitProcess(commandLine.execute(*args))
}