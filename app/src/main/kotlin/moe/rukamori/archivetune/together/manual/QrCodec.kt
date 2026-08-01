package moe.rukamori.archivetune.together.manual

import android.util.Base64
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import moe.rukamori.archivetune.together.ManualQrProtocol
import moe.rukamori.archivetune.together.TogetherJson
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Codec for manual signaling transport (QR, clipboard, etc.).
 *
 * Encoding (called by session):
 *   - Session creates a QrSignalPacket with payload = raw JSON string.
 *   - QrCodec.encode(packet): compresses payload → Base64 → splits into QR_COUNT chunks
 *   - Each chunk becomes a new QrSignalPacket (part = 1..QR_COUNT, total = QR_COUNT)
 *   - Each chunk packet is serialized to JSON, then Base64 (no compression) → QR string.
 *
 * Decoding reverses the process.
 */
object QrCodec {

    /**
     * Encodes a complete [QrSignalPacket] into exactly [ManualQrProtocol.QR_COUNT] QR strings.
     * The packet's [payload] is expected to be a JSON string (not yet compressed).
     */
    fun encode(fullPacket: QrSignalPacket): List<String> {
        require(fullPacket.total == ManualQrProtocol.QR_COUNT) {
            "Total must be ${ManualQrProtocol.QR_COUNT}, got ${fullPacket.total}"
        }
        // Compress and base64 the payload (JSON string)
        val compressed = gzipCompress(fullPacket.payload.toByteArray(Charsets.UTF_8))
        val base64 = Base64.encodeToString(compressed, Base64.URL_SAFE or Base64.NO_WRAP)
        val chunks = splitIntoChunks(base64, ManualQrProtocol.QR_COUNT)
        return chunks.mapIndexed { index, chunk ->
            val packet = fullPacket.copy(
                part = index + 1,
                payload = chunk
            )
            // Serialize packet to JSON, then Base64 (no compression)
            val json = TogetherJson.json.encodeToString(packet)
            Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP)
        }
    }

    /**
     * Decodes a list of QR strings back into a reassembled [QrSignalPacket].
     * The returned packet has part=0 (reassembled) and payload as the original JSON string.
     * @throws QrCodecException on validation or decoding failure.
     */
    fun decode(parts: List<String>): QrSignalPacket {
        if (parts.size != ManualQrProtocol.QR_COUNT) {
            throw QrCodecException("Expected ${ManualQrProtocol.QR_COUNT} parts, got ${parts.size}")
        }
        val packets = parts.map { encoded ->
            val decoded = Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP)
            val json = String(decoded, Charsets.UTF_8)
            TogetherJson.json.decodeFromString<QrSignalPacket>(json)
        }
        val first = packets.first()
        packets.forEach { p ->
            if (p.version != ManualQrProtocol.VERSION) {
                throw QrCodecException("Version mismatch: expected ${ManualQrProtocol.VERSION}, got ${p.version}")
            }
            if (p.sessionId != first.sessionId) {
                throw QrCodecException("Session ID mismatch: ${p.sessionId} vs ${first.sessionId}")
            }
            if (p.kind != first.kind) {
                throw QrCodecException("Kind mismatch: ${p.kind} vs ${first.kind}")
            }
            if (p.total != ManualQrProtocol.QR_COUNT) {
                throw QrCodecException("Total mismatch: expected ${ManualQrProtocol.QR_COUNT}, got ${p.total}")
            }
            if (p.part !in 1..ManualQrProtocol.QR_COUNT) {
                throw QrCodecException("Invalid part number: ${p.part}")
            }
        }
        val sorted = packets.sortedBy { it.part }
        if (sorted.map { it.part } != (1..ManualQrProtocol.QR_COUNT).toList()) {
            throw QrCodecException("Missing or duplicate part numbers")
        }
        val combinedBase64 = sorted.joinToString("") { it.payload }
        val decodedBytes = Base64.decode(combinedBase64, Base64.URL_SAFE or Base64.NO_WRAP)
        val decompressed = gzipDecompress(decodedBytes)
        val payloadJson = String(decompressed, Charsets.UTF_8)
        // Return reassembled packet with part=0
        return QrSignalPacket(
            version = ManualQrProtocol.VERSION,
            sessionId = first.sessionId,
            kind = first.kind,
            part = 0,  // indicates reassembled packet
            total = ManualQrProtocol.QR_COUNT,
            payload = payloadJson
        )
    }

    private fun gzipCompress(data: ByteArray): ByteArray =
        ByteArrayOutputStream().use { baos ->
            GZIPOutputStream(baos).use { gzip -> gzip.write(data) }
            baos.toByteArray()
        }

    private fun gzipDecompress(data: ByteArray): ByteArray =
        ByteArrayInputStream(data).use { bais ->
            GZIPInputStream(bais).use { gzip -> gzip.readBytes() }
        }

    private fun splitIntoChunks(input: String, chunkCount: Int): List<String> {
        val len = input.length
        val chunkSize = (len + chunkCount - 1) / chunkCount
        return List(chunkCount) { i ->
            val start = minOf(i * chunkSize, len)
            val end = minOf(start + chunkSize, len)
            input.substring(start, end)
        }
    }
}

/**
 * Exception thrown by [QrCodec] on encoding/decoding failures.
 */
class QrCodecException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
