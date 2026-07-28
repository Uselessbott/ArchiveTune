package moe.rukamori.archivetune.together.ngrok

import timber.log.Timber

@Suppress("UNUSED_VARIABLE")
object NgrokProbe {

    fun run() {
        throw RuntimeException("NGROK PROBE EXECUTED")
    } catch (t: Throwable) {
                Timber.e(t, "Missing: $name")
            }
        }

        Timber.i("========== NGROK PROBE END ==========")
    }
}
