package com.universalmusic.player.data.sync

import com.universalmusic.player.platform.sha256Bytes
import java.io.RandomAccessFile
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
) : HomeLanVaultStore {
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
            if (!Files.isRegularFile(file)) return@withContext ByteArray(0)
            val size = Files.size(file)
            if (start < 0L || start >= size || endInclusive < start) return@withContext ByteArray(0)
            // Bounded so the Int narrowing below can never overflow or allocate a whole file.
            val end = minOf(endInclusive, size - 1, start + VAULT_BLOB_CHUNK_BYTES - 1)
            val len = (end - start + 1).toInt()
            RandomAccessFile(file.toFile(), "r").use { raf ->
                raf.seek(start)
                val buf = ByteArray(len)
                var read = 0
                while (read < len) {
                    val n = raf.read(buf, read, len - read)
                    if (n < 0) break
                    read += n
                }
                if (read == len) buf else buf.copyOf(read)
            }
        }

    override suspend fun writeRange(relPath: String, offset: Long, data: ByteArray, totalSize: Long): Boolean =
        withContext(Dispatchers.IO) {
            val target = resolve(relPath) ?: error("Invalid vault path or root not configured")
            Files.createDirectories(target.parent)
            val staging = stagingPath(target)
            val staged = if (Files.isRegularFile(staging)) Files.size(staging) else 0L
            try {
                checkVaultChunk(staged, offset, data.size, totalSize)
            } catch (err: IllegalArgumentException) {
                // A gap or overrun means the staged bytes can't be trusted; restart next time.
                Files.deleteIfExists(staging)
                throw err
            }
            // Never pre-extend: the staged length is exactly the contiguous bytes received.
            RandomAccessFile(staging.toFile(), "rw").use { raf ->
                raf.setLength(offset)
                raf.seek(offset)
                raf.write(data)
                raf.fd.sync()
            }
            val received = Files.size(staging)
            if (received != totalSize) return@withContext false
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
        if (Files.isRegularFile(staging)) Files.size(staging) else null
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
        if (!Files.isRegularFile(file)) null else Files.size(file)
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
        return runCatching { Paths.get(raw).toAbsolutePath().normalize() }.getOrNull()
    }

    private fun stagingPath(target: Path): Path =
        target.resolveSibling(vaultStagingName(target.fileName.toString()))

    private fun resolve(relPath: String): Path? {
        val root = rootOrNull() ?: return null
        val path = relPath.normalizeVaultRelPath()
        if (path.isBlank()) return null
        val target = root.resolve(path).normalize()
        if (!target.startsWith(root)) return null
        return target
    }
}
