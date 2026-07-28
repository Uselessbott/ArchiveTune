package moe.rukamori.archivetune.together.tunnel

import okhttp3.HttpUrl

sealed interface TunnelResult {
    data class Success(val publicUrl: HttpUrl) : TunnelResult
    data class Error(val message: String) : TunnelResult
}

interface TunnelProvider {
    suspend fun discoverTunnelUrl(): TunnelResult
}
