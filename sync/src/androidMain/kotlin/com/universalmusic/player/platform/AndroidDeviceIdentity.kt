package com.universalmusic.player.platform

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import com.universalmusic.player.data.sync.CertFingerprint
import java.math.BigInteger
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.SSLEngine
import javax.security.auth.x500.X500Principal

internal class AndroidDeviceIdentity(private val alias: String = ALIAS) : DeviceIdentity {
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private var hardwareLogged = false

    @Synchronized
    private fun material(): KeyStore.PrivateKeyEntry {
        if (!store.containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setCertificateSubject(X500Principal("CN=Kainos device"))
                    .setCertificateSerialNumber(BigInteger.ONE)
                    .setCertificateNotBefore(Date(0))
                    .setCertificateNotAfter(Date(4_102_444_800_000L))
                    .build())
            }.generateKeyPair()
        }
        val entry = store.getEntry(alias, null) as KeyStore.PrivateKeyEntry
        if (!hardwareLogged) {
            val info = KeyFactory.getInstance(entry.privateKey.algorithm, "AndroidKeyStore")
                .getKeySpec(entry.privateKey, KeyInfo::class.java)
            PlaybackTrace.log("HomeSync", "Keystore isInsideSecureHardware=${info.isInsideSecureHardware}")
            hardwareLogged = true
        }
        return entry
    }

    override fun fingerprint(): CertFingerprint = CertFingerprint.ofCertificate(material().certificate.encoded)

    @Synchronized
    override fun reset(): CertFingerprint {
        val previous = runCatching { store.getEntry(alias, null) as? KeyStore.PrivateKeyEntry }.getOrNull()
        var deleted = false
        try {
            store.deleteEntry(alias)
            deleted = true
            hardwareLogged = false
            return fingerprint()
        } catch (failure: Exception) {
            val preserved = if (deleted || previous == null) null else runCatching {
                (store.getEntry(alias, null) as? KeyStore.PrivateKeyEntry)?.takeIf {
                    it.certificate.encoded.contentEquals(previous.certificate.encoded)
                }?.let { CertFingerprint.ofCertificate(it.certificate.encoded) }
            }.getOrNull()
            throw DeviceIdentityResetException(preserved, failure)
        }
    }

    fun keyManager(): X509ExtendedKeyManager {
        PlaybackTrace.log("HomeSync", "TLS key manager start")
        val entry = material()
        PlaybackTrace.log("HomeSync", "TLS key manager ready algorithm=${entry.privateKey.algorithm}")
        return object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
                if (keyType == "EC") arrayOf(this@AndroidDeviceIdentity.alias) else null
            override fun chooseClientAlias(keyTypes: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? {
                val alias = this@AndroidDeviceIdentity.alias.takeIf { keyTypes?.contains("EC") == true }
                PlaybackTrace.log("HomeSync", "TLS chooseClientAlias types=${keyTypes?.joinToString()} selected=${alias != null}")
                return alias
            }
            override fun chooseEngineClientAlias(keyTypes: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String? =
                this@AndroidDeviceIdentity.alias.takeIf { keyTypes?.contains("EC") == true }
            override fun getCertificateChain(alias: String?): Array<X509Certificate>? {
                PlaybackTrace.log("HomeSync", "TLS getCertificateChain matched=${alias == this@AndroidDeviceIdentity.alias}")
                return if (alias == this@AndroidDeviceIdentity.alias) entry.certificateChain.map { it as X509Certificate }.toTypedArray() else null
            }
            override fun getPrivateKey(alias: String?): PrivateKey? {
                PlaybackTrace.log("HomeSync", "TLS getPrivateKey matched=${alias == this@AndroidDeviceIdentity.alias}")
                return if (alias == this@AndroidDeviceIdentity.alias) entry.privateKey else null
            }
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
        }
    }

    private companion object {
        const val ALIAS = "kainos-home-sync"
    }
}
