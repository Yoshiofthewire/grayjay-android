package com.futo.platformplayer.tv

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object TvQr {
    fun encode(text: String, size: Int): Bitmap {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1)
        val m = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(m.width * m.height) { i -> if (m[i % m.width, i / m.width]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, m.width, m.height, Bitmap.Config.RGB_565)
    }
}
