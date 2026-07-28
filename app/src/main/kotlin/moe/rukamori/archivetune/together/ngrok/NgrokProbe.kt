package moe.rukamori.archivetune.together.ngrok

import timber.log.Timber

@Suppress("UNUSED_VARIABLE")
object NgrokProbe {

    fun run() {
        Timber.i("========== NGROK PROBE START ==========")

        val candidates = listOf(
            "com.ngrok.Session",
            "com.ngrok.Ngrok",
            "com.ngrok.HttpTunnel",
            "com.ngrok.TcpTunnel"
        )

        for (name in candidates) {
            try {
                val clazz = Class.forName(name)
                Timber.i("FOUND: $name")
                Timber.i("Loader: ${clazz.classLoader}")
            } catch (t: Throwable) {
                Timber.e(t, "Missing: $name")
            }
        }

        Timber.i("========== NGROK PROBE END ==========")
    }
}
