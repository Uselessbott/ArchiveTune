package moe.rukamori.archivetune.together.tunnel

import okhttp3.HttpUrl

sealed interface CustomTunnelResult {
    data class Success(val publicUrl: HttpUrl) : TunnelResult
    data class Error(val message: String) : TunnelResult
}

interface CustomTunnelProvider {
    suspend fun discoverTunnelUrl(): CustomTunnelResult
}
