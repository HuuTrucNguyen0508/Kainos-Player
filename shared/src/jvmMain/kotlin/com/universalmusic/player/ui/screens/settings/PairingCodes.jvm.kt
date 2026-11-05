package com.universalmusic.player.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

internal actual fun encodeQr(text: String): QrMatrix {
    val matrix = Encoder.encode(text, ErrorCorrectionLevel.M, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8")).matrix
    return QrMatrix(matrix.width, BooleanArray(matrix.width * matrix.height) { matrix[it % matrix.width, it / matrix.width].toInt() == 1 })
}

internal actual val canScanPairingCode: Boolean = false

@Composable
internal actual fun PairingCodeViewfinder(onScanned: (String) -> Unit, onUnavailable: (String) -> Unit, modifier: Modifier) {
    error("A PC shows its pairing code instead of scanning one")
}
