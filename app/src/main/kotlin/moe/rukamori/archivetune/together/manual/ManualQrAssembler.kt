package moe.rukamori.archivetune.together.manual

object ManualQrAssembler {

    fun assemble(
        packets: List<ManualQrPacket>,
    ): String? {

        if (packets.isEmpty()) return null

        val total = packets.first().total

        if (packets.size != total) return null

        val ordered =
            packets.sortedBy { it.sequence }

        for (i in ordered.indices) {
            if (ordered[i].sequence != i) {
                return null
            }
        }

        return buildString {
            ordered.forEach {
                append(it.payload)
            }
        }
    }
}
