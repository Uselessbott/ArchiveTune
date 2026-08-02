package moe.rukamori.archivetune.together.manual

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

object QrBitmapGenerator {

    fun generate(
        text: String,
        size: Int = 768,
    ): ImageBitmap {

        val matrix = QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            size,
            size,
        )

        val bitmap = Bitmap.createBitmap(
            size,
            size,
            Bitmap.Config.ARGB_8888,
        )

        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(
                    x,
                    y,
                    if (matrix[x, y]) 0xFF000000.toInt()
                    else 0xFFFFFFFF.toInt(),
                )
            }
        }

        return bitmap.asImageBitmap()
    }
}
