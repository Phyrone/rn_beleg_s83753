package me.samuel.beleg_htw.rn.cli

import picocli.CommandLine.Command


@Command(
    name = "",
    mixinStandardHelpOptions = true,
    version = [
        "1.0.0"
    ],
    subcommands = [
        ServerApplicationArgs::class,
        ClientApplicationArgs::class,
        ClumsyProxyArgs::class
    ]
)
class ApplicationArgs