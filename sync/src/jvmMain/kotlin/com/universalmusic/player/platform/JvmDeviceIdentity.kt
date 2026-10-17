package com.universalmusic.player.platform

import com.universalmusic.player.data.sync.CertFingerprint
import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.network.tls.extensions.HashAlgorithm
import io.ktor.network.tls.extensions.SignatureAlgorithm
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.security.auth.x500.X500Principal

/**
 * This PC's one EC P-256 identity: the hub's server certificate and the client certificate it
 * presents to other hubs. The PKCS12 password is random and lives in its own owner-only file.
 */
class JvmDeviceIdentity(private val directory: File) : DeviceIdentity {
    class Material(val keyStore: KeyStore, private val password: CharArray, val fingerprint: CertFingerprint) {
        /** Ktor zeroes a password array it is handed, so every caller gets its own copy. */
        fun password(): CharArray = password.copyOf()

        fun keyManagers(): Array<KeyManager> =
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                .apply { init(keyStore, password()) }
                .keyManagers
    }

    private val keyStoreFile = File(directory, "device.p12")
    private val passwordFile = File(directory, "device.p12.password")
    private var loaded: Material? = null

    @Synchronized
    fun material(): Material = loaded ?: (load() ?: create()).also { loaded = it }

    override fun fingerprint(): CertFingerprint = material().fingerprint

    @Synchronized
    override fun reset(): CertFingerprint {
        loaded = null
        keyStoreFile.delete()
        passwordFile.delete()
        return material().fingerprint
    }

    private fun load(): Material? {
        if (!keyStoreFile.isFile || !passwordFile.isFile) return null
        val password = passwordFile.readText().trim().toCharArray()
        val keyStore = runCatching {
            KeyStore.getInstance("PKCS12").apply { keyStoreFile.inputStream().use { load(it, password) } }
        }.getOrNull() ?: return null
        val cert = keyStore.getCertificate(KEY_ALIAS) as? X509Certificate ?: return null
        return Material(keyStore, password, CertFingerprint.ofCertificate(cert.encoded))
    }

    private fun create(): Material {
        directory.mkdirs()
        val password = ByteArray(24).also(SecureRandom()::nextBytes)
            .joinToString("") { ((it.toInt() and 0xff) + 0x100).toString(16).substring(1) }
        val keyStore = buildKeyStore {
            certificate(KEY_ALIAS) {
                this.password = password
                sign = SignatureAlgorithm.ECDSA
                hash = HashAlgorithm.SHA256
                keySizeInBits = 256
                daysValid = 36_500L
                subject = X500Principal("CN=Kainos device")
                domains = emptyList()
                ipAddresses = emptyList()
            }
        }
        writeOwnerOnly(passwordFile) { it.write(password.encodeToByteArray()) }
        writeOwnerOnly(keyStoreFile) { keyStore.store(it, password.toCharArray()) }
        val cert = keyStore.getCertificate(KEY_ALIAS) as X509Certificate
        return Material(keyStore, password.toCharArray(), CertFingerprint.ofCertificate(cert.encoded))
    }

    /** Created owner-only before any byte is written, so the key is never briefly world-readable. */
    private fun writeOwnerOnly(file: File, write: (java.io.OutputStream) -> Unit) {
        file.delete()
        runCatching { Files.createFile(file.toPath(), PosixFilePermissions.asFileAttribute(OWNER_ONLY)) }
        file.outputStream().use(write)
        runCatching { Files.setPosixFilePermissions(file.toPath(), OWNER_ONLY) }
    }

    companion object {
        const val KEY_ALIAS = "kainos-device"
        private val OWNER_ONLY = PosixFilePermissions.fromString("rw-------")
    }
}
