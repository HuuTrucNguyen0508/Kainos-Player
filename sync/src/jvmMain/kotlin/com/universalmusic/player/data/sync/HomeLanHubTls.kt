package com.universalmusic.player.data.sync

import com.universalmusic.player.platform.JvmDeviceIdentity
import io.ktor.server.application.ApplicationCall
import io.ktor.server.netty.NettyApplicationCall
import io.ktor.server.routing.RoutingCall
import io.netty.handler.ssl.ClientAuth
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.SslHandler
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Server TLS for the hub. Any client certificate completes the handshake, and a client without one
 * does too, because trust is decided per request from the fingerprint, not by PKIX.
 */
internal fun hubServerSslContext(identity: JvmDeviceIdentity.Material): SslContext {
    val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        .apply { init(identity.keyStore, identity.password()) }
    return SslContextBuilder.forServer(keyManagers)
        .trustManager(acceptAnyClientCertificate)
        .clientAuth(ClientAuth.OPTIONAL)
        .build()
}

private val acceptAnyClientCertificate = object : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
        throw CertificateException("The hub never verifies servers")
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** Fingerprint of the leaf certificate this call's client presented, or null when it sent none. */
internal fun ApplicationCall.clientFingerprint(): CertFingerprint? {
    val engineCall = (this as RoutingCall).pipelineCall.engineCall as NettyApplicationCall
    val channel = engineCall.context.channel()
    val ssl = channel.pipeline().get(SslHandler::class.java)
        ?: channel.parent()?.pipeline()?.get(SslHandler::class.java)
        ?: return null
    val leaf = runCatching { ssl.engine().session.peerCertificates.firstOrNull() }.getOrNull() ?: return null
    return CertFingerprint.ofCertificate(leaf.encoded)
}

internal sealed interface HubCaller {
    data object Anonymous : HubCaller
    data class Unpaired(val fingerprint: CertFingerprint) : HubCaller
    data class Paired(val peer: PairedPeer) : HubCaller
}

/** The hub's only trust decision. This PC's own fingerprint is paired, so the PC can sync with itself. */
internal fun identifyHubCaller(
    fingerprint: CertFingerprint?,
    self: PairedPeer,
    peers: List<PairedPeer>,
): HubCaller = when {
    fingerprint == null -> HubCaller.Anonymous
    fingerprint == self.fingerprint -> HubCaller.Paired(self)
    else -> peers.firstOrNull { it.fingerprint == fingerprint }?.let(HubCaller::Paired)
        ?: HubCaller.Unpaired(fingerprint)
}
