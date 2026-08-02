package moe.rukamori.archivetune.together.manual

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.Json

object QrImportManager {

    suspend fun importPackets(
        context: Context,
        uri: Uri,
    ): String? {

        val image = InputImage.fromFilePath(context, uri)

        val scanner =
            BarcodeScanning.getClient()

        val barcodes =
            scanner.process(image).await()

        val packets =
            barcodes
                .mapNotNull { barcode ->
                    runCatching {
                        Json.decodeFromString<ManualQrPacket>(
                            barcode.rawValue ?: return@runCatching null
                        )
                    }.getOrNull()
                }
                .filterNotNull()
                .distinctBy { it.sequence }

        return ManualQrAssembler.assemble(packets)
    }
}
