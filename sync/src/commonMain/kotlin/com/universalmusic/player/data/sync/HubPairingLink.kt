package com.universalmusic.player.data.sync

/**
 * What a PC's pairing QR code holds: the facts its mDNS record advertises, so a phone that scans it
 * skips the browse. It carries no secret; the phone still pins [DiscoveredHub.fp] and both screens
 * still compare the six-digit code.
 */
private const val SCHEME = "kainos-pair"
private const val MAX_LINK_CHARS = 512
private const val MAX_NAME_CHARS = 64
private const val MAX_ID_CHARS = 128

fun hubPairingLink(hub: DiscoveredHub): String {
    val params = listOf(
        "fp" to hub.fp.lowercase(),
        "id" to hub.id.orEmpty(),
        "host" to hub.host,
        "port" to hub.port.toString(),
        "name" to hub.name,
    )
    return "$SCHEME:$HOME_LAN_MDNS_VERSION?" + params.joinToString("&") { (key, value) -> "$key=${percentEncode(value)}" }
}

/** Fails with a message the phone can show as is. */
fun parseHubPairingLink(raw: String): Result<DiscoveredHub> = runCatching {
    val text = raw.trim()
    require(text.length <= MAX_LINK_CHARS) { NOT_A_KAINOS_CODE }
    require(!text.startsWith("kainos-homesync:")) {
        "This is an old pairing link. Open Devices and sync on your PC and scan the code shown there."
    }
    val head = text.substringBefore('?')
    require(head.startsWith("$SCHEME:") && '?' in text) { NOT_A_KAINOS_CODE }
    require(head.removePrefix("$SCHEME:") == HOME_LAN_MDNS_VERSION) {
        "This code is from a different version of Kainos. Update the app on both devices."
    }
    val params = text.substringAfter('?').split('&').map { pair ->
        require('=' in pair) { NOT_A_KAINOS_CODE }
        pair.substringBefore('=') to percentDecode(pair.substringAfter('='))
    }
    require(params.map { it.first }.toSet().size == params.size) { NOT_A_KAINOS_CODE }
    val values = params.toMap()
    val fp = values["fp"]?.let(CertFingerprint::parse) ?: throw IllegalArgumentException(NOT_A_KAINOS_CODE)
    val id = values["id"]?.takeIf { it.isNotBlank() && it.length <= MAX_ID_CHARS && it.none(Char::isISOControl) }
        ?: throw IllegalArgumentException(NOT_A_KAINOS_CODE)
    val host = values["host"]?.takeIf(::isPrivateIpv4Literal)
        ?: throw IllegalArgumentException("This code does not point to a PC on your home network.")
    val port = values["port"]?.takeIf { it.length <= 5 && it.all(Char::isDigit) }?.toInt()?.takeIf { it in 1..65_535 }
        ?: throw IllegalArgumentException(NOT_A_KAINOS_CODE)
    val name = values["name"]?.takeIf { it.isNotBlank() && it.length <= MAX_NAME_CHARS && it.none(Char::isISOControl) }
        ?: throw IllegalArgumentException(NOT_A_KAINOS_CODE)
    DiscoveredHub(fp = fp.hex, id = id, host = host, port = port, name = name)
}

private const val NOT_A_KAINOS_CODE = "This is not a Kainos pairing code."

private fun isPrivateIpv4Literal(host: String): Boolean {
    val parts = host.split('.')
    if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || !it.all(Char::isDigit) }) return false
    if (parts.any { it.length > 1 && it.startsWith('0') }) return false
    val octets = parts.map(String::toInt)
    if (octets.any { it > 255 }) return false
    return octets[0] == 10 || (octets[0] == 172 && octets[1] in 16..31) || (octets[0] == 192 && octets[1] == 168)
}

private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

private fun percentEncode(value: String): String = buildString {
    for (byte in value.encodeToByteArray()) {
        val char = (byte.toInt() and 0xff).toChar()
        if (char in UNRESERVED) append(char) else append('%').append(((byte.toInt() and 0xff) + 0x100).toString(16).substring(1).uppercase())
    }
}

private fun percentDecode(value: String): String {
    val bytes = ArrayList<Byte>(value.length)
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char == '%') {
            val hex = value.substring(index + 1, minOf(index + 3, value.length))
            require(hex.length == 2 && hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { NOT_A_KAINOS_CODE }
            bytes += hex.toInt(16).toByte()
            index += 3
        } else {
            require(char in UNRESERVED) { NOT_A_KAINOS_CODE }
            bytes += char.code.toByte()
            index++
        }
    }
    return bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
}
