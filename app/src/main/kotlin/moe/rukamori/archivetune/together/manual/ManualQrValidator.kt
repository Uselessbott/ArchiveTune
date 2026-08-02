package moe.rukamori.archivetune.together.manual

object ManualQrValidator {

    fun validate(packets: List<QrSignalPacket>) {

        require(packets.isNotEmpty()) {
            "No QR packets found."
        }

        val sessionId = packets.first().sessionId
        val version = packets.first().version
        val total = packets.first().total

        require(total == packets.size) {
            "Missing QR pages."
        }

        require(
            packets.all { it.sessionId == sessionId }
        ) {
            "QRs belong to different sessions."
        }

        require(
            packets.all { it.version == version }
        ) {
            "QR protocol version mismatch."
        }

        val parts =
            packets.map { it.part }

        require(parts.distinct().size == parts.size) {
            "Duplicate QR pages."
        }

        require(parts.sorted() == (0 until total).toList()) {
            "QR pages are incomplete."
        }
    }
}
