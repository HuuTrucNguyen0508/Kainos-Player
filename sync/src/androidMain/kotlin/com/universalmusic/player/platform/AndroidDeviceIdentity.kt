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

internal class AndroidDeviceIdentity : DeviceIdentity {
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private var hardwareLogged = false

    @Synchronized
    private fun material(): KeyStore.PrivateKeyEntry {
        if (!store.containsAlias(ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setCertificateSubject(X500Principal("CN=Kainos device"))
                    .setCertificateSerialNumber(BigInteger.ONE)
                    .setCertificateNotBefore(Date(0))
                    .setCertificateNotAfter(Date(4_102_444_800_000L))
                    .build())
            }.generateKeyPair()
        }
        val entry = store.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
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
        store.deleteEntry(ALIAS)
        hardwareLogged = false
        return fingerprint()
    }

    fun keyManager(): X509ExtendedKeyManager {
        val entry = material()
        return object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
                if (keyType == "EC") arrayOf(ALIAS) else null
            override fun chooseClientAlias(keyTypes: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? =
                ALIAS.takeIf { keyTypes?.contains("EC") == true }
            override fun chooseEngineClientAlias(keyTypes: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String? =
                ALIAS.takeIf { keyTypes?.contains("EC") == true }
            override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
                if (alias == ALIAS) entry.certificateChain.map { it as X509Certificate }.toTypedArray() else null
            override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == ALIAS) entry.privateKey else null
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
        }
    }

    private companion object {
        const val ALIAS = "kainos-home-sync"
    }
}
