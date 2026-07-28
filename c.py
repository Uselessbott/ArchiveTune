from pathlib import Path
import shutil

root = Path.cwd()

pkg = root / "app/src/main/kotlin/moe/rukamori/archivetune/together/ngrok"
pkg.mkdir(parents=True, exist_ok=True)

target = pkg / "NgrokProbe.kt"

if target.exists() and target.is_dir():
    shutil.rmtree(target)

target.write_text(
'''package moe.rukamori.archivetune.together.ngrok

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
''',
    encoding="utf-8"
)

print("✓ Created", target)
