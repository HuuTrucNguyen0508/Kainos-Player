package com.universalmusic.player.data.sync

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AndroidHomeLanVaultStore(
    context: Context,
    private val vaultRootProvider: () -> String?,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : HomeLanVaultStore {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val stateFile = File(appContext.filesDir, "vault-sync-state.json")

    override fun isConfigured(): Boolean {
        val tree = treeUri() ?: return false
        val root = DocumentFile.fromTreeUri(appContext, tree) ?: return false
        if (!root.canRead()) return false
        val hasWrite = resolver.persistedUriPermissions.any {
            it.uri == tree && it.isWritePermission
        }
        return hasWrite || root.canWrite()
    }

    override suspend fun buildIndex(deviceId: String): VaultIndexDocument = withContext(Dispatchers.IO) {
        val tree = treeUri() ?: return@withContext VaultIndexDocument(deviceId, emptyList(), loadTombstones())
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val entries = mutableListOf<VaultFileEntry>()
        walk(tree, rootId, prefix = "", into = entries)
        VaultIndexDocument(
            deviceId = deviceId,
            entries = entries.sortedBy { it.relPath },
            tombstones = loadTombstones(),
        )
    }

    override suspend fun readRange(relPath: String, start: Long, endInclusive: Long): ByteArray =
        withContext(Dispatchers.IO) {
            val doc = findDocument(relPath) ?: return@withContext ByteArray(0)
            val pfd = resolver.openFileDescriptor(doc, "r") ?: return@withContext ByteArray(0)
            pfd.use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                    val size = channel.size()
                    if (start >= size) return@withContext ByteArray(0)
                    val end = minOf(endInclusive, size - 1)
                    val len = (end - start + 1).toInt()
                    val buf = ByteArray(len)
                    channel.position(start)
                    var read = 0
                    while (read < len) {
                        val n = channel.read(ByteBuffer.wrap(buf, read, len - read))
                        if (n < 0) break
                        read += n
                    }
                    if (read == len) buf else buf.copyOf(read)
                }
            }
        }

    override suspend fun writeRange(relPath: String, offset: Long, data: ByteArray, totalSize: Long) =
        withContext(Dispatchers.IO) {
            val path = relPath.normalizeVaultRelPath()
            require(path.isNotBlank()) { "Blank vault path" }
            val tree = treeUri() ?: error("Vault root not configured")
            val docUri = ensureDocument(tree, path, create = true)
                ?: error("Cannot create $path in vault")
            val mode = if (offset == 0L) "wt" else "rw"
            val pfd = resolver.openFileDescriptor(docUri, mode)
                ?: error("Cannot open $path for write")
            pfd.use { descriptor ->
                FileOutputStream(descriptor.fileDescriptor).channel.use { channel ->
                    if (offset == 0L && totalSize > 0) {
                        runCatching { channel.truncate(totalSize) }
                    }
                    channel.position(offset)
                    channel.write(ByteBuffer.wrap(data))
                    channel.force(true)
                }
            }
        }

    override suspend fun delete(relPath: String): Boolean = withContext(Dispatchers.IO) {
        val doc = findDocument(relPath) ?: return@withContext false
        DocumentsContract.deleteDocument(resolver, doc)
    }

    override suspend fun localSize(relPath: String): Long? = withContext(Dispatchers.IO) {
        val doc = findDocument(relPath) ?: return@withContext null
        resolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val idx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                if (idx < 0 || cursor.isNull(idx)) null else cursor.getLong(idx)
            }
    }

    override suspend fun loadTombstones(): List<VaultTombstone> = withContext(Dispatchers.IO) {
        if (!stateFile.isFile) return@withContext emptyList()
        runCatching {
            json.decodeFromString<VaultSyncStateDocument>(stateFile.readText()).tombstones
        }.getOrDefault(emptyList())
    }

    override suspend fun saveTombstones(tombstones: List<VaultTombstone>) = withContext(Dispatchers.IO) {
        val doc = VaultSyncStateDocument(tombstones = mergeTombstones(tombstones, emptyList()))
        stateFile.writeText(json.encodeToString(doc))
    }

    private fun treeUri(): Uri? {
        val raw = vaultRootProvider()?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return runCatching { Uri.parse(raw) }.getOrNull()
    }

    private fun walk(
        treeUri: Uri,
        documentId: String,
        prefix: String,
        into: MutableList<VaultFileEntry>,
    ) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val children = queryChildren(childrenUri) ?: return
        for (child in children) {
            val name = child.displayName
            if (name.isBlank() || name == "." || name == "..") continue
            val rel = if (prefix.isEmpty()) name else "$prefix/$name"
            if (child.isDirectory) {
                walk(treeUri, child.documentId, rel.normalizeVaultRelPath(), into)
            } else {
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext !in VAULT_AUDIO_EXTENSIONS) continue
                into += VaultFileEntry(
                    relPath = rel.normalizeVaultRelPath(),
                    sizeBytes = child.size ?: 0L,
                    mtimeMs = child.lastModified ?: 0L,
                    contentHash = "",
                )
            }
        }
    }

    private fun findDocument(relPath: String): Uri? {
        val tree = treeUri() ?: return null
        return ensureDocument(tree, relPath.normalizeVaultRelPath(), create = false)
    }

    private fun ensureDocument(treeUri: Uri, relPath: String, create: Boolean): Uri? {
        val parts = relPath.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        var parentId = DocumentsContract.getTreeDocumentId(treeUri)
        for ((index, part) in parts.withIndex()) {
            val isLast = index == parts.lastIndex
            val existing = findChild(treeUri, parentId, part)
            if (existing != null) {
                if (isLast) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, existing.documentId)
                }
                parentId = existing.documentId
                continue
            }
            if (!create) return null
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
            if (isLast) {
                val mime = mimeForAudioName(part)
                val created = DocumentsContract.createDocument(resolver, parentUri, mime, part)
                    ?: return null
                return created
            }
            val dir = DocumentsContract.createDocument(
                resolver,
                parentUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                part,
            ) ?: return null
            parentId = DocumentsContract.getDocumentId(dir)
        }
        return null
    }

    private fun findChild(treeUri: Uri, parentId: String, displayName: String): ChildDoc? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        return queryChildren(childrenUri)?.firstOrNull { it.displayName == displayName }
    }

    private fun queryChildren(childrenUri: Uri): List<ChildDoc>? {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        return resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        ChildDoc(
                            documentId = cursor.string(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                            displayName = cursor.string(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                            mimeType = cursor.string(DocumentsContract.Document.COLUMN_MIME_TYPE),
                            size = cursor.longOrNull(DocumentsContract.Document.COLUMN_SIZE),
                            lastModified = cursor.longOrNull(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                        ),
                    )
                }
            }
        }
    }

    private fun Cursor.string(column: String): String {
        val idx = getColumnIndexOrThrow(column)
        return if (isNull(idx)) "" else getString(idx).orEmpty()
    }

    private fun Cursor.longOrNull(column: String): Long? {
        val idx = getColumnIndex(column)
        if (idx < 0 || isNull(idx)) return null
        return getLong(idx)
    }

    private fun mimeForAudioName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "m4a", "aac" -> "audio/mp4"
            "ogg", "oga", "opus" -> "audio/ogg"
            "wav", "wave" -> "audio/wav"
            else -> "audio/*"
        }
    }

    private data class ChildDoc(
        val documentId: String,
        val displayName: String,
        val mimeType: String,
        val size: Long?,
        val lastModified: Long?,
    ) {
        val isDirectory: Boolean
            get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
    }
}
