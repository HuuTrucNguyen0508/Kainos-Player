package com.universalmusic.player.domain.playback

/** A focus handoff between our backends never counts as an external interruption. */
internal class SpotifyFocusPolicy {
    var active = false
        private set
    var resumeOnGain = false
        private set
    enum class Change { LOSS, TRANSIENT_LOSS, DUCK, GAIN }
    enum class Action { NONE, PAUSE, RESUME }
    fun activate() { active = true }
    fun deactivate() { active = false; resumeOnGain = false }
    fun userPause() { resumeOnGain = false }
    fun change(change: Change, playing: Boolean): Action {
        if (!active) return Action.NONE
        return when (change) {
            Change.DUCK -> Action.NONE
            Change.LOSS, Change.TRANSIENT_LOSS -> {
                resumeOnGain = change == Change.TRANSIENT_LOSS && playing
                if (playing) Action.PAUSE else Action.NONE
            }
            Change.GAIN -> {
                val resume = resumeOnGain
                resumeOnGain = false
                if (resume) Action.RESUME else Action.NONE
            }
        }
    }
}
