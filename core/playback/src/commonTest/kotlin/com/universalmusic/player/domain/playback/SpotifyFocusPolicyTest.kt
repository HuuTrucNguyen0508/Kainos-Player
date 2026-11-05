package com.universalmusic.player.domain.playback

import com.universalmusic.player.domain.playback.SpotifyFocusPolicy.Action.*
import com.universalmusic.player.domain.playback.SpotifyFocusPolicy.Change.*
import kotlin.test.Test
import kotlin.test.assertEquals

class SpotifyFocusPolicyTest {
    @Test fun focusLossResumesOnlyItsOwnTransientPause() {
        val policy = SpotifyFocusPolicy()
        policy.activate()
        assertEquals(NONE, policy.change(DUCK, true))
        assertEquals(PAUSE, policy.change(TRANSIENT_LOSS, true))
        assertEquals(RESUME, policy.change(GAIN, false))
        assertEquals(PAUSE, policy.change(LOSS, true))
        assertEquals(NONE, policy.change(GAIN, false))
        policy.change(TRANSIENT_LOSS, true)
        policy.userPause()
        assertEquals(NONE, policy.change(GAIN, false))
        policy.deactivate()
        assertEquals(NONE, policy.change(LOSS, true))
    }
}
