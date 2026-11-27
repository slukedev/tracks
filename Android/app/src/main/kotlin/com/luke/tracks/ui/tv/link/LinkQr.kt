package com.luke.tracks.ui.tv.link

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A QR code's modules, row by row: true is dark. */
class QrModules(val size: Int, private val dark: BooleanArray) {
    operator fun get(x: Int, y: Int): Boolean = dark[y * size + x]

    companion object {
        /** The QR for [text], or null for one too long to encode. */
        fun encode(text: String): QrModules? = runCatching {
            val hints = mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.CHARACTER_SET to "UTF-8")
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
            val n = matrix.width
            QrModules(n, BooleanArray(n * n) { matrix[it % n, it / n] })
        }.getOrNull()
    }
}

/**
 * The QR a phone scans to open the link, dark on white whatever the theme, as cameras read
 * it best, with the quiet zone scanners need. Encoded off the main thread.
 */
@Composable
fun LinkQr(text: String, modifier: Modifier = Modifier, size: Dp = 200.dp) {
    val modules by produceState<QrModules?>(null, text) { value = withContext(Dispatchers.Default) { QrModules.encode(text) } }
    Box(
        modifier
            .size(size)
            .background(Color.White, RoundedCornerShape(20.dp))
            .padding(16.dp)
            .clearAndSetSemantics {},
    ) {
        val qr = modules ?: return@Box
        Canvas(Modifier.fillMaxSize()) {
            val cell = this.size.minDimension / qr.size
            // Whole pixels per module keep the edges crisp; the rest is centred.
            val step = kotlin.math.floor(cell).coerceAtLeast(1f)
            val inset = (this.size.minDimension - step * qr.size) / 2
            for (y in 0 until qr.size) for (x in 0 until qr.size) {
                if (qr[x, y]) drawRect(Color.Black, Offset(inset + x * step, inset + y * step), Size(step, step))
            }
        }
    }
}
