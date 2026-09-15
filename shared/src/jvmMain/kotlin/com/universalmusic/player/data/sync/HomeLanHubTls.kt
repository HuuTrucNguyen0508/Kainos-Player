package com.universalmusic.player.data.sync

import com.universalmusic.player.platform.sha256Bytes
import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.network.tls.certificates.saveToFile
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate

private const val HUB_KEY_ALIAS = "kainos-home-lan"
private const val HUB_KEYSTORE_FILE = "home-lan-hub.p12"

/**
 * Creates a fresh self-signed hub certificate, writes PKCS12 under [configDir],
 * and returns material including the SHA-256 pin for pairing.
 *
 * [extraHosts] are added as SAN entries (e.g. detected LAN IP) so clients can
 * verify the certificate against the connect host without a separate SNI override.
 */
fun generateHomeLanHubTls(
    configDir: File,
    sharedSecretHex: String,
    extraHosts: List<String> = emptyList(),
): HomeLanHubTlsMaterial {
    configDir.mkdirs()
    val keyStoreFile = File(configDir, HUB_KEYSTORE_FILE)
    val keyStorePassword = keyStorePasswordFor(sharedSecretHex)
    val privateKeyPassword = keyStorePassword
    val hosts = (listOf("localhost", "127.0.0.1", "kainos-home-lan") + extraHosts)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
    val keyStore = buildKeyStore {
        certificate(HUB_KEY_ALIAS) {
            password = privateKeyPassword.concatToString()
            domains = hosts
        }
    }
    keyStore.saveToFile(keyStoreFile, keyStorePassword.concatToString())
    val cert = keyStore.getCertificate(HUB_KEY_ALIAS) as X509Certificate
    return HomeLanHubTlsMaterial(
        certSha256Hex = certSha256Hex(cert.encoded),
        keyStore = keyStore,
        keyStorePassword = keyStorePassword,
        keyAlias = HUB_KEY_ALIAS,
        privateKeyPassword = privateKeyPassword,
        keyStoreFile = keyStoreFile,
    )
}

/**
 * Loads the hub PKCS12 and verifies the leaf pin matches [expectedCertSha256Hex].
 */
fun loadHomeLanHubTls(
    configDir: File,
    sharedSecretHex: String,
    expectedCertSha256Hex: String,
): HomeLanHubTlsMaterial {
    val keyStoreFile = File(configDir, HUB_KEYSTORE_FILE)
    require(keyStoreFile.isFile) {
        "Hub TLS keystore missing; re-run Start hub pairing on the PC"
    }
    val keyStorePassword = keyStorePasswordFor(sharedSecretHex)
    val keyStore = KeyStore.getInstance("PKCS12").apply {
        keyStoreFile.inputStream().use { load(it, keyStorePassword) }
    }
    val cert = keyStore.getCertificate(HUB_KEY_ALIAS) as? X509Certificate
        ?: error("Hub TLS certificate missing from keystore; re-pair")
    val pin = certSha256Hex(cert.encoded)
    require(pin.equals(expectedCertSha256Hex.trim(), ignoreCase = true)) {
        "Hub TLS certificate pin mismatch; re-run Start hub pairing and re-pair the phone"
    }
    return HomeLanHubTlsMaterial(
        certSha256Hex = pin,
        keyStore = keyStore,
        keyStorePassword = keyStorePassword,
        keyAlias = HUB_KEY_ALIAS,
        privateKeyPassword = keyStorePassword,
        keyStoreFile = keyStoreFile,
    )
}

private fun keyStorePasswordFor(sharedSecretHex: String): CharArray {
    val material = ("kainos-home-lan-keystore|" + sharedSecretHex.trim().lowercase()).encodeToByteArray()
    return sha256Bytes(material).toHex().toCharArray()
}
