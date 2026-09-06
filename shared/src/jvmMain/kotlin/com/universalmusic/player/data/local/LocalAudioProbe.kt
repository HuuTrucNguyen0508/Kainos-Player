package com.universalmusic.player.data.local

import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.refineQualityTier
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

private val probeJson = Json { ignoreUnknownKeys = true }

internal fun probeLocalAudioQuality(path: Path, fallback: AudioQuality): AudioQuality {
    val output = runFfprobe(path) ?: return fallback
    return parseFfprobeQuality(output, fallback)
}

internal fun parseFfprobeQuality(rawJson: String, fallback: AudioQuality): AudioQuality {
    val stream = runCatching {
        probeJson.decodeFromString(FfprobeRoot.serializer(), rawJson).streams.firstOrNull()
    }.getOrNull() ?: return fallback

    val sampleRateHz = stream.sampleRate.asPositiveInt()
    val bitDepth = sequenceOf(stream.bitsPerRawSample, stream.bitsPerSample)
        .mapNotNull { it.asPositiveInt() }
        .firstOrNull()
    val bitrateKbps = stream.bitRate.asPositiveLong()
        ?.div(1000)
        ?.toInt()
    val codec = stream.codecName?.trim()?.takeIf { it.isNotEmpty() } ?: fallback.codec

    return AudioQuality(
        tier = refineQualityTier(fallback.tier, sampleRateHz, bitDepth),
        codec = codec,
        bitrateKbps = bitrateKbps ?: fallback.bitrateKbps,
        sampleRateHz = sampleRateHz ?: fallback.sampleRateHz,
        bitDepth = bitDepth ?: fallback.bitDepth,
    )
}

private fun JsonElement?.asPositiveInt(): Int? {
    val primitive = this as? JsonPrimitive ?: return null
    return primitive.intOrNull?.takeIf { it > 0 }
        ?: primitive.contentOrNull?.toIntOrNull()?.takeIf { it > 0 }
}

private fun JsonElement?.asPositiveLong(): Long? {
    val primitive = this as? JsonPrimitive ?: return null
    return primitive.longOrNull?.takeIf { it > 0 }
        ?: primitive.contentOrNull?.toLongOrNull()?.takeIf { it > 0 }
}

private fun runFfprobe(path: Path): String? {
    val process = runCatching {
        ProcessBuilder(
            "ffprobe",
            "-v", "error",
            "-select_streams", "a:0",
            "-show_entries", "stream=codec_name,sample_rate,bits_per_raw_sample,bits_per_sample,bit_rate",
            "-of", "json",
            path.toAbsolutePath().normalize().toString(),
        ).redirectErrorStream(true).start()
    }.getOrNull() ?: return null

    val finished = runCatching { process.waitFor(3, TimeUnit.SECONDS) }.getOrDefault(false)
    if (!finished) {
        process.destroyForcibly()
        return null
    }
    if (process.exitValue() != 0) return null
    return runCatching { process.inputStream.bufferedReader().readText() }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
}

@Serializable
private data class FfprobeRoot(
    val streams: List<FfprobeStream> = emptyList(),
)

@Serializable
private data class FfprobeStream(
    @SerialName("codec_name") val codecName: String? = null,
    @SerialName("sample_rate") val sampleRate: JsonElement? = null,
    @SerialName("bits_per_raw_sample") val bitsPerRawSample: JsonElement? = null,
    @SerialName("bits_per_sample") val bitsPerSample: JsonElement? = null,
    @SerialName("bit_rate") val bitRate: JsonElement? = null,
)
