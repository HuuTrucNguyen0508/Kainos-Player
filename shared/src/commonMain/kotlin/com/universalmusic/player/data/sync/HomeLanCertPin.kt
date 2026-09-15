package com.universalmusic.player.data.sync

import com.universalmusic.player.platform.sha256Bytes

/** SHA-256 of certificate DER bytes as lowercase hex (hub TLS pin). */
fun certSha256Hex(certificateDer: ByteArray): String =
    sha256Bytes(certificateDer).toHex()
