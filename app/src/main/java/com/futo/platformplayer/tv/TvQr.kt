package com.futo.platformplayer.tv

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object TvQr {
    fun encode(text: String, size: Int): Bitmap {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1)
        val m = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(m.width * m.height) { i -> if (m[i % m.width, i / m.width]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, m.width, m.height, Bitmap.Config.RGB_565)
    }

    /** Reads a QR code from a photo (JPEG/PNG bytes), or null when there is none. */
    fun decodeImage(bytes: ByteArray): String? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // Large photos are sampled down first; a QR code filling part of the frame still reads at ~1600 px.
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2000) sample *= 2
        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return decodePixels(pixels, bmp.width, bmp.height).also { bmp.recycle() }
    }

    /** Platform-free part of [decodeImage], so it can be unit tested. */
    fun decodePixels(argb: IntArray, width: Int, height: Int): String? {
        val source = RGBLuminanceSource(width, height, argb)
        val hints = mapOf(DecodeHintType.TRY_HARDER to true)
        for (bitmap in listOf(BinaryBitmap(HybridBinarizer(source)), BinaryBitmap(GlobalHistogramBinarizer(source)))) {
            try { return QRCodeReader().decode(bitmap, hints).text } catch (_: NotFoundException) {
            } catch (_: com.google.zxing.ChecksumException) {
            } catch (_: com.google.zxing.FormatException) { }
        }
        return null
    }
}
