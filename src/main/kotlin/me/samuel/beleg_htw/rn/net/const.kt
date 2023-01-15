package me.samuel.beleg_htw.rn.net


const val START_STRING_TEXT = "Start"
val START_STRING_BYTES = START_STRING_TEXT.toByteArray(Charsets.US_ASCII)

const val MAX_RESEND_ATTEMPTS = 10

//TODO replace with adaptive timeout
const val BASE_TIMEOUT = 500L
const val TIMEOUT_MULTIPLIER = 1.25
const val PACKET_ID_OVERFLOW = 256

const val FALLBACK_MTU = 1500
const val UDP_OVERHEAD = 28
const val IPV4_OVERHEAD = 20
const val IPV6_OVERHEAD = 40
const val FT_HEADER_SIZE = 3 //session id + packet id