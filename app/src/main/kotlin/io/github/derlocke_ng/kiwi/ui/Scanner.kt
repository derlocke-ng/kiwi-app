package io.github.derlocke_ng.kiwi.ui

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors

/**
 * The camera's picture, read for QR codes. [onText] gets what each one says, as long as this is
 * shown. The pictures are read on the phone and kept nowhere.
 */
@Composable
fun Scanner(onText: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val found by rememberUpdatedState(onText)
    val view = remember { PreviewView(context) }
    DisposableEffect(owner) {
        val main = ContextCompat.getMainExecutor(context)
        val reading = Executors.newSingleThreadExecutor()
        val cameras = ProcessCameraProvider.getInstance(context)
        cameras.addListener({
            val preview = Preview.Builder().build().apply { setSurfaceProvider(view.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .apply { setAnalyzer(reading, QrReader { text -> main.execute { found(text) } }) }
            try {
                cameras.get().run {
                    unbindAll()
                    bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }
            } catch (e: Exception) {
                // no camera on the back: the link can still be pasted
            }
        }, main)
        onDispose {
            if (cameras.isDone) runCatching { cameras.get().unbindAll() }
            reading.shutdown()
        }
    }
    AndroidView(factory = { view }, modifier = modifier)
}

/** Reads the grey plane of each picture for a QR code. */
private class QrReader(private val onText: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = QRCodeReader()

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val grey = ByteArray(plane.buffer.remaining()).also { plane.buffer.get(it) }
            val source = PlanarYUVLuminanceSource(grey, plane.rowStride, image.height, 0, 0, image.width, image.height, false)
            val text = try {
                reader.decode(BinaryBitmap(HybridBinarizer(source))).text
            } catch (e: ReaderException) {
                null
            } finally {
                reader.reset()
            }
            if (text != null) onText(text)
        } catch (e: RuntimeException) {
            // a picture in a shape the reader does not take: the next one may do
        } finally {
            image.close()
        }
    }
}
