package com.universalmusic.player.data.sync

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PairingProtocolTest {
    private val hubFp = CertFingerprint.parse("11".repeat(32))!!
    private val phoneFp = CertFingerprint.parse("22".repeat(32))!!
    private val strangerFp = CertFingerprint.parse("99".repeat(32))!!
    private val hubNonce = ByteArray(32) { 0x33 }
    private val phoneNonce = ByteArray(32) { 0x44 }
    private val attemptId = "33".repeat(16)

    private var now = 1_000_000L
    private val paired = mutableListOf<PairedPeer>()
    private val host = PairingHost(
        hubFingerprint = { hubFp },
        hubDeviceId = { "kainos-pc" },
        onPaired = { paired += it },
        clock = { now },
        random = { size -> ByteArray(size) { 0x33 } },
    )

    private fun startRequest(nonce: ByteArray = phoneNonce) =
        PairStartRequest("kainos-phone", "Poco F7 Ultra", pairingCommit(nonce).toHex())

    private fun reveal(nonce: ByteArray = phoneNonce) = PairRevealRequest(attemptId, nonce.toHex())

    @Test
    fun codeIsTheFirstFourDigestBytesModuloAMillionWithLeadingZeros() {
        assertEquals("079838", pairingCode(hubFp, phoneFp, hubNonce, phoneNonce).digits)
        assertEquals("582793", pairingCode(phoneFp, hubFp, hubNonce, phoneNonce).digits)
    }

    @Test
    fun trustIsStoredOnlyAfterBothConfirmations() = runTest {
        assertEquals(PairingReply.Ok(PairStartResponse(attemptId, hubNonce.toHex())), host.start(phoneFp, startRequest()))
        assertEquals(PairingReply.Ok(Unit), host.reveal(phoneFp, reveal()))
        assertEquals(
            PairingAttempt(attemptId, "kainos-phone", "Poco F7 Ultra", phoneFp, PairingCode("079838"),
                pcConfirmed = false, phoneConfirmed = false),
            host.incoming.value,
        )
        assertEquals(PairingReply.Ok(Unit), host.confirmFromPhone(phoneFp, attemptId))
        assertEquals(emptyList(), paired)
        assertEquals(PairingReply.Ok(PairStatusResponse(PairState.PENDING)), host.status(phoneFp, attemptId))

        now += 5_000
        assertEquals(PairingReply.Ok(Unit), host.confirmFromPc(attemptId))
        assertEquals(listOf(PairedPeer("kainos-phone", "Poco F7 Ultra", phoneFp, pairedAtMs = 1_005_000L)), paired)
        assertEquals(
            PairingReply.Ok(PairStatusResponse(PairState.PAIRED, hubDeviceId = "kainos-pc")),
            host.status(phoneFp, attemptId),
        )
        assertNull(host.incoming.value)
    }

    @Test
    fun aMismatchedRevealEndsTheAttemptForGood() = runTest {
        host.start(phoneFp, startRequest())
        val ended = PairingReply.Refused(PairingRefusal.ENDED, "The phone's pairing check did not match")
        assertEquals(ended, host.reveal(phoneFp, reveal(ByteArray(32) { 0x55 })))
        assertEquals(410, ended.refusal.httpStatus)
        assertEquals(ended, host.reveal(phoneFp, reveal()))
        assertEquals(ended, host.confirmFromPc(attemptId))
        assertEquals(
            PairingReply.Ok(PairStatusResponse(PairState.ENDED, reason = "The phone's pairing check did not match")),
            host.status(phoneFp, attemptId),
        )
        assertEquals(emptyList(), paired)
    }

    @Test
    fun aSecondStartWhileOneIsPendingGets409() = runTest {
        host.start(phoneFp, startRequest())
        val reply = host.start(strangerFp, startRequest())
        assertEquals(PairingReply.Refused(PairingRefusal.BUSY, "Another pairing is in progress"), reply)
        assertEquals(409, PairingRefusal.BUSY.httpStatus)
    }

    @Test
    fun anotherClientFingerprintGets403OnEveryLaterRequest() = runTest {
        host.start(phoneFp, startRequest())
        val foreign = PairingReply.Refused(PairingRefusal.FOREIGN, "Not this device's pairing attempt")
        assertEquals(foreign, host.reveal(strangerFp, reveal()))
        assertEquals(foreign, host.confirmFromPhone(strangerFp, attemptId))
        assertEquals(foreign, host.cancel(strangerFp, attemptId))
        assertEquals(foreign, host.status(strangerFp, attemptId))
        assertEquals(foreign, host.status(phoneFp, "44".repeat(16)))
        assertEquals(403, PairingRefusal.FOREIGN.httpStatus)
        assertEquals(PairingReply.Ok(Unit), host.reveal(phoneFp, reveal()))
    }

    @Test
    fun aSixthStartWithinOneMinuteIsRefused() = runTest {
        repeat(5) {
            assertEquals(PairingReply.Ok(PairStartResponse(attemptId, hubNonce.toHex())), host.start(phoneFp, startRequest()))
            host.cancel(phoneFp, attemptId)
            now += 1_000
        }
        assertEquals(
            PairingReply.Refused(PairingRefusal.RATE_LIMITED, "Too many pairing attempts; wait a minute"),
            host.start(phoneFp, startRequest()),
        )
        now += 56_000
        assertEquals(PairingReply.Ok(PairStartResponse(attemptId, hubNonce.toHex())), host.start(phoneFp, startRequest()))
    }

    @Test
    fun anAttemptExpiresTwoMinutesAfterItStarted() = runTest {
        host.start(phoneFp, startRequest())
        host.reveal(phoneFp, reveal())
        host.confirmFromPhone(phoneFp, attemptId)
        now += PAIRING_ATTEMPT_TTL_MS
        assertEquals(
            PairingReply.Refused(PairingRefusal.ENDED, "The pairing timed out"),
            host.confirmFromPc(attemptId),
        )
        assertEquals(emptyList(), paired)
        assertNull(host.incoming.value)
    }

    @Test
    fun rejectOnThePcAndCancelOnThePhoneBothEndTheAttempt() = runTest {
        host.start(phoneFp, startRequest())
        host.reveal(phoneFp, reveal())
        host.confirmFromPhone(phoneFp, attemptId)
        assertEquals(PairingReply.Ok(Unit), host.rejectFromPc(attemptId))
        assertEquals(
            PairingReply.Ok(PairStatusResponse(PairState.ENDED, reason = "The PC rejected the pairing")),
            host.status(phoneFp, attemptId),
        )

        host.start(phoneFp, startRequest())
        host.reveal(phoneFp, reveal())
        host.confirmFromPc(attemptId)
        assertEquals(PairingReply.Ok(Unit), host.cancel(phoneFp, attemptId))
        assertEquals(
            PairingReply.Refused(PairingRefusal.ENDED, "The phone cancelled the pairing"),
            host.confirmFromPhone(phoneFp, attemptId),
        )
        assertEquals(emptyList(), paired)
    }
}
