package com.universalmusic.player.data.sync

import com.universalmusic.player.data.local.readLocalContentKey
import com.universalmusic.player.platform.sha256Bytes
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class JvmHomeLanVaultStore(
    private val vaultRootProvider: () -> String?,
    private val statePath: Path,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
    private val hashContent: Boolean = false,
) : CheckedVaultStore {
    override fun isConfigured(): Boolean {
        val root = rootOrNull() ?: return false
        return Files.isDirectory(root)
    }

    override suspend fun buildIndex(deviceId: String): VaultIndexDocument = withContext(Dispatchers.IO) {
        val root = rootOrNull() ?: return@withContext VaultIndexDocument(deviceId, emptyList(), loadTombstones())
        val entries = mutableListOf<VaultFileEntry>()
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                    val name = file.fileName?.toString().orEmpty()
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext !in VAULT_AUDIO_EXTENSIONS) return FileVisitResult.CONTINUE
                    val rel = root.relativize(file).toString().replace('\\', '/').normalizeVaultRelPath()
                    if (rel.isBlank()) return FileVisitResult.CONTINUE
                    val hash = if (hashContent) {
                        runCatching { sha256Bytes(Files.readAllBytes(file)).toHex() }.getOrDefault("")
                    } else {
                        ""
                    }
                    entries += VaultFileEntry(
                        relPath = rel,
                        sizeBytes = attrs.size(),
                        mtimeMs = attrs.lastModifiedTime().toMillis(),
                        contentHash = hash,
                        contentKey = file.readLocalContentKey(),
                    )
                    return FileVisitResult.CONTINUE
                }
            },
        )
        VaultIndexDocument(
            deviceId = deviceId,
            entries = entries.sortedBy { it.relPath },
            tombstones = loadTombstones(),
        )
    }

    override suspend fun readRange(relPath: String, start: Long, endInclusive: Long): ByteArray =
        withContext(Dispatchers.IO) {
            val file = resolve(relPath) ?: return@withContext ByteArray(0)
            if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) return@withContext ByteArray(0)
            val size = Files.size(file)
            if (start < 0L || start >= size || endInclusive < start) return@withContext ByteArray(0)
            // Bounded so the Int narrowing below can never overflow or allocate a whole file.
            val end = minOf(endInclusive, size - 1, start + VAULT_BLOB_CHUNK_BYTES - 1)
            val len = (end - start + 1).toInt()
            FileChannel.open(file, READ, NOFOLLOW_LINKS).use { channel ->
                channel.position(start)
                val buf = ByteBuffer.allocate(len)
                while (buf.hasRemaining() && channel.read(buf) >= 0) { }
                buf.array().copyOf(buf.position())
            }
        }

    override suspend fun writeRange(relPath: String, offset: Long, data: ByteArray, totalSize: Long): Boolean =
        writeCheckedRange(relPath, offset, data, totalSize, "") {}

    override suspend fun writeCheckedRange(
        relPath: String, offset: Long, data: ByteArray, totalSize: Long,
        expectedHash: String, beforePublish: suspend () -> Unit,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val target = resolve(relPath) ?: error("Invalid vault path or root not configured")
            Files.createDirectories(target.parent)
            check(resolve(relPath) == target) { "Vault parent changed" }
            val staging = stagingPath(target)
            val staged = if (Files.isRegularFile(staging, NOFOLLOW_LINKS)) Files.size(staging) else 0L
            try {
                checkVaultChunk(staged, offset, data.size, totalSize)
            } catch (err: IllegalArgumentException) {
                // A gap or overrun means the staged bytes can't be trusted; restart next time.
                Files.deleteIfExists(staging)
                throw err
            }
            // Never pre-extend: the staged length is exactly the contiguous bytes received.
            FileChannel.open(staging, CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
                channel.truncate(offset)
                channel.position(offset)
                val buffer = ByteBuffer.wrap(data)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            val received = Files.size(staging)
            if (received != totalSize) return@withContext false
            beforePublish()
            if (expectedHash.isNotBlank()) {
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                Files.newInputStream(staging, java.nio.file.LinkOption.NOFOLLOW_LINKS).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                check(digest.digest().joinToString("") { "%02x".format(it) } == expectedHash) {
                    "Vault content hash mismatch for $relPath"
                }
            }
            check(resolve(relPath) == target) { "Vault parent changed" }
            stagingPath(target)
            try {
                Files.move(
                    staging,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING)
            }
            true
        }

    override suspend fun stagedSize(relPath: String): Long? = withContext(Dispatchers.IO) {
        val target = resolve(relPath) ?: return@withContext null
        val staging = stagingPath(target)
        if (Files.isRegularFile(staging, NOFOLLOW_LINKS)) Files.size(staging) else null
    }

    override suspend fun discardStaged(relPath: String) = withContext(Dispatchers.IO) {
        val target = resolve(relPath) ?: return@withContext
        Files.deleteIfExists(stagingPath(target))
        Unit
    }

    override suspend fun delete(relPath: String): Boolean = withContext(Dispatchers.IO) {
        val file = resolve(relPath) ?: return@withContext false
        Files.deleteIfExists(file)
    }

    override suspend fun localSize(relPath: String): Long? = withContext(Dispatchers.IO) {
        val file = resolve(relPath) ?: return@withContext null
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) null else Files.size(file)
    }

    override suspend fun loadTombstones(): List<VaultTombstone> = withContext(Dispatchers.IO) {
        if (!Files.isRegularFile(statePath)) return@withContext emptyList()
        runCatching {
            json.decodeFromString<VaultSyncStateDocument>(Files.readString(statePath)).tombstones
        }.getOrDefault(emptyList())
    }

    override suspend fun saveTombstones(tombstones: List<VaultTombstone>): Unit = withContext(Dispatchers.IO) {
        Files.createDirectories(statePath.parent)
        val doc = VaultSyncStateDocument(tombstones = mergeTombstones(tombstones, emptyList()))
        Files.writeString(statePath, json.encodeToString(doc))
    }

    private fun rootOrNull(): Path? {
        val raw = vaultRootProvider()?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return runCatching { Paths.get(raw).toRealPath() }.getOrNull()
    }

    private fun stagingPath(target: Path): Path {
        val staging = target.resolveSibling(vaultStagingName(target.fileName.toString()))
        require(!Files.isSymbolicLink(staging)) { "Symlink vault staging path" }
        return staging
    }

    private fun resolve(relPath: String): Path? {
        val root = rootOrNull() ?: return null
        val path = relPath.normalizeVaultRelPath()
        if (path.isBlank() || path.substringAfterLast('.', "").lowercase() !in VAULT_AUDIO_EXTENSIONS) return null
        val target = root.resolve(path).normalize()
        if (!target.startsWith(root)) return null
        var current = root
        for (part in root.relativize(target)) {
            current = current.resolve(part)
            if (Files.isSymbolicLink(current)) return null
            if (Files.exists(current, NOFOLLOW_LINKS) && !current.toRealPath().startsWith(root)) return null
        }
        return target
    }
}
