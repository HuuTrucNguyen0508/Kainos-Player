package com.universalmusic.player.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import com.universalmusic.player.platform.PlaybackTrace
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal actual fun encodeQr(text: String): QrMatrix {
    val matrix = Encoder.encode(text, ErrorCorrectionLevel.M, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8")).matrix
    return QrMatrix(matrix.width, BooleanArray(matrix.width * matrix.height) { matrix[it % matrix.width, it / matrix.width].toInt() == 1 })
}

internal actual val canScanPairingCode: Boolean = true

private const val CAMERA_REFUSED =
    "Scanning needs the camera. Allow camera access for Kainos in Android settings, or use Pair with a PC."

@Composable
internal actual fun PairingCodeViewfinder(onScanned: (String) -> Unit, onUnavailable: (String) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val currentUnavailable by rememberUpdatedState(onUnavailable)
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) granted = true else currentUnavailable(CAMERA_REFUSED)
    }
    LaunchedEffect(Unit) { if (!granted) request.launch(Manifest.permission.CAMERA) }
    if (granted) CameraPreview(onScanned, onUnavailable, modifier)
}

@Composable
private fun CameraPreview(onScanned: (String) -> Unit, onUnavailable: (String) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentScanned by rememberUpdatedState(onScanned)
    val currentUnavailable by rememberUpdatedState(onUnavailable)
    val previewView = remember {
        PreviewView(context).apply {
            // A TextureView scrolls and clips with the Settings column; a SurfaceView would not.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    DisposableEffect(lifecycleOwner) {
        val main = ContextCompat.getMainExecutor(context)
        val analyzerThread = Executors.newSingleThreadExecutor()
        val disposed = AtomicBoolean(false)
        val delivered = AtomicBoolean(false)
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                ).build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(analyzerThread) { image ->
            val text = image.use(::readQr) ?: return@setAnalyzer
            if (delivered.compareAndSet(false, true)) {
                main.execute { if (!disposed.get()) currentScanned(text) }
            }
        }
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        providerFuture.addListener({
            if (disposed.get()) return@addListener
            runCatching {
                providerFuture.get().also {
                    it.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    provider = it
                }
            }.onFailure { failure ->
                PlaybackTrace.log("HomeSync", "pairing camera unavailable: ${failure.message ?: failure}")
                currentUnavailable("Kainos could not open the camera. Use Pair with a PC instead.")
            }
        }, main)
        onDispose {
            disposed.set(true)
            provider?.unbind(preview, analysis)
            analysis.clearAnalyzer()
            analyzerThread.shutdown()
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}

private val qrHints = mapOf(
    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
    DecodeHintType.TRY_HARDER to true,
)

/** Reads the luminance plane only; a QR code needs no colour and decodes at any rotation. */
private fun readQr(image: ImageProxy): String? {
    val plane = image.planes[0]
    val buffer = plane.buffer.duplicate().apply { rewind() }
    val luminance = ByteArray(buffer.remaining()).also(buffer::get)
    val rowStride = plane.rowStride
    val rows = luminance.size / rowStride + if (luminance.size % rowStride >= image.width) 1 else 0
    val height = minOf(image.height, rows)
    val source = PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, image.width, height, false)
    return try {
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), qrHints).text
    } catch (_: ReaderException) {
        null
    }
}
