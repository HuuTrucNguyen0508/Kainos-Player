package com.universalmusic.player.data.library

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Home sync of portable pins: the phone posts its pins, the hub merges and replies, the phone merges the reply. */
class HomePinSyncTest {
    private var now = 1_000L

    private class Device(val library: LibraryRepository, val playlists: MutableSet<String>)

    private suspend fun device(id: String, spotifyAccount: String? = null, playlists: Set<String> = emptySet()) = Device(
        LibraryRepository(clock = { now }, deviceIdProvider = { id }).apply { setSpotifyAccountId(spotifyAccount) },
        playlists.toMutableSet(),
    )

    /** One Sync now from [phone] to the hub [pc], replying under the phone's Spotify account as the hub route does. */
    private suspend fun sync(phone: Device, pc: Device) {
        now += 10
        val sent = phone.library.exportHomePinsSyncDocument()
        val reply = pc.library.mergeAndPersistHomePins(sent, pc.playlists).forPeerSpotifyAccount(sent.spotifyAccountId)
        phone.library.mergeAndPersistHomePins(reply, phone.playlists)
    }

    private fun Device.pins() = library.homePins.value.map { it.targetId }

    private fun Device.pinKainos(id: String) {
        now += 10
        library.pinHome(HomePinKind.KAINOS_PLAYLIST, "kainos:playlist:$id", title = id)
    }

    private fun Device.pinSpotify(id: String) {
        now += 10
        library.pinHome(
            HomePinKind.PROVIDER_PLAYLIST, "spotify-playlist:$id", title = id, providerEntityId = id, provider = "SPOTIFY",
        )
    }

    private fun Device.pinFolder(path: String) {
        now += 10
        library.pinHome(HomePinKind.LOCAL_FOLDER, path, title = path)
    }

    private fun Device.unpin(targetId: String) {
        now += 10
        library.unpinHome(library.homePins.value.single { it.targetId == targetId }.id)
    }

    private fun Device.move(targetId: String, delta: Int) {
        now += 10
        library.moveHomePin(library.homePins.value.single { it.targetId == targetId }.id, delta)
    }

    private val shared = setOf("kainos:playlist:a", "kainos:playlist:b", "kainos:playlist:c")

    @Test
    fun pinsCrossBothWays() = runTest {
        val phone = device("phone", playlists = shared)
        val pc = device("pc", playlists = shared)
        phone.pinKainos("a")
        pc.pinKainos("b")

        sync(phone, pc)

        assertEquals(listOf("kainos:playlist:b", "kainos:playlist:a"), pc.pins())
        assertEquals(listOf("kainos:playlist:a", "kainos:playlist:b"), phone.pins())
    }

    @Test
    fun unpinCrossesAndAnOlderPinDoesNotBringItBack() = runTest {
        val phone = device("phone", playlists = shared)
        val pc = device("pc", playlists = shared)
        phone.pinKainos("a")
        phone.pinKainos("b")
        sync(phone, pc)

        pc.unpin("kainos:playlist:a")
        sync(phone, pc)

        assertEquals(listOf("kainos:playlist:b"), phone.pins())
        assertEquals(listOf("kainos:playlist:b"), pc.pins())

        phone.unpin("kainos:playlist:b")
        sync(phone, pc)
        assertEquals(emptyList(), pc.pins())
        assertEquals(emptyList(), phone.pins())
    }

    @Test
    fun pinningAgainAfterAnUnpinWins() = runTest {
        val phone = device("phone", playlists = shared)
        val pc = device("pc", playlists = shared)
        phone.pinKainos("a")
        sync(phone, pc)
        pc.unpin("kainos:playlist:a")
        sync(phone, pc)

        phone.pinKainos("a")
        sync(phone, pc)

        assertEquals(listOf("kainos:playlist:a"), pc.pins())
        assertEquals(listOf("kainos:playlist:a"), phone.pins())
    }

    @Test
    fun reorderCrossesBothWaysAndTheLaterReorderWins() = runTest {
        val phone = device("phone", playlists = shared)
        val pc = device("pc", playlists = shared)
        phone.pinKainos("a")
        phone.pinKainos("b")
        phone.pinKainos("c")
        sync(phone, pc)

        phone.move("kainos:playlist:c", -2)
        sync(phone, pc)
        assertEquals(listOf("kainos:playlist:c", "kainos:playlist:a", "kainos:playlist:b"), pc.pins())

        pc.move("kainos:playlist:a", 2)
        sync(phone, pc)
        assertEquals(listOf("kainos:playlist:c", "kainos:playlist:b", "kainos:playlist:a"), phone.pins())

        // Both reorder before the next sync: the later reorder (the phone's) wins on both.
        pc.move("kainos:playlist:b", -1)
        phone.move("kainos:playlist:a", -2)
        sync(phone, pc)
        assertEquals(listOf("kainos:playlist:a", "kainos:playlist:c", "kainos:playlist:b"), phone.pins())
        assertEquals(listOf("kainos:playlist:a", "kainos:playlist:c", "kainos:playlist:b"), pc.pins())
    }

    @Test
    fun aPinAddedAfterThePeersReorderKeepsThatReorder() = runTest {
        val phone = device("phone", playlists = shared)
        val pc = device("pc", playlists = shared)
        phone.pinKainos("a")
        phone.pinKainos("b")
        sync(phone, pc)

        pc.move("kainos:playlist:b", -1)
        phone.pinKainos("c")
        sync(phone, pc)

        assertEquals(listOf("kainos:playlist:b", "kainos:playlist:a", "kainos:playlist:c"), phone.pins())
        assertEquals(listOf("kainos:playlist:b", "kainos:playlist:a", "kainos:playlist:c"), pc.pins())
    }

    @Test
    fun albumAndFolderPinsStayOnTheirDeviceAndKeepTheirPlace() = runTest {
        val phone = device("phone", playlists = shared)
        val pc = device("pc", playlists = shared)
        phone.pinFolder("/phone/Music")
        phone.pinKainos("a")
        phone.library.pinHome(HomePinKind.ALBUM, "local-album:x", title = "X")
        pc.pinFolder("/pc/Music")
        pc.pinKainos("b")

        sync(phone, pc)

        assertEquals(listOf("/pc/Music", "kainos:playlist:b", "kainos:playlist:a"), pc.pins())
        assertEquals(listOf("/phone/Music", "kainos:playlist:a", "local-album:x", "kainos:playlist:b"), phone.pins())
        phone.unpin("/phone/Music")
        assertEquals(emptyList(), phone.library.exportHomePinsSyncDocument().tombstones)
    }

    @Test
    fun aPinWhosePlaylistIsMissingWaitsForThePlaylist() = runTest {
        val phone = device("phone", playlists = setOf("kainos:playlist:new"))
        val pc = device("pc")
        phone.pinKainos("new")

        sync(phone, pc)
        assertEquals(emptyList(), pc.pins())
        assertEquals(listOf("kainos:playlist:new"), phone.pins())

        pc.playlists += "kainos:playlist:new"
        sync(phone, pc)
        assertEquals(listOf("kainos:playlist:new"), pc.pins())
    }

    @Test
    fun spotifyPinsCrossOnlyBetweenTheSameAccount() = runTest {
        val phone = device("phone", spotifyAccount = "account-a")
        val sameAccountPc = device("pc", spotifyAccount = "account-a")
        phone.pinSpotify("mine")
        sameAccountPc.pinSpotify("pc")
        sync(phone, sameAccountPc)
        assertEquals(listOf("spotify-playlist:pc", "spotify-playlist:mine"), sameAccountPc.pins())
        assertEquals(listOf("spotify-playlist:mine", "spotify-playlist:pc"), phone.pins())

        val otherAccountPc = device("pc2", spotifyAccount = "account-b")
        otherAccountPc.pinSpotify("other")
        otherAccountPc.pinKainos("a")
        otherAccountPc.playlists += "kainos:playlist:a"
        phone.playlists += "kainos:playlist:a"
        phone.unpin("spotify-playlist:pc")
        sync(phone, otherAccountPc)
        assertEquals(listOf("spotify-playlist:other", "kainos:playlist:a"), otherAccountPc.pins())
        assertEquals(listOf("spotify-playlist:mine", "kainos:playlist:a"), phone.pins())

        val signedOutPc = device("pc3", playlists = setOf("kainos:playlist:a"))
        sync(phone, signedOutPc)
        assertEquals(listOf("kainos:playlist:a"), signedOutPc.pins())
        assertEquals(listOf("spotify-playlist:mine", "kainos:playlist:a"), phone.pins())
    }

    @Test
    fun hubReplyCarriesSpotifyPinsOnlyToTheSameAccount() = runTest {
        val pc = device("pc", spotifyAccount = "account-a")
        pc.pinSpotify("hub")
        pc.unpin("spotify-playlist:hub")
        pc.pinSpotify("kept")
        val doc = pc.library.exportHomePinsSyncDocument()

        assertEquals(listOf("spotify-playlist:kept"), doc.forPeerSpotifyAccount("account-a").pins.map { it.targetId })
        assertEquals(listOf("spotify-playlist:hub"), doc.forPeerSpotifyAccount("account-a").tombstones.map { it.targetId })
        assertEquals(emptyList(), doc.forPeerSpotifyAccount("account-b").pins)
        assertEquals(emptyList(), doc.forPeerSpotifyAccount("account-b").tombstones)
        assertEquals(emptyList(), doc.forPeerSpotifyAccount(null).pins)
    }

    @Test
    fun aNewerOrderWaitingForAPlaylistWinsOnceThePlaylistArrives() = runTest {
        val phone = device("phone", playlists = setOf("kainos:playlist:a", "kainos:playlist:b"))
        val pc = device("pc", playlists = setOf("kainos:playlist:a"))
        phone.pinKainos("a")
        phone.pinKainos("b")
        phone.move("kainos:playlist:b", -1)
        sync(phone, pc)
        assertEquals(listOf("kainos:playlist:a"), pc.pins())

        pc.playlists += "kainos:playlist:b"
        sync(phone, pc)
        sync(phone, pc)

        assertEquals(listOf("kainos:playlist:b", "kainos:playlist:a"), pc.pins())
        assertEquals(listOf("kainos:playlist:b", "kainos:playlist:a"), phone.pins())
    }

    @Test
    fun anUnpinMadeWhileAMergeRunsIsKept() = runTest {
        val pc = device("pc", playlists = shared)
        pc.pinKainos("a")
        val phone = device("phone", playlists = shared)
        phone.pinKainos("b")
        var unpinned = false
        // The pc user unpins "a" while the merge is reading the pc's pins.
        val playlistsThatUnpinOnFirstLookup = object : AbstractSet<String>() {
            override val size get() = shared.size
            override fun iterator() = shared.iterator()
            override fun contains(element: String): Boolean {
                if (!unpinned) {
                    unpinned = true
                    pc.unpin("kainos:playlist:a")
                }
                return element in shared
            }
        }

        pc.library.mergeAndPersistHomePins(phone.library.exportHomePinsSyncDocument(), playlistsThatUnpinOnFirstLookup)

        assertEquals(listOf("kainos:playlist:b"), pc.pins())
        assertEquals(listOf("kainos:playlist:a"), pc.library.exportHomePinsSyncDocument().tombstones.map { it.targetId })
    }

    @Test
    fun refreshingAPinKeepsItsStampSoAnOlderPeerUnpinCannotRemoveIt() = runTest {
        val phone = device("phone", playlists = shared)
        val pc = device("pc", playlists = shared)
        phone.pinKainos("a")
        sync(phone, pc)
        pc.unpin("kainos:playlist:a")
        phone.unpin("kainos:playlist:a")
        phone.pinKainos("a")
        val stamp = phone.library.homePins.value.single().syncStamp

        phone.library.pinHome(HomePinKind.KAINOS_PLAYLIST, "kainos:playlist:a", title = "renamed")
        assertEquals(stamp, phone.library.homePins.value.single().syncStamp)
        sync(phone, pc)

        assertEquals(listOf("kainos:playlist:a"), pc.pins())
        assertEquals(listOf("renamed"), phone.library.homePins.value.map { it.title })
    }

    @Test
    fun movingAPinThatASyncJustRemovedDoesNothing() = runTest {
        val phone = device("phone", playlists = shared)
        val pc = device("pc", playlists = shared)
        phone.pinKainos("a")
        sync(phone, pc)
        val staleId = phone.library.homePins.value.single().id
        pc.unpin("kainos:playlist:a")
        sync(phone, pc)

        assertEquals(false, phone.library.moveHomePin(staleId, 1))
        assertEquals(emptyList(), phone.pins())
    }
}
