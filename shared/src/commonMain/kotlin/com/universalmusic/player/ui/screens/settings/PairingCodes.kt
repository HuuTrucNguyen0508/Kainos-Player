package com.universalmusic.player.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** QR modules, row by row; `true` is a dark module. */
internal class QrMatrix(val size: Int, private val dark: BooleanArray) {
    operator fun get(x: Int, y: Int): Boolean = dark[y * size + x]
}

internal expect fun encodeQr(text: String): QrMatrix

/** False on desktop, where the PC shows the code instead of reading one. */
internal expect val canScanPairingCode: Boolean

/**
 * A live camera view that reads the first QR code it sees and hands its text to [onScanned].
 * It asks for the camera permission when it first appears; [onUnavailable] explains a refusal.
 */
@Composable
internal expect fun PairingCodeViewfinder(
    onScanned: (String) -> Unit,
    onUnavailable: (String) -> Unit,
    modifier: Modifier,
)

/**
 * Always dark modules on white with a four-module quiet zone, whatever the colour scheme:
 * phone scanners read an inverted or low-contrast code poorly.
 */
@Composable
internal fun PairingQrCode(link: String, modifier: Modifier = Modifier) {
    val matrix = remember(link) { encodeQr(link) }
    Canvas(
        modifier
            .size(176.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .semantics { contentDescription = "Pairing QR code for this PC" },
    ) {
        val cell = size.minDimension / (matrix.size + 8)
        for (y in 0 until matrix.size) for (x in 0 until matrix.size) {
            if (matrix[x, y]) {
                drawRect(Color.Black, Offset((x + 4) * cell, (y + 4) * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

/** The camera view in a square window with corner marks, so it reads as "aim here". */
@Composable
internal fun PairingScanWindow(onScanned: (String) -> Unit, onUnavailable: (String) -> Unit) {
    val mark = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .widthIn(max = 320.dp)
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        PairingCodeViewfinder(onScanned, onUnavailable, Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize().padding(24.dp)) {
            val arm = size.minDimension / 6
            val stroke = 4.dp.toPx()
            val corners = listOf(
                Offset(0f, 0f) to Offset(1f, 1f),
                Offset(size.width, 0f) to Offset(-1f, 1f),
                Offset(0f, size.height) to Offset(1f, -1f),
                Offset(size.width, size.height) to Offset(-1f, -1f),
            )
            for ((corner, direction) in corners) {
                drawLine(mark, corner, corner + Offset(direction.x * arm, 0f), stroke, StrokeCap.Round)
                drawLine(mark, corner, corner + Offset(0f, direction.y * arm), stroke, StrokeCap.Round)
            }
        }
    }
}
