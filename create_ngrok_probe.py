from pathlib import Path

root = Path.cwd()

pkg = root / "app/src/main/kotlin/moe/rukamori/archivetune/together/ngrok"
pkg.mkdir(parents=True, exist_ok=True)

(pkg / "NgrokProbe.kt").write_text("""\
package moe.rukamori.archivetune.together.ngrok

import timber.log.Timber

@Suppress("UNUSED_VARIABLE")
object NgrokProbe {

    fun run() {
        Timber.i("========== NGROK PROBE START ==========")

        try {
            val candidates = listOf(
                "com.ngrok.Session",
                "com.ngrok.Ngrok",
                "com.ngrok.TcpTunnel",
                "com.ngrok.HttpTunnel"
            )

            for (name in candidates) {
                try {
                    val clazz = Class.forName(name)
                    Timber.i("FOUND: $name")
                    Timber.i("ClassLoader = ${clazz.classLoader}")
                } catch (t: Throwable) {
                    Timber.e(t, "Missing: $name")
                }
            }

            Timber.i("========== NGROK PROBE END ==========")
        } catch (t: Throwable) {
            Timber.e(t, "Probe crashed")
        }
    }
}
""")

print("✓ Created NgrokProbe.kt")
