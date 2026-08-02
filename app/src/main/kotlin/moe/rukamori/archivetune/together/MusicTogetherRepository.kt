/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.together

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.TogetherAllowGuestsToAddTracksKey
import moe.rukamori.archivetune.constants.TogetherAllowGuestsToControlPlaybackKey
import moe.rukamori.archivetune.constants.TogetherDefaultPortKey
import moe.rukamori.archivetune.constants.TogetherDisplayNameKey
import moe.rukamori.archivetune.constants.TogetherLastJoinLinkKey
import moe.rukamori.archivetune.constants.TogetherRequireHostApprovalToJoinKey
import moe.rukamori.archivetune.constants.TogetherWelcomeShownKey
import moe.rukamori.archivetune.constants.TogetherUseWebRtcKey
import moe.rukamori.archivetune.playback.MusicService
import moe.rukamori.archivetune.utils.dataStore
import javax.inject.Inject
import javax.inject.Singleton

enum class MusicTogetherConnectionMode {
    LAN,
    ONLINE,
    MANUAL_WEBRTC,
    CUSTOM,
}

data class MusicTogetherPreferences(
    val displayName: String,
    val port: Int,
    val allowGuestsToAddTracks: Boolean,
    val allowGuestsToControlPlayback: Boolean,
    val requireHostApprovalToJoin: Boolean,
    val lastJoinLink: String,
    val welcomeShown: Boolean,
    val useWebRtc: Boolean,
)

data class MusicTogetherSnapshot(
    val preferences: MusicTogetherPreferences,
    val sessionState: TogetherSessionState,
    val manualQrState: moe.rukamori.archivetune.together.ManualQrState,
    val qrExchangeState: moe.rukamori.archivetune.together.manual.QrExchangeState,
    val qrPackets: List<String>,
)

@Singleton
class MusicTogetherRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val serviceFlow = MutableStateFlow<MusicService?>(null)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val manualQrState =
        serviceFlow.flatMapLatest {
            it?.manualQrState
                ?: kotlinx.coroutines.flow.flowOf(
                    moe.rukamori.archivetune.together.ManualQrState.Idle
                )
        }


        val preferences: Flow<MusicTogetherPreferences> =
            context.dataStore.data
                .map { preferences ->
                    MusicTogetherPreferences(
                        displayName =
                            preferences[TogetherDisplayNameKey]
                                ?: Build.MODEL?.takeIf { it.isNotBlank() }
                                ?: context.getString(R.string.app_name),
                        port = preferences[TogetherDefaultPortKey] ?: 42117,
                        allowGuestsToAddTracks = preferences[TogetherAllowGuestsToAddTracksKey] ?: true,
                        allowGuestsToControlPlayback = preferences[TogetherAllowGuestsToControlPlaybackKey] ?: false,
                        requireHostApprovalToJoin = preferences[TogetherRequireHostApprovalToJoinKey] ?: false,
                        lastJoinLink = preferences[TogetherLastJoinLinkKey] ?: "",
                        welcomeShown = preferences[TogetherWelcomeShownKey] ?: false,
                        useWebRtc = preferences[TogetherUseWebRtcKey] ?: false,
                    )
                }.distinctUntilChanged()

        @OptIn(ExperimentalCoroutinesApi::class)
        val sessionState: Flow<TogetherSessionState> =
            serviceFlow.flatMapLatest { service ->
                service?.togetherSessionState ?: flowOf(TogetherSessionState.Idle)
            }

        fun attachService(service: MusicService?) {
            serviceFlow.value = service
        }

        suspend fun setDisplayName(displayName: String) {
            context.dataStore.edit { preferences ->
                preferences[TogetherDisplayNameKey] = displayName
            }
        }

        suspend fun setPort(port: Int) {
            context.dataStore.edit { preferences ->
                preferences[TogetherDefaultPortKey] = port
            }
        }

        suspend fun setAllowGuestsToAddTracks(value: Boolean) {
            context.dataStore.edit { preferences ->
                preferences[TogetherAllowGuestsToAddTracksKey] = value
            }
        }

        suspend fun setAllowGuestsToControlPlayback(value: Boolean) {
            context.dataStore.edit { preferences ->
                preferences[TogetherAllowGuestsToControlPlaybackKey] = value
            }
        }

        suspend fun setRequireHostApprovalToJoin(value: Boolean) {
            context.dataStore.edit { preferences ->
                preferences[TogetherRequireHostApprovalToJoinKey] = value
            }
        }

        suspend fun setLastJoinLink(value: String) {
            context.dataStore.edit { preferences ->
                preferences[TogetherLastJoinLinkKey] = value
            }
        }

        suspend fun setWelcomeShown(value: Boolean) {
            context.dataStore.edit { preferences ->
                preferences[TogetherWelcomeShownKey] = value
            }
        }


        suspend fun setUseWebRtc(value: Boolean) {
            context.dataStore.edit { preferences ->
                preferences[TogetherUseWebRtcKey] = value
            }
        }

        fun startSession(
            mode: MusicTogetherConnectionMode,
            displayName: String,
            port: Int,
            settings: TogetherRoomSettings,
            useWebRtc: Boolean = false,
        ) {
            val service = serviceFlow.value ?: return
            when (mode) {
                MusicTogetherConnectionMode.LAN -> {
                    service.startTogetherHost(
                        port = port,
                        displayName = displayName,
                        settings = settings,
                    )
                }

                MusicTogetherConnectionMode.ONLINE -> {
                    service.startTogetherOnlineHost(
                        displayName = displayName,
                        settings = settings,
                        useWebRtc = useWebRtc,
                    )
                }

                MusicTogetherConnectionMode.MANUAL_WEBRTC -> {
                    service.startTogetherManualWebRtcHost(
                        displayName = displayName,
                        settings = settings,
                    )
                }

                MusicTogetherConnectionMode.CUSTOM -> {
                    service.startTogetherCustomHost(
                        port = port,
                        displayName = displayName,
                        settings = settings,
                    )
                }
            }
        }

        fun joinSession(
            mode: MusicTogetherConnectionMode,
            rawInput: String,
            displayName: String,
            useWebRtc: Boolean = false,
        ) {
            val service = serviceFlow.value ?: return
            when (mode) {
                MusicTogetherConnectionMode.LAN -> service.joinTogether(rawInput, displayName)
                MusicTogetherConnectionMode.ONLINE ->
                    service.joinTogetherOnline(
                        rawInput,
                        displayName,
                        useWebRtc,
                    )

                MusicTogetherConnectionMode.MANUAL_WEBRTC ->
                    service.joinTogetherManualWebRtc(displayName)

                MusicTogetherConnectionMode.CUSTOM -> service.joinTogetherCustom(rawInput, displayName)
            }
        }

        fun leaveSession() {
            serviceFlow.value?.leaveTogether()
        }


        val manualQrPackets: Flow<List<String>> =
            serviceFlow.flatMapLatest { service ->
                service?.qrPackets ?: flowOf(emptyList())
            }

        val manualQrExchangeState: Flow<moe.rukamori.archivetune.together.manual.QrExchangeState> =
            serviceFlow.flatMapLatest { service ->
                service?.qrExchangeState ?: flowOf(
                    moe.rukamori.archivetune.together.manual.QrExchangeState.Idle
                )
            }

        suspend fun submitManualQrPackets(
            packets: List<String>,
        ) {
            serviceFlow.value?.submitManualQrPackets(packets)
        }

        suspend fun exportManualIce() {
            serviceFlow.value?.exportManualIce()
        }


        fun updateSettings(settings: TogetherRoomSettings) {
            serviceFlow.value?.updateTogetherSettings(settings)
        }

        fun approveParticipant(
            participantId: String,
            approved: Boolean,
        ) {
            serviceFlow.value?.approveTogetherParticipant(participantId, approved)
        }

        fun kickParticipant(participantId: String) {
            serviceFlow.value?.kickTogetherParticipant(participantId)
        }

        fun banParticipant(participantId: String) {
            serviceFlow.value?.banTogetherParticipant(participantId)
        }

        fun transferHostOwnership(participantId: String) {
            serviceFlow.value?.transferTogetherHostOwnership(participantId)
        }
    }
