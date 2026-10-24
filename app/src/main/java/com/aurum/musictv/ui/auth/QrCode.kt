package com.aurum.musictv.ui.auth

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * QR drawn straight onto a Canvas from the encoder's bit matrix — no Bitmap,
 * no image library, no network. A few hundred rectangles; negligible RAM.
 */
@Composable
fun QrCode(text: String, size: Dp = 260.dp, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        runCatching {
            QRCodeWriter().encode(
                text,
                BarcodeFormat.QR_CODE,
                0,
                0,
                mapOf(
                    EncodeHintType.MARGIN to 0,
                    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                ),
            )
        }.getOrNull()
    }
    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .padding(14.dp)
            .size(size),
    ) {
        val m = matrix ?: return@Canvas
        val cell = this.size.width / m.width
        for (y in 0 until m.height) {
            for (x in 0 until m.width) {
                if (m.get(x, y)) {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(x * cell, y * cell),
                        // +0.5 px overlap hides hairline gaps between modules
                        size = Size(cell + 0.5f, cell + 0.5f),
                    )
                }
            }
        }
    }
}
