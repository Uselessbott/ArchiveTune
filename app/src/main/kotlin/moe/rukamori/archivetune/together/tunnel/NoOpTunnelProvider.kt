package moe.rukamori.archivetune.together.tunnel

class NoOpTunnelProvider : CustomTunnelProvider {
    override suspend fun discoverTunnelUrl(): CustomTunnelResult =
        CustomTunnelResult.Error("No tunnel provider configured")
}
