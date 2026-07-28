package moe.rukamori.archivetune.together.tunnel

import okhttp3.HttpUrl

sealed interface CustomTunnelResult {
    data class Success(val publicUrl: HttpUrl) : CustomTunnelResult
    data class Error(val message: String) : CustomTunnelResult
}

interface CustomTunnelProvider {
    suspend fun discoverTunnelUrl(): CustomTunnelResult
}
