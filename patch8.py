from pathlib import Path

ROOT = Path("app/src/main/kotlin/moe/rukamori/archivetune")

di = ROOT / "di" / "WebRtcModule.kt"

factory = ROOT / "together" / "webrtc" / "WebRtcPeerFactory.kt"

di.write_text(
"""/*
 * Auto-generated WebRTC DI module.
 */

package moe.rukamori.archivetune.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import moe.rukamori.archivetune.together.webrtc.WebRtcPeerFactory
import moe.rukamori.archivetune.together.webrtc.WebRtcTransport
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object WebRtcModule {

    @Provides
    @Singleton
    fun provideWebRtcPeerFactory(
        @ApplicationContext context: Context,
    ): WebRtcPeerFactory = WebRtcPeerFactory(context)

    @Provides
    @Singleton
    fun provideWebRtcTransport(
        factory: WebRtcPeerFactory,
    ): WebRtcTransport = WebRtcTransport(factory)
}
""",
encoding="utf-8",
)

factory.write_text(
"""/*
 * Auto-generated WebRTC Peer Factory.
 */

package moe.rukamori.archivetune.together.webrtc

import android.content.Context
import org.webrtc.PeerConnectionFactory
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebRtcPeerFactory
@Inject
constructor(
    context: Context,
) {

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions
                .builder(context.applicationContext)
                .createInitializationOptions()
        )
    }

    val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.builder().createPeerConnectionFactory()
    }
}
""",
encoding="utf-8",
)

peer = ROOT / "together" / "webrtc" / "WebRtcPeer.kt"

peer.write_text(
"""package moe.rukamori.archivetune.together.webrtc

import javax.inject.Inject

class WebRtcPeer
@Inject
constructor(
    val factory: WebRtcPeerFactory,
)
""",
encoding="utf-8",
)

transport = ROOT / "together" / "webrtc" / "WebRtcTransport.kt"

transport.write_text(
"""package moe.rukamori.archivetune.together.webrtc

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebRtcTransport
@Inject
constructor(
    val peerFactory: WebRtcPeerFactory,
)
""",
encoding="utf-8",
)

print("✓ WebRTC DI scaffolding created.")
