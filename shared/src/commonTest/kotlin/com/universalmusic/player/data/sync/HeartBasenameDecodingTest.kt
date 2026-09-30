package com.universalmusic.player.data.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class HeartBasenameDecodingTest {
    private val realName = "ghostfinalrevelation .feat kinoko蘑菇punishing_ gray raven ost - 遥岸方舟.flac"

    @Test
    fun safDocumentIdWithCjkDecodesAsUtf8() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/" +
            "primary%3AMusic%2Fstorage-2%2Fghostfinalrevelation%20.feat%20kinoko%E8%98%91%E8%8F%87" +
            "punishing_%20gray%20raven%20ost%20-%20%E9%81%A5%E5%B2%B8%E6%96%B9%E8%88%9F.flac"
        assertEquals(realName.lowercase(), basenameFromLocalLocation(uri))
    }

    @Test
    fun desktopFileUriWithCjkDecodesAsUtf8() {
        val uri = "file:///home/me/Music/ghostfinalrevelation%20.feat%20kinoko%E8%98%91%E8%8F%87" +
            "punishing_%20gray%20raven%20ost%20-%20%E9%81%A5%E5%B2%B8%E6%96%B9%E8%88%9F.flac"
        assertEquals(realName.lowercase(), basenameFromLocalLocation(uri))
    }

    @Test
    fun plusIsALiteralFileNameCharacter() {
        assertEquals("a+b.flac", basenameFromLocalLocation("file:///music/A+B.flac"))
        assertEquals("a b.flac", basenameFromLocalLocation("file:///music/A%20B.flac"))
    }

    @Test
    fun trailingEscapeIsDecoded() {
        assertEquals("song!", basenameFromLocalLocation("file:///music/song%21"))
    }

    @Test
    fun storedMojibakeHeartRepairsToTheRealName() {
        // What older builds stored: each UTF-8 byte decoded as one char, then lowercased.
        val mojibake = realName.encodeToByteArray()
            .joinToString("") { (it.toInt() and 0xFF).toChar().toString() }
            .lowercase()
        val heartId = localFileHeartId(mojibake)
        assertEquals(realName.lowercase(), normalizedLocalFileHeartBasename(heartId))
    }

    @Test
    fun ordinaryNamesAreUntouchedByRepair() {
        assertEquals("café.flac", repairLatin1Mojibake("café.flac"))
        assertEquals("plain.flac", repairLatin1Mojibake("plain.flac"))
        assertEquals("遥岸方舟.flac", repairLatin1Mojibake("遥岸方舟.flac"))
    }
}
