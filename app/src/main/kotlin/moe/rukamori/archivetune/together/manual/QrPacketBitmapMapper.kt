package moe.rukamori.archivetune.together.manual

import androidx.compose.ui.graphics.ImageBitmap

object QrPacketBitmapMapper {

    fun map(
        packets: List<String>,
    ): List<ImageBitmap> =
        packets.map(QrBitmapGenerator::generate)

}
