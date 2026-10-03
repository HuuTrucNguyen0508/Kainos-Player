package com.universalmusic.player.data.cache

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class AndroidHeartedAudioCacheDisk(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : HeartedAudioCacheDisk {
    private val root = File(context.applicationContext.filesDir, "audio-cache")
    private val indexFile = File(root, "index.json")
    private val audioDir = File(root, "audio")

    private val startupFiles = audioDir.listFiles()?.filter { it.isFile }?.toList().orEmpty()
    private var reconciled = false
    private var indexReadable = true

    override suspend fun loadIndex(): HeartedAudioCacheIndex {
        if (!indexFile.exists()) return HeartedAudioCacheIndex()
        return runCatching {
            json.decodeFromString<HeartedAudioCacheIndex>(indexFile.readText())
        }.getOrElse { indexReadable = false; HeartedAudioCacheIndex() }
    }

    override suspend fun saveIndex(index: HeartedAudioCacheIndex) {
        root.mkdirs()
        audioDir.mkdirs()
        val tmp = File.createTempFile("index-", ".tmp", root)
        try {
            tmp.writeText(json.encodeToString(index))
            try {
                java.nio.file.Files.move(tmp.toPath(), indexFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(tmp.toPath(), indexFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            tmp.delete()
        }
    }

    override suspend fun reconcile(referencedUris: Set<String>) {
        if (reconciled || !indexReadable) return
        val referenced = referencedUris.map(::normalizedAudioUri).toSet()
        startupFiles.filter { normalizedAudioUri(it.absolutePath) !in referenced }.forEach { it.delete() }
        reconciled = true
    }

    override suspend fun deleteFile(uri: String) {
        fileFromUri(uri)?.delete()
    }

    override suspend fun clearAll() {
        audioDir.listFiles()?.forEach { it.delete() }
        indexFile.delete()
        root.mkdirs()
        audioDir.mkdirs()
    }

    override fun fileExists(uri: String): Boolean {
        val file = fileFromUri(uri) ?: return false
        return file.isFile && file.length() > 0L
    }

    override fun audioDirectoryPath(): String {
        audioDir.mkdirs()
        return audioDir.absolutePath
    }

    override suspend fun bytesUsed(): Long =
        audioDir.listFiles()?.sumOf { it.length() } ?: 0L

    private fun fileFromUri(uri: String): File? = runCatching {
        when {
            uri.startsWith("file:") -> File(java.net.URI(uri))
            else -> File(uri)
        }
    }.getOrNull()
}
