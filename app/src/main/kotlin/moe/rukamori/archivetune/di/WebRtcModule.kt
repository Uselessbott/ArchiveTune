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
        @ApplicationContext context: Context
    ): WebRtcPeerFactory {
        return WebRtcPeerFactory(context)
    }

    @Provides
    @Singleton
    fun provideWebRtcTransport(
        peerFactory: WebRtcPeerFactory
    ): WebRtcTransport {
        return WebRtcTransport(peerFactory)
    }
}
