package moe.rukamori.archivetune.together.tunnel

class NoOpTunnelProvider : TunnelProvider {
    override suspend fun discoverTunnelUrl(): TunnelResult =
        TunnelResult.Error("No tunnel provider configured")
}
