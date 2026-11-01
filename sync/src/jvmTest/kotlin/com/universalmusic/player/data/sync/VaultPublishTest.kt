package com.universalmusic.player.data.sync

import com.universalmusic.player.platform.sha256Bytes
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val FILE = "a.flac"
private const val STAGED = "a.flac.kainos-part"
private const val BACKUP = "a.flac.kainos-old"
private const val UNVERIFIED = "a.flac.kainos-unverified"
private val OLD = byteArrayOf(1, 1, 1, 1, 1, 1)
private val NEW = byteArrayOf(2, 2, 2, 2, 2, 2, 2, 2)

private class ProcessDied : RuntimeException("process died")

/**
 * A folder behaving like the SAF external-storage provider: renaming onto a taken name silently
 * picks another name, and a copy reaches the destination in stages. [dieAtStep] kills the process
 * immediately before that mutation: it and every later call fail until [restart].
 */
private class FakeFolder(
    private val refuseRename: (from: String, to: String) -> Boolean = { _, _ -> false },
    var failCopyTo: String? = null,
    var stickyCopyFailure: String? = null,
    private val corruptCopyTo: String? = null,
    private val reportsSize: Boolean = true,
    private val renameThrowsAfterApplying: Boolean = false,
    var dieAtStep: Int = Int.MAX_VALUE,
) : VaultFolder {
    val docs = linkedMapOf<String, ByteArray>()
    val backupWhenFileFirstWritten = mutableListOf<ByteArray?>()
    var steps = 0
        private set
    private var dead = false

    fun restart() {
        dead = false
        dieAtStep = Int.MAX_VALUE
    }

    private fun alive() {
        if (dead) throw ProcessDied()
    }

    private fun step() {
        alive()
        if (++steps == dieAtStep) {
            dead = true
            throw ProcessDied()
        }
    }

    override fun find(name: String): VaultDocument? {
        alive()
        return docs[name]?.let { VaultDocument(if (reportsSize) it.size.toLong() else null) }
    }

    override fun rename(from: String, to: String) {
        step()
        check(!refuseRename(from, to)) { "rename refused" }
        val bytes = docs.remove(from) ?: error("no $from")
        docs[if (to in docs) "$to (1)" else to] = bytes
        check(!renameThrowsAfterApplying) { "rename reported failure" }
    }

    override fun create(name: String) {
        step()
        docs[name] = ByteArray(0)
    }

    override fun copy(from: String, to: String) {
        step()
        val bytes = docs[from] ?: error("no $from")
        if (to == FILE && FILE in docs) backupWhenFileFirstWritten += docs[BACKUP]
        docs[to] = ByteArray(0)
        step()
        docs[to] = bytes.copyOf(bytes.size / 2)
        if (to == failCopyTo) {
            failCopyTo = stickyCopyFailure
            error("copy to $to failed")
        }
        step()
        docs[to] = if (to == corruptCopyTo) bytes.copyOf(bytes.size).also { it[0] = 99 } else bytes
    }

    override fun delete(name: String) {
        step()
        docs.remove(name) ?: error("no $name")
    }

    override fun sha256(name: String): String {
        alive()
        return sha256Bytes(docs[name] ?: error("no $name")).toHex()
    }
}

private fun FakeFolder.recoverAll() {
    docs.keys.toList().mapNotNull(::interruptedVaultFile).toSet().forEach { recoverVaultFile(this, it) }
}

/** The file is exactly one of [acceptable] (null: absent) and only staged bytes may remain beside it. */
private fun assertSettled(folder: FakeFolder, vararg acceptable: ByteArray?) {
    val file = folder.docs[FILE]
    assertTrue(acceptable.any { (it == null && file == null) || (it != null && file != null && it.contentEquals(file)) },
        "file holds ${file?.toList()}")
    assertEquals(emptySet(), folder.docs.keys - setOf(FILE, STAGED))
}

class VaultPublishTest {
    private val everyRenameRefused: (String, String) -> Boolean = { _, _ -> true }
    private val existingFileRenameRefused: (String, String) -> Boolean = { from, _ -> from == FILE }

    private fun folder(
        old: ByteArray? = OLD,
        refuseRename: (String, String) -> Boolean = { _, _ -> false },
        failCopyTo: String? = null,
        corruptCopyTo: String? = null,
        reportsSize: Boolean = true,
        renameThrowsAfterApplying: Boolean = false,
    ) = FakeFolder(
        refuseRename = refuseRename,
        failCopyTo = failCopyTo,
        corruptCopyTo = corruptCopyTo,
        reportsSize = reportsSize,
        renameThrowsAfterApplying = renameThrowsAfterApplying,
    ).apply {
        old?.let { docs[FILE] = it }
        docs[STAGED] = NEW
    }

    private fun FakeFolder.publish() = publishVaultFile(this, FILE, NEW.size.toLong())

    @Test
    fun renamingProviderReplacesTheFileAndLeavesNothingBehind() {
        val folder = folder()
        folder.publish()
        assertContentEquals(NEW, folder.docs[FILE])
        assertEquals(setOf(FILE), folder.docs.keys)
    }

    @Test
    fun newFileNeedsNoBackup() {
        val folder = folder(old = null)
        folder.publish()
        assertContentEquals(NEW, folder.docs[FILE])
        assertEquals(setOf(FILE), folder.docs.keys)
    }

    @Test
    fun refusedRenameBacksUpTheGoodFileBeforeWritingOverIt() {
        val folder = folder(refuseRename = existingFileRenameRefused)
        folder.publish()
        assertContentEquals(OLD, assertNotNull(folder.backupWhenFileFirstWritten.single()))
        assertContentEquals(NEW, folder.docs[FILE])
        assertEquals(setOf(FILE), folder.docs.keys)
    }

    @Test
    fun providerWithNoRenameAtAllStillPublishes() {
        val folder = folder(refuseRename = everyRenameRefused, reportsSize = false)
        folder.publish()
        assertContentEquals(NEW, folder.docs[FILE])
        assertEquals(setOf(FILE), folder.docs.keys)
    }

    @Test
    fun renameThatThrowsAfterApplyingStillPublishesOnce() {
        val folder = folder(renameThrowsAfterApplying = true)
        folder.publish()
        assertContentEquals(NEW, folder.docs[FILE])
        assertEquals(setOf(FILE), folder.docs.keys)
    }

    @Test
    fun copyFailingHalfwayRestoresTheFileAndKeepsTheStagedBytes() {
        val folder = folder(refuseRename = existingFileRenameRefused, failCopyTo = FILE)
        assertFailsWith<IllegalStateException> { folder.publish() }
        assertContentEquals(OLD, folder.docs[FILE])
        assertContentEquals(NEW, folder.docs[STAGED])
        assertEquals(setOf(FILE, STAGED), folder.docs.keys)
    }

    @Test
    fun restoreThatAlsoFailsKeepsTheBackupForTheNextRecovery() {
        val folder = folder(refuseRename = existingFileRenameRefused, failCopyTo = FILE).apply {
            stickyCopyFailure = FILE
        }
        assertFailsWith<IllegalStateException> { folder.publish() }
        assertContentEquals(OLD, folder.docs[BACKUP])
        assertTrue(UNVERIFIED in folder.docs)

        folder.stickyCopyFailure = null
        folder.failCopyTo = null
        folder.recoverAll()
        assertContentEquals(OLD, folder.docs[FILE])
        assertEquals(setOf(FILE, STAGED), folder.docs.keys)
    }

    @Test
    fun backupCopyFailingHalfwayLeavesTheFileUntouched() {
        val folder = folder(refuseRename = existingFileRenameRefused, failCopyTo = BACKUP)
        assertFailsWith<IllegalStateException> { folder.publish() }
        assertContentEquals(OLD, folder.docs[FILE])
        assertContentEquals(NEW, folder.docs[STAGED])
        assertEquals(setOf(FILE, STAGED), folder.docs.keys)
    }

    @Test
    fun corruptBackupIsNeverTrusted() {
        val folder = folder(refuseRename = existingFileRenameRefused, corruptCopyTo = BACKUP)
        assertFailsWith<IllegalStateException> { folder.publish() }
        assertContentEquals(OLD, folder.docs[FILE])
        assertTrue(folder.backupWhenFileFirstWritten.isEmpty(), "the file was never opened for writing")
        assertEquals(setOf(FILE, STAGED), folder.docs.keys)
    }

    @Test
    fun corruptCopyOverTheFileRollsBack() {
        val folder = folder(refuseRename = existingFileRenameRefused, corruptCopyTo = FILE)
        assertFailsWith<IllegalStateException> { folder.publish() }
        assertContentEquals(OLD, folder.docs[BACKUP], "this provider corrupts the restore too, so the backup stays")
        assertTrue(UNVERIFIED in folder.docs)
    }

    @Test
    fun publishedSizeMismatchRollsBack() {
        val folder = folder()
        assertFailsWith<IllegalStateException> { publishVaultFile(folder, FILE, NEW.size + 1L) }
        assertContentEquals(OLD, folder.docs[FILE])
        assertEquals(setOf(FILE), folder.docs.keys)
    }

    @Test
    fun failedPublishOfANewFileLeavesNoPartialFile() {
        val folder = folder(old = null, refuseRename = everyRenameRefused, failCopyTo = FILE)
        assertFailsWith<IllegalStateException> { folder.publish() }
        assertNull(folder.docs[FILE])
        assertContentEquals(NEW, folder.docs[STAGED])
        assertEquals(setOf(STAGED), folder.docs.keys)
    }

    @Test
    fun recoveryRestoresABackupStrandedByAnOlderAppVersion() {
        val folder = FakeFolder().apply { docs[BACKUP] = OLD }
        folder.recoverAll()
        assertContentEquals(OLD, folder.docs[FILE])
        assertEquals(setOf(FILE), folder.docs.keys)
    }

    @Test
    fun recoveryDropsABackupThatTheCompleteFileReplaced() {
        val folder = FakeFolder().apply {
            docs[FILE] = NEW
            docs[BACKUP] = OLD
        }
        folder.recoverAll()
        assertContentEquals(NEW, folder.docs[FILE])
        assertEquals(setOf(FILE), folder.docs.keys)
    }

    @Test
    fun recoveryLeavesAPlainPartialDownloadAlone() {
        val folder = FakeFolder().apply {
            docs[FILE] = OLD
            docs[STAGED] = byteArrayOf(2)
        }
        folder.recoverAll()
        assertContentEquals(OLD, folder.docs[FILE])
        assertContentEquals(byteArrayOf(2), folder.docs[STAGED])
    }

    @Test
    fun onlyPublishLeftoversMarkAnInterruptedFile() {
        assertEquals(FILE, interruptedVaultFile(BACKUP))
        assertEquals(FILE, interruptedVaultFile(UNVERIFIED))
        assertNull(interruptedVaultFile(FILE))
        assertNull(interruptedVaultFile(STAGED))
    }

    @Test
    fun publishLeftoversAreNeverIndexedAsAudio() {
        for (name in listOf(STAGED, BACKUP, UNVERIFIED)) {
            assertFalse(name.substringAfterLast('.').lowercase() in VAULT_AUDIO_EXTENSIONS, name)
        }
    }

    private data class Provider(val label: String, val old: ByteArray?, val refuse: (String, String) -> Boolean)

    private val providers = listOf(
        Provider("rename, replace", OLD) { _, _ -> false },
        Provider("rename, new file", null) { _, _ -> false },
        Provider("file rename refused, replace", OLD, existingFileRenameRefused),
        Provider("no rename, replace", OLD, everyRenameRefused),
        Provider("no rename, new file", null, everyRenameRefused),
    )

    private inline fun forEveryDeath(makeFolder: () -> FakeFolder, check: (FakeFolder, Int) -> Unit): Int {
        var step = 1
        while (true) {
            val folder = makeFolder()
            folder.dieAtStep = step
            val died = runCatching { folder.publish() }.exceptionOrNull()
            if (died == null) return step - 1
            check(folder, step)
            step += 1
        }
    }

    @Test
    fun deathAtEveryPublishStepRecoversToACompleteFileAndThenRetries() {
        for (provider in providers) {
            val deaths = forEveryDeath({ folder(provider.old, provider.refuse) }) { folder, step ->
                folder.restart()
                folder.recoverAll()
                assertSettled(folder, provider.old, NEW)
                folder.docs[STAGED] = NEW
                folder.publish()
                assertContentEquals(NEW, folder.docs[FILE], "${provider.label} step $step")
                assertEquals(setOf(FILE), folder.docs.keys, "${provider.label} step $step")
            }
            assertTrue(deaths >= 3, "${provider.label} covered only $deaths steps")
        }
    }

    @Test
    fun deathDuringRecoveryStillConverges() {
        for (provider in providers) {
            forEveryDeath({ folder(provider.old, provider.refuse) }) { interrupted, _ ->
                val recoverySteps = interrupted.docs.size + 16
                val leftover = interrupted.docs.mapValues { it.value.copyOf() }
                for (step in 1..recoverySteps) {
                    val folder = FakeFolder(provider.refuse).apply { docs.putAll(leftover) }
                    folder.dieAtStep = step
                    runCatching { folder.recoverAll() }
                    folder.restart()
                    folder.recoverAll()
                    assertSettled(folder, provider.old, NEW)
                }
            }
        }
    }

    @Test
    fun transferOverARefusingProviderFailsCleanlyThenRetrySucceeds() = runTest {
        val folder = FakeFolder(refuseRename = existingFileRenameRefused, failCopyTo = FILE)
        folder.docs[FILE] = OLD
        val store = FolderVaultStore(folder)
        val transfer = PendingVaultTransfer(FILE, VaultCopyDirection.TO_LOCAL, NEW.size.toLong())
        val client = FakeVaultClient(NEW, sourcePath = FILE)

        assertFailsWith<IllegalStateException> { executeVaultTransfer(store, client, transfer) { _, _, _ -> } }
        assertContentEquals(OLD, folder.docs[FILE])

        executeVaultTransfer(store, client, transfer) { _, _, _ -> }
        assertContentEquals(NEW, folder.docs[FILE])
        assertEquals(setOf(FILE), folder.docs.keys)
    }
}

/** The part of the Android store above [publishVaultFile]: chunks to staging, publish at the end. */
private class FolderVaultStore(private val folder: FakeFolder) : HomeLanVaultStore {
    override fun isConfigured() = true
    override suspend fun buildIndex(deviceId: String) = VaultIndexDocument(deviceId)
    override suspend fun readRange(relPath: String, start: Long, endInclusive: Long) = ByteArray(0)
    override suspend fun delete(relPath: String) = folder.docs.remove(relPath) != null
    override suspend fun localSize(relPath: String) = folder.docs[relPath]?.size?.toLong()
    override suspend fun loadTombstones() = emptyList<VaultTombstone>()
    override suspend fun saveTombstones(tombstones: List<VaultTombstone>) = Unit

    override suspend fun writeRange(relPath: String, offset: Long, data: ByteArray, totalSize: Long): Boolean {
        val staging = vaultStagingName(relPath)
        folder.docs[staging] = (folder.docs[staging] ?: ByteArray(0)).copyOf(offset.toInt()) + data
        if (folder.docs.getValue(staging).size.toLong() != totalSize) return false
        publishVaultFile(folder, relPath, totalSize)
        return true
    }
}
