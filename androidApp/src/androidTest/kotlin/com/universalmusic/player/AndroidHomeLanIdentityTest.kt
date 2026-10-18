package com.universalmusic.player

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.universalmusic.player.platform.DeviceIdentity
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidHomeLanIdentityTest {
    private val alias = "kainos-test-home-sync-${UUID.randomUUID()}"
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun identity(): DeviceIdentity = Class.forName(
        "com.universalmusic.player.platform.AndroidDeviceIdentity",
    ).getDeclaredConstructor(String::class.java).apply { isAccessible = true }
        .newInstance(alias) as DeviceIdentity

    @After
    fun cleanUp() {
        store.deleteEntry(alias)
    }

    @Test
    fun freshIdentitySignsTheDigestUsedByTls() {
        identity().fingerprint()
        assertTlsSignature()
    }

    @Test
    fun legacySha256OnlyIdentityIsReplacedOnceAndThenSurvivesReload() {
        KeyPairGenerator.getInstance("EC", "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build())
        }.generateKeyPair()
        val legacy = store.getCertificate(alias).encoded.toList()
        val repaired = identity().fingerprint()
        assertNotEquals(legacy, store.getCertificate(alias).encoded.toList())
        assertEquals(repaired, identity().fingerprint())
        assertTlsSignature()
    }

    @Test
    fun compatibleIdentitySurvivesReloadAndResetCreatesAnotherUsableKey() {
        val identity = identity()
        val original = identity.fingerprint()
        assertEquals(original, identity().fingerprint())
        val renewed = identity.reset()
        assertNotEquals(original, renewed)
        assertEquals(renewed, identity().fingerprint())
        assertTlsSignature()
    }

    private fun assertTlsSignature() {
        val message = "Kainos TLS CertificateVerify regression".toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(message)
        val signed = Signature.getInstance("NONEwithECDSA").run {
            initSign(store.getKey(alias, null) as PrivateKey)
            update(digest)
            sign()
        }
        assertTrue(Signature.getInstance("SHA256withECDSA").run {
            initVerify(store.getCertificate(alias))
            update(message)
            verify(signed)
        })
    }
}
