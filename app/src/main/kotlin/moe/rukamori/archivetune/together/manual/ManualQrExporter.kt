package moe.rukamori.archivetune.together.manual

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

object ManualQrExporter {

    fun export(
        payload: String,
        chunkSize: Int = 700,
    ): List<String> {

        val sessionId = UUID.randomUUID().toString()

        val chunks =
            payload.chunked(chunkSize)

        return chunks.mapIndexed { index, chunk ->

            Json.encodeToString(
                ManualQrPacket(
                    sessionId = sessionId,
                    sequence = index,
                    total = chunks.size,
                    payload = chunk,
                )
            )
        }
    }
}
