package com.universalmusic.player.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class LibrespotHelperCheckTest {
    @Test
    fun helperBuiltBeforeRadioModeIsReportedMissing() {
        // Exact usage line of the helper installed on 2026-09-11.
        val usage = "Usage: /x/tools/librespot-runtime/bin/kainos-discover-weekly <credentials.json> [playlist_id]\n"
        assertEquals(HelperRadioSupport.MISSING, helperRadioSupport(usage))
    }

    @Test
    fun currentHelperUsageHasRadioMode() {
        val usage = "Usage: kainos-discover-weekly <credentials.json> [discover-weekly|playlist:<id>|radio:<trackId>]\n"
        assertEquals(HelperRadioSupport.PRESENT, helperRadioSupport(usage))
    }

    @Test
    fun unreadableOutputIsUnknownRatherThanMissing() {
        assertEquals(HelperRadioSupport.UNKNOWN, helperRadioSupport(null))
        assertEquals(HelperRadioSupport.UNKNOWN, helperRadioSupport("segfault"))
    }
}
