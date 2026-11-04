package com.universalmusic.player.ui.screens

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.runComposeUiTest
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.universalmusic.player.data.sync.DiscoveredHub
import com.universalmusic.player.data.sync.hubPairingLink
import com.universalmusic.player.data.sync.parseHubPairingLink
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class PairingQrCodeTest {
    @Test
    fun theDrawnCodeDecodesToThePcsPairingLink() = runComposeUiTest {
        val hub = DiscoveredHub("ab".repeat(32), "pc-7f3a", "192.168.1.20", 43822, "Kainos on studio")
        val link = hubPairingLink(hub)
        setContent { PairingQrCode(link) }
        val image = onNodeWithContentDescription("Pairing QR code for this PC").captureToImage().toAwtImage()
        val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(image.width, image.height, pixels)))).text
        assertEquals(link, decoded)
        assertEquals(hub, parseHubPairingLink(decoded).getOrThrow())
    }
}
