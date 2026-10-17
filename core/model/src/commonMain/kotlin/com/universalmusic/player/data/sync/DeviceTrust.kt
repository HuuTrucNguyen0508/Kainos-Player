package com.universalmusic.player.data.sync

import com.universalmusic.player.platform.sha256Bytes
import kotlin.jvm.JvmInline
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** SHA-256 of a device certificate's DER encoding, as 64 lowercase hex characters. */
@Serializable(with = CertFingerprintSerializer::class)
@JvmInline
value class CertFingerprint private constructor(val hex: String) {
    val bytes: ByteArray get() = ByteArray(32) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    override fun toString(): String = hex

    companion object {
        private val HEX64 = Regex("[0-9a-f]{64}")

        fun parse(raw: String): CertFingerprint? =
            raw.trim().lowercase().takeIf(HEX64::matches)?.let(::CertFingerprint)

        fun ofCertificate(der: ByteArray): CertFingerprint =
            CertFingerprint(sha256Bytes(der).joinToString("") { ((it.toInt() and 0xff) + 0x100).toString(16).substring(1) })
    }
}

internal object CertFingerprintSerializer : KSerializer<CertFingerprint> {
    override val descriptor = PrimitiveSerialDescriptor("CertFingerprint", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: CertFingerprint) = encoder.encodeString(value.hex)
    override fun deserialize(decoder: Decoder): CertFingerprint {
        val raw = decoder.decodeString()
        return CertFingerprint.parse(raw) ?: throw SerializationException("Not a certificate fingerprint: $raw")
    }
}

/** A device this one trusts, known by the certificate fingerprint it presented while pairing. */
@Serializable
data class PairedPeer(
    val deviceId: String,
    val displayName: String,
    val fingerprint: CertFingerprint,
    val lastKnownHost: String? = null,
    val lastKnownPort: Int = HOME_LAN_SYNC_DEFAULT_PORT,
    val pairedAtMs: Long,
)

/** Six decimal digits both screens show; equal codes mean both sides saw the same two certificates. */
@JvmInline
value class PairingCode(val digits: String) {
    override fun toString(): String = digits

    companion object {
        fun fromDigest(digest: ByteArray): PairingCode {
            val head = digest.take(4).fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xff) }
            return PairingCode((head % 1_000_000).toString().padStart(6, '0'))
        }
    }
}

/** The incoming request the PC shows while a phone pairs with it. */
data class PairingAttempt(
    val attemptId: String,
    val peerDeviceId: String,
    val peerName: String,
    val peerFingerprint: CertFingerprint,
    /** Null until the phone reveals its nonce; the PC cannot confirm before then. */
    val code: PairingCode?,
    val pcConfirmed: Boolean,
    val phoneConfirmed: Boolean,
)
