# Beleg Renchernernetze

Es sollten alle geforderten features implementiert sein.

## Features

- [X] Stop and Wait
- [X] Go Back N
- [X] Fortschrittsanzeige
- [X] Anpassung an RundTripTime
- [X] Unterstützung von Megereren verbindungen gleichzeitig (server)
- [X] Anpassung der Packetgrößen (nur Dateifragmente) an die MTU
- [X] Zusatzproxy im Netzwerkfehler zu simulieren
- [X] Überprüfung der Eingaben
- [X] Direktes verwerfen von Packeten (testübertragung)
- [X] Überlauf der Sequenznummer (nicht explizit getestet aber konnte dateien mit mehr aus 100mb fehlerfrei übertragen)
- [X] Crc32 Prüfung (start und ende)

## Tests

- [X] Funktion Ihres Clients + Server ohne Fehlersimulation
- [X] Funktion Ihres Clients + Server mit Fehlersimulation
- [ ] Funktion Ihres Clients + Server über Hochschulproxy (server nicht erreichbar erwarte jedoch funktion)
- [X] Funktion Ihres Clients + Hochschulserver ohne Fehlersimulation
- [ ] Funktion Ihres Clients + Hochschulserver mit Fehlersimulation (server nicht erreichbar erwarte jedoch funktion)

## Bekannte Probleme und Verbesserungsideen (optional)

habe ich aus zeitgründen nicht weiter bearbeitet sind aber nach meiner einschätzung nicht relevant

- [ ] wenn proxy scheint packete fehlerhaft zu beantworten (feature :-) )
- [ ] server hat keine fortschrittsanzeige
- [ ] einige implementierungen vorallem clientseitig haben verbesserungspotential
- [ ] Server passt sein delay nicht an die aktuelle routrip time an statdessen erhöht er die verzoegerung um bei jedem
  fehler um einen festgelegten faktor

# Anmerkungen zum Protokoll

1. Das Protokoll unterstützt keinen abruch des transfers abgesehen von einem timeout
2. Die PaketID sollte u.u. 16bit betragen um häufige überläufe zu verhindern und damit fehlerpotenzial (relativ
   unwichtig)

## Verbersserungsvorschläge

1. 2 packet ids reservieren 1 für init (z.b. 0) und 1 für abbruch (z.b. 255 bzw. 65535)
2. für dateiztansfer kann es auch sinn machen compression zu unterstüzen indem man z.b. die datei erst duch einen
   Deflate / Inflate stream sendet
3.

## Verwendet Bliliotheken

| Bibliothek                | Webseite                                      | Verwendungszweck                                                                                                                                                                    | Lizens     |
|---------------------------|-----------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------|
| PicoCLI                   | https://picocli.info/                         | habe ich benutzt um die startargumente<br/> angenehmer zu handhaben                                                                                                                 | Apache 2.0 |
| JANSI                     | https://github.com/fusesource/jansi           | Sichert compatibilität zu ANSI auf z.b. PowerShell (aktuell nur Picoli)                                                                                                             | Apache 2.0 |
| Ktor Networking           | https://ktor.io/docs/servers-raw-sockets.html | Anstelle von JavaIO da diese Coroutines unterstützt                                                                                                                                 | Apache 2.0 |
| Google Flogger            | https://github.com/google/flogger             | Logging API                                                                                                                                                                         | Apache 2.0 |
| Logback                   | https://logback.qos.ch/                       | Logging Implementation                                                                                                                                                              | MIT        |
| guava                     | https://github.com/google/guava               | Habe den CRC32 hash von Guava Benutzt                                                                                                                                               | Apache 2.0 |
| Kotlin Stdlib + Corotines | https://kotlinlang.org/                       | Habe die Coroutines von Kotlin benutzt um die<br/> asynchronität zu handhaben sowie features wie timeout etc. die kotlin stdlib ist generell essentiell für eine Kotlin application | Apache 2.0 |

## Hilfsmittel

- IntelliJ IDEA
- Wireschark
- Github COPilot (AI)
- Github
- Google
- PlantUML

## Eigenständigkeitserklärung

Hiermit erkläre ich, dass ich die vorliegende Arbeit eigenständig verfasst, keine anderen als die
angegebenen Quellen und Hilfsmittel verwendet sowie die aus fremden Quellen direkt oder indirekt
übernommenen Stellen/Gedanken als solche kenntlich gemacht habe.
