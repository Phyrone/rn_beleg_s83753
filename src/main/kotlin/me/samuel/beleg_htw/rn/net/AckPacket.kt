package me.samuel.beleg_htw.rn.net

data class AckPacket(
    val limit: UByte,
    val crC32: Int,
)
