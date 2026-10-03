package com.universalmusic.player.data.cache

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID

class AndroidMetadataCacheDisk(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : MetadataCacheDisk {
    private val root = File(context.applicationContext.filesDir, "meta-cache")
    private val indexFile = File(root, "index.json")
    private val artDir = File(root, "art")

    override suspend fun loadIndex(): MetadataCacheIndex {
        if (!indexFile.exists()) return MetadataCacheIndex()
        return runCatching {
            json.decodeFromString<MetadataCacheIndex>(indexFile.readText())
        }.getOrDefault(MetadataCacheIndex())
    }

    override suspend fun saveIndex(index: MetadataCacheIndex) {
        root.mkdirs()
        val tmp = File(root, "index.json.tmp")
        tmp.writeText(json.encodeToString(index))
        if (!tmp.renameTo(indexFile)) {
            tmp.copyTo(indexFile, overwrite = true)
            tmp.delete()
        }
    }

    override suspend fun saveArtwork(canonicalId: String, bytes: ByteArray): String? {
        artDir.mkdirs()
        val file = File(artDir, fileNameFor(canonicalId))
        val tmp = File(artDir, "${file.name}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
        return file.toURI().toASCIIString()
    }

    override suspend fun deleteArtwork(canonicalId: String) {
        File(artDir, fileNameFor(canonicalId)).delete()
    }

    override suspend fun clearAll() {
        artDir.listFiles()?.forEach { it.delete() }
        indexFile.delete()
        root.mkdirs()
    }

    override fun artworkExists(uri: String): Boolean {
        return when {
            uri.startsWith("file:") -> runCatching {
                File(java.net.URI(uri)).isFile
            }.getOrDefault(false)
            uri.startsWith("content:") -> true
            else -> File(uri).isFile
        }
    }

    override suspend fun artworkBytesUsed(): Long =
        artDir.listFiles()?.sumOf { it.length() } ?: 0L

    override suspend fun artworkFileCount(): Int =
        artDir.listFiles()?.size ?: 0

    private fun fileNameFor(canonicalId: String): String {
        val id = UUID.nameUUIDFromBytes(canonicalId.toByteArray(StandardCharsets.UTF_8)).toString()
        return "$id.jpg"
    }
}
