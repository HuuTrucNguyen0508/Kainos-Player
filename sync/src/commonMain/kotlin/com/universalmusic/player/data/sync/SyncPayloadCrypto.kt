package com.universalmusic.player.data.sync

import com.universalmusic.player.platform.secureRandomBytes
import com.universalmusic.player.platform.sha256Bytes
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * AES-GCM payload encryption for home-LAN vault bodies (Phase 3).
 * Key = SHA-256(sharedSecretHex UTF-8). Wire format: nonce(12) || ciphertext+tag.
 */
object SyncPayloadCrypto {
    private const val NONCE_BYTES = 12

    fun keyFromSharedSecret(sharedSecretHex: String): ByteArray =
        sha256Bytes(sharedSecretHex.encodeToByteArray())

    fun encrypt(plain: ByteArray, key: ByteArray): ByteArray {
        val nonce = secureRandomBytes(NONCE_BYTES)
        val sealed = aesGcmEncrypt(key, nonce, plain)
        return nonce + sealed
    }

    fun decrypt(blob: ByteArray, key: ByteArray): ByteArray {
        require(blob.size > NONCE_BYTES) { "Encrypted payload too short" }
        val nonce = blob.copyOfRange(0, NONCE_BYTES)
        val sealed = blob.copyOfRange(NONCE_BYTES, blob.size)
        return aesGcmDecrypt(key, nonce, sealed)
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun encryptToBase64(plain: ByteArray, key: ByteArray): String =
        Base64.Default.encode(encrypt(plain, key))

    @OptIn(ExperimentalEncodingApi::class)
    fun decryptFromBase64(encoded: String, key: ByteArray): ByteArray =
        decrypt(Base64.Default.decode(encoded), key)
}

/** Platform AES-GCM (JVM / Android). */
expect fun aesGcmEncrypt(key: ByteArray, nonce: ByteArray, plain: ByteArray): ByteArray

expect fun aesGcmDecrypt(key: ByteArray, nonce: ByteArray, sealed: ByteArray): ByteArray
