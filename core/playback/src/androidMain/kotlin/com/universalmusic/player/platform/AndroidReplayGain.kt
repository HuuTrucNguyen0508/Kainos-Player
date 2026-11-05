package com.universalmusic.player.platform

import androidx.media3.common.C
import androidx.media3.common.Metadata
import androidx.media3.common.Tracks
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import com.universalmusic.player.domain.playback.ReplayGain
import com.universalmusic.player.domain.playback.VolumeLevelling

/** ReplayGain of the selected audio track, or null when nothing is selected yet or it has no tags. */
internal fun replayGainOf(tracks: Tracks): ReplayGain? {
    val group = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected } ?: return null
    val index = (0 until group.length).firstOrNull(group::isTrackSelected) ?: return null
    return replayGainOf(group.getTrackFormat(index).metadata)
}

/** Reads FLAC and Ogg Vorbis comments, ID3 TXXX frames and MP4 iTunes freeform atoms. */
@Suppress("DEPRECATION") // The flac VorbisComment is the base class of the vorbis one Media3 now emits.
internal fun replayGainOf(metadata: Metadata?): ReplayGain? {
    if (metadata == null) return null
    val tags = buildMap {
        for (index in 0 until metadata.length()) {
            when (val entry = metadata.get(index)) {
                is androidx.media3.extractor.metadata.flac.VorbisComment -> put(entry.key.uppercase(), entry.value)
                is TextInformationFrame -> if (entry.id == "TXXX") {
                    entry.description?.let { description -> entry.values.firstOrNull()?.let { put(description.uppercase(), it) } }
                }
                is InternalFrame -> put(entry.description.uppercase(), entry.text)
            }
        }
    }
    return VolumeLevelling.parse(tags::get)
}
