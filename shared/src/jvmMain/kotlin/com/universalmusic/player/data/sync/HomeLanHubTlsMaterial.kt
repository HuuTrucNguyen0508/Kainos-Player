package com.universalmusic.player.data.sync

/**
 * TLS material for the desktop home-LAN hub.
 * [certSha256Hex] is SHA-256 of the leaf certificate DER (lowercase hex), pinned in pairing.
 */
data class HomeLanHubTlsMaterial(
    val certSha256Hex: String,
    val keyStore: java.security.KeyStore,
    val keyStorePassword: CharArray,
    val keyAlias: String,
    val privateKeyPassword: CharArray,
    val keyStoreFile: java.io.File,
)
