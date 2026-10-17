package com.universalmusic.player.data.sync

import java.nio.file.Files
import java.security.cert.X509Certificate
import kotlin.test.Test
import kotlin.test.assertTrue

class HomeLanHubTlsTest {
    @Test
    fun lanIpIsAnIpSanSoJvmClientsAcceptIt() {
        val dir = Files.createTempDirectory("kainos-hub-tls").toFile()
        try {
            val material = generateHomeLanHubTls(dir, "ab".repeat(32), extraHosts = listOf("192.168.1.128"))
            val cert = material.keyStore.getCertificate(material.keyAlias) as X509Certificate
            val sans = cert.subjectAlternativeNames.map { (it[0] as Int) to it[1].toString() }
            val ipSan = 7
            val dnsSan = 2
            assertTrue(ipSan to "192.168.1.128" in sans, "SANs were $sans")
            assertTrue(ipSan to "127.0.0.1" in sans, "SANs were $sans")
            assertTrue(dnsSan to "localhost" in sans, "SANs were $sans")
        } finally {
            dir.deleteRecursively()
        }
    }
}
