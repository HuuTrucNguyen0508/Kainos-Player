package com.universalmusic.player

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.universalmusic.player.data.sync.AndroidHomeLanVaultStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidVaultSafPublishTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val resolver = context.contentResolver
    private val authority = "com.android.externalstorage.documents"

    @Test
    fun publishesAndRecoversExactBytesThroughExternalStorageProvider() = runBlocking {
        val folder = "Music/kainos-saf-test-${UUID.randomUUID()}"
        val tree = DocumentsContract.buildTreeDocumentUri(authority, "primary:$folder")
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        // Shell only prepares a unique empty directory. All file operations below use SAF.
        instrumentation.uiAutomation.executeShellCommand("mkdir -p /sdcard/$folder").use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
        // Keep the app's startup permission prompt from covering the returning picker.
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.READ_MEDIA_AUDIO)
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                        putExtra(DocumentsContract.EXTRA_INITIAL_URI, root)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                    }, 9071)
                }
                val device = UiDevice.getInstance(instrumentation)
                assertNotNull("Picker did not open the test folder", device.wait(
                    Until.findObject(By.text(folder.substringAfterLast('/'))), 15_000))
                val use = device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("Use this folder", 2))), 10_000)
                assertNotNull("Missing Use this folder", use)
                use.click()
                val allow = device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("Allow", 2))), 10_000)
                assertNotNull("Missing Allow confirmation", allow)
                allow.click()
                assertTrue("Picker did not return to Kainos", device.wait(Until.hasObject(By.pkg(context.packageName)), 10_000))
                instrumentation.waitForIdleSync()
                resolver.takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                assertTrue(resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission })
                assertEquals(authority, root.authority)
                Log.i("VaultSafTest", "API ${Build.VERSION.SDK_INT}; provider=$authority; tree=$tree")
                val store = AndroidHomeLanVaultStore(context, vaultRootProvider = { tree.toString() })
                assertTrue(store.isConfigured())
                val old = byteArrayOf(0, 1, 2, 127, -128, -1, 42)
                val replacement = byteArrayOf(-1, 0, 7, 8, 9, 10, 11, 12, 13)

                // A process died after moving the old file aside, before creating the marker.
                val original = create(root, "song.aac", old)
                DocumentsContract.renameDocument(resolver, original, "song.aac.kainos-old")
                assertEquals(setOf("song.aac.kainos-old"), names(tree))
                assertEquals(listOf("song.aac"), store.buildIndex("saf-test").entries.map { it.relPath })
                assertArrayEquals("Backup-only AAC recovery", old, store.readRange("song.aac", 0, 100))
                assertEquals(setOf("song.aac"), names(tree))
                assertTrue(store.delete("song.aac"))

                for (name in listOf("song.aac", "song.flac", "song.mp3")) {
                    assertFalse(store.writeRange(name, 0, old.copyOfRange(0, 3), old.size.toLong()))
                    assertTrue(names(tree).contains("$name.kainos-part"))
                    assertFalse(store.buildIndex("saf-test").entries.any { it.relPath.endsWith(".kainos-part") })
                    assertTrue(store.writeRange(name, 3, old.copyOfRange(3, old.size), old.size.toLong()))
                    assertArrayEquals(name, old, store.readRange(name, 0, 100))
                }
                val expectedNames = setOf("song.aac", "song.flac", "song.mp3")
                assertEquals(expectedNames, names(tree))
                assertTrue(store.writeCheckedRange("song.aac", 0, replacement, replacement.size.toLong(), "") {
                    assertEquals(expectedNames + "song.aac.kainos-part", names(tree))
                    assertArrayEquals(old, store.readRange("song.aac", 0, 100))
                })
                assertArrayEquals(replacement, store.readRange("song.aac", 0, 100))
                assertEquals(expectedNames, names(tree))

                // A process died during the copy: a complete backup, marker, and partial target.
                val current = document(tree, "song.aac")
                DocumentsContract.renameDocument(resolver, current, "song.aac.kainos-old")
                create(root, "song.aac.kainos-unverified", byteArrayOf())
                create(root, "song.aac", byteArrayOf(3))
                create(root, "song.aac.kainos-part", byteArrayOf(4, 5))
                assertEquals(expectedNames + setOf("song.aac.kainos-old", "song.aac.kainos-unverified", "song.aac.kainos-part"), names(tree))
                val index = store.buildIndex("saf-test")
                assertEquals(expectedNames, index.entries.map { it.relPath }.toSet())
                assertEquals(replacement.size.toLong(), index.entries.single { it.relPath == "song.aac" }.sizeBytes)
                assertArrayEquals(replacement, store.readRange("song.aac", 0, 100))
                assertEquals(expectedNames + "song.aac.kainos-part", names(tree))
                store.discardStaged("song.aac")
                assertEquals(expectedNames, names(tree))
            }
        } finally {
            try {
                if (resolver.persistedUriPermissions.any { it.uri == tree }) {
                    assertTrue("Failed to delete test tree", DocumentsContract.deleteDocument(resolver, root))
                    // ExternalStorageProvider revokes the grant when its tree is deleted.
                    if (resolver.persistedUriPermissions.any { it.uri == tree }) {
                        resolver.releasePersistableUriPermission(tree,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    }
                }
            } finally {
                instrumentation.uiAutomation.executeShellCommand("rmdir /sdcard/$folder").use {
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
                }
            }
        }
    }

    private fun create(parent: Uri, name: String, bytes: ByteArray): Uri {
        val uri = requireNotNull(DocumentsContract.createDocument(resolver, parent, "application/octet-stream", name))
        resolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
        return uri
    }

    private fun children(tree: Uri): Map<String, Uri> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)!!.use { cursor ->
            buildMap {
                while (cursor.moveToNext()) put(cursor.getString(0), DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(1)))
            }
        }
    }

    private fun names(tree: Uri) = children(tree).keys
    private fun document(tree: Uri, name: String) = children(tree).getValue(name)
}
