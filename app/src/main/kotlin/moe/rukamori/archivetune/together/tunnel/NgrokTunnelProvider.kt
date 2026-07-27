package moe.rukamori.archivetune.together.tunnel

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

@Serializable
private data class NgrokTunnel(
    val public_url: String,
    val proto: String,
)

@Serializable
private data class NgrokTunnelsResponse(
    val tunnels: List<NgrokTunnel>,
)

class NgrokTunnelProvider(
    private val client: OkHttpClient,
) : TunnelProvider {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun discoverTunnelUrl(): TunnelResult {
        return try {
            val request = Request.Builder()
                .url("http://127.0.0.1:4040/api/tunnels")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return TunnelResult.Error("Ngrok API returned ${response.code}")
            }

            val body = response.body?.string() ?: return TunnelResult.Error("Empty response from ngrok")
            val parsed = json.decodeFromString<NgrokTunnelsResponse>(body)

            val tunnel = parsed.tunnels
                .firstOrNull { it.proto == "https" || it.proto == "http" }
                ?: return TunnelResult.Error("No HTTP/HTTPS tunnel found in ngrok")

            val publicUrl = tunnel.public_url.toHttpUrlOrNull()
                ?: return TunnelResult.Error(
                    "Invalid tunnel URL: ${tunnel.public_url}"
                )

            TunnelResult.Success(publicUrl)
        } catch (e: IOException) {
            TunnelResult.Error("Failed to reach ngrok: ${e.message}")
        } catch (e: SerializationException) {
            TunnelResult.Error("Failed to parse ngrok response: ${e.message}")
        }
    }
}
