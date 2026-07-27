/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:Suppress("DEPRECATION")

package moe.rukamori.archivetune.playback

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.database.SQLException
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaCodecList
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.PowerManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.EVENT_POSITION_DISCONTINUITY
import androidx.media3.common.Player.EVENT_TIMELINE_CHANGED
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.REPEAT_MODE_ONE
import androidx.media3.common.Player.STATE_IDLE
import androidx.media3.common.Timeline
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlaybackStats
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.rukamori.archivetune.MainActivity
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.cast.CastMediaItemResolver
import moe.rukamori.archivetune.cast.CastPlaybackRepository
import moe.rukamori.archivetune.cast.CastPlaybackRepositoryLocator
import moe.rukamori.archivetune.constants.AudioNormalizationKey
import moe.rukamori.archivetune.constants.AudioOffload
import moe.rukamori.archivetune.constants.AudioQuality
import moe.rukamori.archivetune.constants.AudioQualityKey
import moe.rukamori.archivetune.constants.AutoDownloadOnLikeKey
import moe.rukamori.archivetune.constants.AutoLoadMoreKey
import moe.rukamori.archivetune.constants.AutoSkipNextOnErrorKey
import moe.rukamori.archivetune.constants.AutoStartOnBluetoothKey
import moe.rukamori.archivetune.constants.CrossfadeDurationKey
import moe.rukamori.archivetune.constants.CrossfadeEnabledKey
import moe.rukamori.archivetune.constants.CrossfadeGaplessKey
import moe.rukamori.archivetune.constants.DeviceMutePlaybackRecoveryVolumeKey
import moe.rukamori.archivetune.constants.DiscordShowWhenPausedKey
import moe.rukamori.archivetune.constants.DiscordTokenKey
import moe.rukamori.archivetune.constants.EnableDiscordRPCKey
import moe.rukamori.archivetune.constants.EnableLastFMScrobblingKey
import moe.rukamori.archivetune.constants.EqualizerAutoHeadroomEnabledKey
import moe.rukamori.archivetune.constants.EqualizerBandLevelsMbKey
import moe.rukamori.archivetune.constants.EqualizerBassBoostEnabledKey
import moe.rukamori.archivetune.constants.EqualizerBassBoostStrengthKey
import moe.rukamori.archivetune.constants.EqualizerEnabledKey
import moe.rukamori.archivetune.constants.EqualizerOutputGainEnabledKey
import moe.rukamori.archivetune.constants.EqualizerOutputGainMbKey
import moe.rukamori.archivetune.constants.EqualizerSelectedProfileIdKey
import moe.rukamori.archivetune.constants.EqualizerVirtualizerEnabledKey
import moe.rukamori.archivetune.constants.EqualizerVirtualizerStrengthKey
import moe.rukamori.archivetune.constants.HISTORY_DURATION_DEFAULT
import moe.rukamori.archivetune.constants.HISTORY_DURATION_MAX
import moe.rukamori.archivetune.constants.HISTORY_DURATION_MIN
import moe.rukamori.archivetune.constants.HideExplicitKey
import moe.rukamori.archivetune.constants.HideVideoKey
import moe.rukamori.archivetune.constants.HistoryDuration
import moe.rukamori.archivetune.constants.LastFMSessionKey
import moe.rukamori.archivetune.constants.LastFMUseNowPlaying
import moe.rukamori.archivetune.constants.ListenBrainzEnabledKey
import moe.rukamori.archivetune.constants.ListenBrainzTokenKey
import moe.rukamori.archivetune.constants.MaxSongCacheSizeKey
import moe.rukamori.archivetune.constants.MediaSessionConstants.CommandToggleLike
import moe.rukamori.archivetune.constants.MediaSessionConstants.CommandToggleRepeatMode
import moe.rukamori.archivetune.constants.MediaSessionConstants.CommandToggleShuffle
import moe.rukamori.archivetune.constants.MediaSessionConstants.CommandToggleStartRadio
import moe.rukamori.archivetune.constants.PauseListenHistoryKey
import moe.rukamori.archivetune.constants.PauseOnDeviceMuteKey
import moe.rukamori.archivetune.constants.PermanentShuffleKey
import moe.rukamori.archivetune.constants.PersistentQueueKey
import moe.rukamori.archivetune.constants.PlayerStreamClient
import moe.rukamori.archivetune.constants.PlayerStreamClientKey
import moe.rukamori.archivetune.constants.PlayerVolumeKey
import moe.rukamori.archivetune.constants.RepeatModeKey
import moe.rukamori.archivetune.constants.ScrobbleDelayPercentKey
import moe.rukamori.archivetune.constants.ScrobbleDelaySecondsKey
import moe.rukamori.archivetune.constants.ScrobbleMinSongDurationKey
import moe.rukamori.archivetune.constants.ShowLyricsKey
import moe.rukamori.archivetune.constants.SkipSilenceKey
import moe.rukamori.archivetune.constants.SmartTrimmerKey
import moe.rukamori.archivetune.constants.StopMusicOnTaskClearKey
import moe.rukamori.archivetune.constants.TogetherClientIdKey
import moe.rukamori.archivetune.constants.WakelockKey
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.AlbumEntity
import moe.rukamori.archivetune.db.entities.ArtistEntity
import moe.rukamori.archivetune.db.entities.Event
import moe.rukamori.archivetune.db.entities.FormatEntity
import moe.rukamori.archivetune.db.entities.RelatedSongMap
import moe.rukamori.archivetune.db.entities.Song
import moe.rukamori.archivetune.db.entities.SongEntity
import moe.rukamori.archivetune.di.DownloadCache
import moe.rukamori.archivetune.di.PlayerCache
import moe.rukamori.archivetune.extensions.SilentHandler
import moe.rukamori.archivetune.extensions.collect
import moe.rukamori.archivetune.extensions.collectLatest
import moe.rukamori.archivetune.extensions.currentMetadata
import moe.rukamori.archivetune.extensions.directorySizeBytes
import moe.rukamori.archivetune.extensions.findNextMediaItemById
import moe.rukamori.archivetune.extensions.mediaItems
import moe.rukamori.archivetune.extensions.metadata
import moe.rukamori.archivetune.extensions.setOffloadEnabled
import moe.rukamori.archivetune.extensions.toContinuationQueue
import moe.rukamori.archivetune.extensions.toMediaItem
import moe.rukamori.archivetune.extensions.toPersistQueue
import moe.rukamori.archivetune.extensions.toQueue
import moe.rukamori.archivetune.innertube.PlaybackAuthState
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import moe.rukamori.archivetune.innertube.models.response.PlayerResponse
import moe.rukamori.archivetune.lastfm.LastFM
import moe.rukamori.archivetune.lyrics.LyricsHelper
import moe.rukamori.archivetune.lyrics.LyricsPreloadManager
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.models.PersistPlayerState
import moe.rukamori.archivetune.models.PersistQueue
import moe.rukamori.archivetune.models.toMediaMetadata
import moe.rukamori.archivetune.moriextractor.ArchiveTuneExtractorException
import moe.rukamori.archivetune.moriextractor.InMemoryBearerTokenRepository
import moe.rukamori.archivetune.moriextractor.StreamingExtractionManager
import moe.rukamori.archivetune.playback.queues.EmptyQueue
import moe.rukamori.archivetune.playback.queues.ListQueue
import moe.rukamori.archivetune.playback.queues.Queue
import moe.rukamori.archivetune.playback.queues.YouTubeQueue
import moe.rukamori.archivetune.playback.queues.filterBlockedArtists
import moe.rukamori.archivetune.playback.queues.filterExplicit
import moe.rukamori.archivetune.playback.queues.filterVideo
import moe.rukamori.archivetune.playback.queues.hasBlockedArtist
import moe.rukamori.archivetune.scrobbling.LastFmServiceConfig
import moe.rukamori.archivetune.storage.StorageFolderKind
import moe.rukamori.archivetune.storage.StorageLocationRepository
import moe.rukamori.archivetune.together.TogetherPlaybackSync
import moe.rukamori.archivetune.ui.screens.settings.DiscordPresenceManager
import moe.rukamori.archivetune.ui.screens.settings.ListenBrainzManager
import moe.rukamori.archivetune.utils.AuthScopedCacheValue
import moe.rukamori.archivetune.utils.CoilBitmapLoader
import moe.rukamori.archivetune.utils.NetworkConnectivityObserver
import moe.rukamori.archivetune.utils.StreamClientUtils
import moe.rukamori.archivetune.utils.SyncUtils
import moe.rukamori.archivetune.utils.YTPlayerUtils
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.enumPreference
import moe.rukamori.archivetune.utils.get
import moe.rukamori.archivetune.utils.getAsync
import moe.rukamori.archivetune.utils.isLocalMediaId
import moe.rukamori.archivetune.utils.isLowDataModeActive
import moe.rukamori.archivetune.utils.reportException
import moe.rukamori.archivetune.utils.retryWithoutPlaybackLoginContext
import moe.rukamori.archivetune.widget.LoadWidgetInsightsUseCase
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.EOFException
import java.io.FileOutputStream
import java.io.IOException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.net.ConnectException
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.LocalDateTime
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.time.Duration.Companion.seconds
import moe.rukamori.archivetune.together.tunnel.NoOpTunnelProvider
import moe.rukamori.archivetune.together.tunnel.TunnelProvider
import moe.rukamori.archivetune.together.tunnel.TunnelResult
import moe.rukamori.archivetune.together.tunnel.NgrokTunnelProvider

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class, UnstableApi::class)
@AndroidEntryPoint
class MusicService :
    MediaLibraryService(),
    Player.Listener,
    PlaybackStatsListener.Callback {
    @Inject
    lateinit var database: MusicDatabase

    @Inject
    lateinit var lyricsHelper: LyricsHelper

    @Inject
    lateinit var syncUtils: SyncUtils

    @Inject
    lateinit var mediaLibrarySessionCallback: MediaLibrarySessionCallback

    @Inject
    internal lateinit var loadWidgetInsightsUseCase: LoadWidgetInsightsUseCase

    @Inject
    lateinit var equalizerPlaybackController: EqualizerPlaybackController

    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var lastAudioFocusState = AudioManager.AUDIOFOCUS_NONE
    private var wasPlayingBeforeAudioFocusLoss = false
    private var pauseOnDeviceMuteEnabled = false
    private var deviceMutePlaybackRecoveryVolumePercent = 0
    private var wasAutoPausedByDeviceMute = false
    private var muteRecoveryObserver: ContentObserver? = null
    private var lastDeviceMutePlaybackNoticeAtElapsedMs = 0L
    private var hasAudioFocus = false
    private var autoStartOnBluetoothEnabled = false
    private var bluetoothReceiverRegistered = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakelockEnabled = false
    private var audioDeviceCallbackRegistered = false
    private var audioRouteRecoveryJob: Job? = null
    private var audiblePlaybackRecoveryJob: Job? = null
    private var lastAudioOutputDeviceSignature: String? = null
    private var lastAudioRouteRecoveryRealtimeMs = 0L

    private lateinit var audioOutputResolver: AudioOutputResolver

    val activeAudioDevice get() = audioOutputResolver.activeAudioDevice

    fun refreshActiveDevice() = audioOutputResolver.refresh()

    private val audioDeviceCallback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
                if (addedDevices.any { it.isSink }) onAudioOutputDeviceChanged()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
                if (removedDevices.any { it.isSink }) onAudioOutputDeviceChanged()
            }
        }

    private var scopeJob = Job()
    private var scope = CoroutineScope(Dispatchers.Main + scopeJob)
    private var ioScope = CoroutineScope(Dispatchers.IO + scopeJob)
    private val binder = MusicBinder()
    private var hasBoundClients = false
    private var idleStopJob: Job? = null

    private lateinit var connectivityManager: ConnectivityManager
    lateinit var connectivityObserver: NetworkConnectivityObserver
    val waitingForNetworkConnection = MutableStateFlow(false)
    private val isNetworkConnected = MutableStateFlow(false)

    private val audioQuality by enumPreference(
        this,
        AudioQualityKey,
        moe.rukamori.archivetune.constants.AudioQuality.AUTO,
    )
    private val preferredStreamClient by enumPreference(
        this,
        PlayerStreamClientKey,
        PlayerStreamClient.ANDROID_VR,
    )
    private val playbackUrlCache = ConcurrentHashMap<String, AuthScopedCacheValue>()
    private val extractorPlaybackUrlCache = ConcurrentHashMap<String, AuthScopedCacheValue>()
    private val remotePlaybackTrackingUrlCache = ConcurrentHashMap<String, String>()
    private val contentLengthCache = ConcurrentHashMap<String, Long>()
    private val extractorTokenRepository by lazy {
        InMemoryBearerTokenRepository(moe.rukamori.archivetune.BuildConfig.EXTRACTOR_BEARER)
    }
    private val _extractorAuthenticationEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val extractorAuthenticationEvents = _extractorAuthenticationEvents.asSharedFlow()
    private val streamingExtractionManagerDelegate =
        lazy {
            StreamingExtractionManager(
                tokenRepository = extractorTokenRepository,
                authenticationCallback = { notifyExtractorAuthenticationRequired() },
            )
        }
    private val streamingExtractionManager by streamingExtractionManagerDelegate
    private val mediaOkHttpClient: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .proxy(YouTube.streamOkHttpProxy)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                val host = request.url.host
                val isYouTubeMediaHost =
                    host.endsWith("googlevideo.com") ||
                        host.endsWith("googleusercontent.com") ||
                        host.endsWith("youtube.com") ||
                        host.endsWith("youtube-nocookie.com") ||
                        host.endsWith("ytimg.com")

                if (!isYouTubeMediaHost) return@addInterceptor chain.proceed(request)

                val requestProfile = StreamClientUtils.resolveRequestProfile(request.url)
                chain.proceed(
                    StreamClientUtils
                        .applyRequestProfile(
                            request.newBuilder(),
                            requestProfile,
                        ).build(),
                )
            }.build()
    }
    private val extractorMediaOkHttpClient: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .proxy(Proxy.NO_PROXY)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private var currentQueue: Queue = EmptyQueue
    var queueTitle: String? = null
    private var blockedArtistIds: Set<String> = emptySet()
    private var hideMusicVideos = false
    private var infiniteQueueJob: Job? = null
    private var infiniteQueueGeneration = 0L
    private val persistentStateLock = Any()
    private val persistentSaveGeneration = AtomicLong(0L)

    @Volatile
    private var isRestoringPersistentState = false

    @Volatile
    private var isHydratingRestoredQueue = false
    private val restoredQueueHydrationGeneration = AtomicLong(0L)
    private var restoredQueueBackfillJob: Job? = null

    @Volatile
    private var suppressAutoPlayback = false
    private var lastPresenceToken: String? = null

    @Volatile
    private var pausedPresenceGate = PausedPresenceGate.FollowPreference

    @Volatile
    private var discordServiceStopping = false

    @Volatile
    private var lastDiscordPresenceDecision: DiscordPresenceDecision? = null

    @Volatile
    private var activeDiscordHoldState: ActiveHoldState? = null

    private var activeDiscordHoldTimeoutJob: Job? = null

    @Volatile
    private var lastAppliedVisiblePresence: LastAppliedVisiblePresence? = null

    private val discordSyncEpoch = AtomicLong(0L)
    private val discordSyncRequests = Channel<DiscordSyncRequest>(Channel.CONFLATED)
    private var discordSyncWorkerJob: Job? = null
    private val pendingDiscordRefreshWaiters = mutableListOf<CompletableDeferred<Boolean>>()
    private val discordRefreshWaitersMutex = Mutex()
    private val toggleLikeMutex = Mutex()

    @Volatile
    private var lastLoginRecoveryPrompt: Pair<String, Long>? = null
    private val playbackStreamRecoveryTracker = PlaybackStreamRecoveryTracker()
    private var nextHistorySessionToken = 0L
    private var currentHistorySessionToken = 0L
    private var currentHistoryMediaId: String? = null
    private var currentHistoryAccumulatedPlayMs = 0L
    private var currentHistoryStartedAtElapsedMs: Long? = null
    private var currentHistoryEventId: Long? = null
    private var currentHistoryRemoteRegistered = false
    private var currentHistoryImmediateAttempted = false
    private var currentHistorySessionQueued = false
    private var historyThresholdJob: Job? = null
    private val pendingHistoryFinalizations = mutableMapOf<String, MutableList<PendingHistoryFinalization>>()
    private val historyRecordingJobs = ConcurrentHashMap<Long, kotlinx.coroutines.Deferred<ImmediateHistoryResult>>()

    val currentMediaMetadata = MutableStateFlow<moe.rukamori.archivetune.models.MediaMetadata?>(null)
    val queueRestoreCompleted = MutableStateFlow(false)
    val infiniteQueueLoading = MutableStateFlow(false)
    private val playerInitialized = MutableStateFlow(false)
    private val currentSong =
        currentMediaMetadata
            .flatMapLatest { mediaMetadata ->
                database.song(mediaMetadata?.id)
            }.flowOn(Dispatchers.IO)
            .stateIn(scope, SharingStarted.Lazily, null)
    private val currentFormat =
        currentMediaMetadata
            .flatMapLatest { mediaMetadata ->
                database.format(mediaMetadata?.id)
            }.flowOn(Dispatchers.IO)

    private val normalizeFactor = MutableStateFlow(1f)
    private val audioNormalizationFactorCache = ConcurrentHashMap<String, Float>()
    private var audioNormalizationEnabled = true
    var playerVolume = MutableStateFlow(1f)
    private val audioFocusVolumeFactor = MutableStateFlow(1f)
    private var effectiveVolumeRampJob: Job? = null
    private var crossfadeEnabled = false
    private var crossfadeDurationMs = 0L
    private var crossfadeGapless = false
    private var crossfadeTriggerJob: Job? = null
    private var crossfadeJob: Job? = null
    private var secondaryCrossfadePlayer: ExoPlayer? = null
    private var secondaryCrossfadeTarget: CrossfadeTarget? = null
    private var isCrossfading = false
    private var crossfadeHandoffInProgress = false
    private var crossfadeBaseVolume = 1f
    private var crossfadeIncomingBaseVolume = 1f
    private var crossfadeProgress = 0f
    private var crossfadePlaybackRequested = false
    private var lyricsPreloadManager: LyricsPreloadManager? = null

    private val secondaryCrossfadeListener =
        object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Timber.tag(TAG).w(error, "Secondary crossfade player failed")
                scope.launch {
                    cancelCrossfade(resetVolume = true, resetPauseAtEnd = true)
                    scheduleCrossfade()
                }
            }
        }

    private data class CrossfadeConfig(
        val enabled: Boolean,
        val durationSeconds: Float,
        val gapless: Boolean,
    )

    private data class DiscordSyncRequest(
        val epoch: Long,
        val reason: String,
        val force: Boolean,
    )

    private data class Quadruple<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
    )

    private class StaleDiscordSyncException : CancellationException("Stale Discord sync request")

    private data class CrossfadeTarget(
        val index: Int,
        val mediaId: String,
    )

    private data class PendingHistoryFinalization(
        val sessionToken: Long,
        val eventId: Long?,
        val remoteRegistered: Boolean,
    )

    private data class ImmediateHistoryResult(
        val eventId: Long?,
        val remoteRegistered: Boolean,
    )

    private fun PlayerResponse.PlaybackTracking.remotePlaybackTrackingUrl(): String? =
        videostatsPlaybackUrl
            ?.baseUrl
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun isAppInForeground(): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val appProcesses = activityManager.runningAppProcesses ?: return false
        return appProcesses.any { processInfo ->
            processInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                processInfo.processName == packageName
        }
    }

    private fun promptLoginRecovery(
        mediaId: String,
        targetUrl: String,
    ) {
        if (!isAppInForeground()) return

        val now = System.currentTimeMillis()
        val lastPrompt = lastLoginRecoveryPrompt
        if (lastPrompt?.first == mediaId && now - lastPrompt.second < 10000L) return
        lastLoginRecoveryPrompt = mediaId to now

        val deepLink = Uri.parse("archivetune://login?url=${Uri.encode(targetUrl)}")
        val intent =
            Intent(Intent.ACTION_VIEW, deepLink, this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

        runCatching {
            startActivity(intent)
        }.onFailure {
            Timber.e(it, "Failed to open login recovery for %s", mediaId)
        }
    }

    private fun Throwable.isRequestTimeout(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is SocketTimeoutException) return true
            if (current.message?.contains("Request timeout has expired", ignoreCase = true) == true) return true
            current = current.cause
        }
        return false
    }

    private fun Throwable.isNetworkConnectionFailure(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is ConnectException || current is UnknownHostException) return true
            current = current.cause
        }
        return false
    }

    lateinit var sleepTimer: SleepTimer

    @Inject
    @PlayerCache
    lateinit var playerCache: Cache

    @Inject
    @DownloadCache
    lateinit var downloadCache: Cache

    lateinit var localPlayer: ExoPlayer
        private set
    lateinit var player: Player
        private set
    private lateinit var castPlaybackRepository: CastPlaybackRepository
    private lateinit var mediaSession: MediaLibrarySession

    private var isAudioEffectSessionOpened = false
    private var openedAudioSessionId: Int? = null
    val eqCapabilities = MutableStateFlow<EqCapabilities?>(null)
    private val desiredEqSettings =
        MutableStateFlow(
            EqSettings(
                enabled = false,
                bandLevelsMb = emptyList(),
                outputGainEnabled = false,
                outputGainMb = 0,
                bassBoostEnabled = false,
                bassBoostStrength = 0,
                virtualizerEnabled = false,
                virtualizerStrength = 0,
                autoHeadroomEnabled = false,
            ),
        )

    private var audioEffectsSessionId: Int? = null
    private var audioEffectsInitializationJob: Job? = null
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private val audioEffectPlayerListener =
        object : Player.Listener {
            override fun onEvents(
                player: Player,
                events: Player.Events,
            ) {
                if (events.containsAny(
                        Player.EVENT_AUDIO_SESSION_ID,
                        Player.EVENT_PLAYBACK_STATE_CHANGED,
                        Player.EVENT_IS_PLAYING_CHANGED,
                    )
                ) {
                    reconcileAudioEffectSession()
                }
            }
        }

    private var lastDiscordUpdateTime = 0L

    private var scrobbleManager: moe.rukamori.archivetune.utils.ScrobbleManager? = null

    private lateinit var widgetUpdater: MusicServiceWidgetUpdater

    val autoAddedMediaIds: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    private var consecutivePlaybackErr = 0

    val maxSafeGainFactor = MAX_AUDIO_NORMALIZATION_FACTOR

    @Volatile
    private var hasCalledStartForeground = false

    val togetherSessionState =
        MutableStateFlow<moe.rukamori.archivetune.together.TogetherSessionState>(
            moe.rukamori.archivetune.together.TogetherSessionState.Idle,
        )
    private var togetherServer: moe.rukamori.archivetune.together.TogetherServer? = null
    private var togetherOnlineHost: moe.rukamori.archivetune.together.TogetherOnlineHost? = null
    private var togetherClient: moe.rukamori.archivetune.together.TogetherClient? = null
    private var togetherBroadcastJob: Job? = null
    private var togetherOnlineConnectJob: Job? = null
    private var togetherClientEventsJob: Job? = null
    private var togetherHeartbeatJob: Job? = null
    private var togetherHostInactivityJob: Job? = null
    private var togetherHostInactivityEndSession: (suspend () -> Unit)? = null
    private var togetherClock: moe.rukamori.archivetune.together.TogetherClock? = null
    private var togetherSelfParticipantId: String? = null
    private var togetherAuthorityParticipantId: String? = null
    private var togetherLastAppliedQueueHash: String? = null
    private var togetherIsOnlineSession: Boolean = false

    private var tunnelProvider: TunnelProvider = NoOpTunnelProvider()

    fun setTunnelProvider(provider: TunnelProvider) {
        tunnelProvider = provider
    }

    @Volatile
    private var togetherApplyingRemote: Boolean = false

    @Volatile
    private var togetherSuppressEchoUntilElapsedMs: Long = 0L

    @Volatile
    private var togetherLastAppliedRoomStateSentAtElapsedMs: Long = 0L

    @Volatile
    private var togetherLastRemoteAppliedPlayWhenReady: Boolean? = null

    @Volatile
    private var togetherLastRemoteAppliedIndex: Int = -1

    @Volatile
    private var togetherLastSentControlAtElapsedMs: Long = 0L

    @Volatile
    private var togetherLastSentControlAction: moe.rukamori.archivetune.together.ControlAction? = null

    @Volatile
    private var togetherPendingGuestControl: TogetherPendingGuestControl? = null

    private fun isTogetherApplyingRemote(): Boolean = togetherApplyingRemote

    private val togetherHostId: String = "host"
    private val togetherParticipantNames = ConcurrentHashMap<String, String>()
    private var lastTogetherNoticeAtElapsedMs: Long = 0L
    private var lastTogetherNoticeKey: String? = null

    private data class TogetherPendingGuestControl(
        val desiredIsPlaying: Boolean? = null,
        val desiredIndex: Int? = null,
        val desiredTrackId: String? = null,
        val requestedAtElapsedMs: Long,
        val expiresAtElapsedMs: Long,
    )

    private fun showTogetherNotice(
        message: String,
        key: String? = null,
    ) {
        val now = android.os.SystemClock.elapsedRealtime()
        val normalizedKey = key ?: message
        if (normalizedKey == lastTogetherNoticeKey && now - lastTogetherNoticeAtElapsedMs < 1200L) return
        lastTogetherNoticeKey = normalizedKey
        lastTogetherNoticeAtElapsedMs = now
        scope.launch(SilentHandler) {
            Toast.makeText(this@MusicService, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showTogetherParticipantNotification(
        participantName: String,
        joined: Boolean,
    ) {
        val normalizedName = participantName.trim().ifBlank { getString(R.string.together_unknown_participant) }
        val contentText =
            getString(
                if (joined) {
                    R.string.together_participant_joined_notification
                } else {
                    R.string.together_participant_left_notification
                },
                normalizedName,
            )
        val contentIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat
                .Builder(this, TOGETHER_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.small_icon)
                .setContentTitle(getString(R.string.music_together))
                .setContentText(contentText)
                .setContentIntent(contentIntent)
                .setCategory(Notification.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()

        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(TOGETHER_PARTICIPANT_NOTIFICATION_ID, notification)
        }.onFailure { error ->
            Timber.tag("Together").v(error, "Unable to show participant notification")
        }
    }

    private fun showTogetherInactivityNotification() {
        val contentIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat
                .Builder(this, TOGETHER_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.small_icon)
                .setContentTitle(getString(R.string.music_together))
                .setContentText(getString(R.string.together_room_closed_inactivity_notification))
                .setContentIntent(contentIntent)
                .setCategory(Notification.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()

        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(TOGETHER_INACTIVITY_NOTIFICATION_ID, notification)
        }.onFailure { error ->
            Timber.tag("Together").v(error, "Unable to show inactivity notification")
        }
    }

    private fun cancelTogetherHostInactivityTimeout() {
        togetherHostInactivityJob?.cancel()
        togetherHostInactivityJob = null
    }

    private fun scheduleTogetherHostInactivityTimeout(
        sessionId: String,
        endOnlineSession: (suspend () -> Unit)? = togetherHostInactivityEndSession,
    ) {
        cancelTogetherHostInactivityTimeout()
        togetherHostInactivityEndSession = endOnlineSession
        togetherHostInactivityJob =
            ioScope.launch(SilentHandler) {
                delay(TOGETHER_HOST_INACTIVITY_TIMEOUT_MS)

                val currentState = togetherSessionState.value
                val isCurrentHostSession =
                    when (currentState) {
                        is moe.rukamori.archivetune.together.TogetherSessionState.Hosting -> {
                            currentState.sessionId == sessionId
                        }

                        is moe.rukamori.archivetune.together.TogetherSessionState.HostingOnline -> {
                            currentState.sessionId == sessionId
                        }

                        is moe.rukamori.archivetune.together.TogetherSessionState.Joined -> {
                            currentState.sessionId == sessionId &&
                                currentState.role is moe.rukamori.archivetune.together.TogetherRole.Host
                        }

                        else -> {
                            false
                        }
                    }
                val isLocalAuthority =
                    togetherAuthorityParticipantId == null ||
                        togetherAuthorityParticipantId == togetherHostId
                val participants =
                    togetherServer?.currentParticipants()
                        ?: togetherOnlineHost?.currentParticipants()
                        ?: emptyList()
                val hasConnectedGuest =
                    participants.any { participant ->
                        participant.id != togetherHostId &&
                            participant.isConnected &&
                            !participant.isPending
                    }
                if (!isCurrentHostSession ||
                    !isLocalAuthority ||
                    togetherParticipantNames.isNotEmpty() ||
                    hasConnectedGuest
                ) {
                    togetherHostInactivityJob = null
                    return@launch
                }

                togetherHostInactivityJob = null
                runCatching { endOnlineSession?.invoke() }
                    .onFailure { error ->
                        Timber.tag("Together").w(error, "Unable to end inactive online room")
                    }
                stopTogetherInternal()
                togetherSessionState.value = moe.rukamori.archivetune.together.TogetherSessionState.Idle
                showTogetherInactivityNotification()
                scheduleStopIfIdle()
            }
    }

    private suspend fun getOrCreateTogetherClientId(): String {
        val existing = dataStore.getAsync(TogetherClientIdKey)?.trim().orEmpty()
        if (existing.isNotBlank()) return existing
        val generated =
            java.util.UUID
                .randomUUID()
                .toString()
        dataStore.edit { prefs -> prefs[TogetherClientIdKey] = generated }
        return generated
    }

    private fun ensureStartedAsForeground() {
        if (hasCalledStartForeground) return

        val notification =
            try {
                val contentIntent =
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )

                NotificationCompat
                    .Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.small_icon)
                    .setContentTitle(getString(R.string.music_player))
                    .setContentText(getString(R.string.app_name))
                    .setContentIntent(contentIntent)
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .build()
            } catch (e: Exception) {
                reportException(e)
                return
            }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            hasCalledStartForeground = true
        } catch (e: Exception) {
            reportException(e)
        }
    }

    private fun promoteToStartedService() {
        runCatching { startService(Intent(this, MusicService::class.java)) }
            .onFailure { reportException(it) }
    }

    private fun cancelIdleStop() {
        idleStopJob?.cancel()
        idleStopJob = null
    }

    private fun hasResumablePlaybackNotification(): Boolean {
        val state = player.playbackState
        return player.mediaItemCount > 0 &&
            player.currentMediaItem != null &&
            state != Player.STATE_IDLE &&
            state != Player.STATE_ENDED
    }

    private fun stopForegroundAndSelf() {
        cancelIdleStop()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                stopForeground(true)
            }
        }
        hasCalledStartForeground = false
        stopSelf()
    }

    private fun scheduleStopIfIdle() {
        if (hasBoundClients) return
        if (hasResumablePlaybackNotification()) {
            cancelIdleStop()
            promoteToStartedService()
            ensureStartedAsForeground()
            return
        }
        val togetherIdle = togetherSessionState.value is moe.rukamori.archivetune.together.TogetherSessionState.Idle
        if (!togetherIdle) {
            cancelIdleStop()
            return
        }

        val state = player.playbackState
        val delayMs =
            when (state) {
                Player.STATE_ENDED, Player.STATE_IDLE -> 30_000L
                else -> 60_000L
            }

        cancelIdleStop()
        idleStopJob =
            scope.launch {
                delay(delayMs)
                if (hasBoundClients) return@launch
                if (hasResumablePlaybackNotification()) return@launch
                if (togetherSessionState.value !is moe.rukamori.archivetune.together.TogetherSessionState.Idle) return@launch
                stopForegroundAndSelf()
            }
    }

    override fun onCreate() {
        super.onCreate()
        equalizerPlaybackController.attach(this)
        ensureScopesActive()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = getSystemService(NotificationManager::class.java)
                nm?.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.music_player),
                        NotificationManager.IMPORTANCE_LOW,
                    ),
                )
                nm?.createNotificationChannel(
                    NotificationChannel(
                        TOGETHER_NOTIFICATION_CHANNEL_ID,
                        getString(R.string.music_together),
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ),
                )
            }
        
        // Use the extractor client for tunnel discovery
        setTunnelProvider(NgrokTunnelProvider(extractorMediaOkHttpClient))} catch (e: Exception) {
            reportException(e)
        }

        localPlayer =
            ExoPlayer
                .Builder(this)
                .setMediaSourceFactory(createMediaSourceFactory())
                .setRenderersFactory(createRenderersFactory())
                .setLoadControl(createPrimaryLoadControl())
                .setTrackSelector(DefaultTrackSelector(this, SafeTrackSelectionFactory()))
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .setAudioAttributes(
                    playbackAudioAttributes(),
                    false,
                ).setSeekBackIncrementMs(5000)
                .setSeekForwardIncrementMs(5000)
                .setDeviceVolumeControlEnabled(true)
                .build()
                .apply {
                    addAnalyticsListener(PlaybackStatsListener(false, this@MusicService))
                    addListener(audioEffectPlayerListener)
                    setOffloadEnabled(false)
                }
        castPlaybackRepository = CastPlaybackRepositoryLocator.get(this)
        player =
            castPlaybackRepository
                .createPlayer(
                    context = this,
                    localPlayer = localPlayer,
                    mediaItemResolver = CastMediaItemResolver(::resolveMediaItemForCast),
                ).apply {
                    addListener(this@MusicService)
                    sleepTimer = SleepTimer(scope, this, this@MusicService)
                    addListener(sleepTimer)
                }
        playerInitialized.value = true
        database
            .blockedArtistIds()
            .map { ids -> ids.toSet() }
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)
            .collect(scope) { updatedBlockedArtistIds ->
                blockedArtistIds = updatedBlockedArtistIds
                removeBlockedArtistItems(updatedBlockedArtistIds)
            }
        dataStore.data
            .map { preferences -> preferences[HideVideoKey] ?: false }
            .distinctUntilChanged()
            .collect(scope) { shouldHideMusicVideos ->
                hideMusicVideos = shouldHideMusicVideos
                if (shouldHideMusicVideos) {
                    removeMusicVideoItems()
                }
            }
        widgetUpdater =
            MusicServiceWidgetUpdater(
                service = this,
                player = player,
                scope = scope,
                loadWidgetInsights = loadWidgetInsightsUseCase,
            )

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioOutputResolver = AudioOutputResolver(audioManager)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            audioManager.setAllowedCapturePolicy(android.media.AudioAttributes.ALLOW_CAPTURE_BY_ALL)
        }
        wakeLock =
            (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ArchiveTune:Playback")
                .also { it.setReferenceCounted(false) }
        setupAudioFocusRequest()
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, android.os.Handler(mainLooper))
        audioDeviceCallbackRegistered = true
        lastAudioOutputDeviceSignature = currentAudioOutputDeviceSignature()
        audioOutputResolver.refresh()

        mediaLibrarySessionCallback.apply {
            toggleLike = ::toggleLike
            toggleStartRadio = ::toggleStartRadio
            toggleLibrary = ::toggleLibrary
        }
        mediaSession =
            MediaLibrarySession
                .Builder(this, player, mediaLibrarySessionCallback)
                .setSessionActivity(
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                ).setBitmapLoader(CoilBitmapLoader(this, scope))
                .build()
        setMediaNotificationProvider(
            ArchiveTuneMediaNotificationProvider(
                context = this,
                smallIconResId = R.drawable.small_icon,
            ),
        )

        updateNotification()
        player.repeatMode = REPEAT_MODE_OFF

        val sessionToken = SessionToken(this, ComponentName(this, MusicService::class.java))
        val controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({ controllerFuture.get() }, MoreExecutors.directExecutor())
        scope.launch(Dispatchers.IO) {
            val prefs = dataStore.data.first()
            val repeatMode = prefs[RepeatModeKey] ?: REPEAT_MODE_OFF
            val volume = (prefs[PlayerVolumeKey] ?: 1f).coerceIn(0f, 1f)
            val offload = prefs[AudioOffload] ?: false
            val crossfadePrefEnabled = prefs[CrossfadeEnabledKey] ?: false
            withContext(Dispatchers.Main) {
                player.repeatMode = repeatMode
                playerVolume.value = volume
                updateAudioOffload(offload && !crossfadePrefEnabled)
            }
        }

        connectivityManager = getSystemService()!!
        connectivityObserver = NetworkConnectivityObserver(this)

        scope.launch {
            connectivityObserver.networkStatus.collect { isConnected ->
                isNetworkConnected.value = isConnected
                if (isConnected && waitingForNetworkConnection.value) {
                    waitingForNetworkConnection.value = false
                    if (player.currentMediaItem != null && player.playWhenReady &&
                        player.playbackState == Player.STATE_IDLE
                    ) {
                        player.prepare()
                        player.play()
                    }
                }
            }
        }

        combine(playerVolume, normalizeFactor, audioFocusVolumeFactor) { playerVolume, normalizeFactor, audioFocusVolumeFactor ->
            calculateEffectivePlayerVolume(playerVolume, normalizeFactor, audioFocusVolumeFactor)
        }.collectLatest(scope) { finalVolume ->
            updateEffectiveVolume(finalVolume)
        }

        playerVolume.debounce(1000).collect(ioScope) { volume ->
            dataStore.edit { settings ->
                settings[PlayerVolumeKey] = volume
            }
        }

        currentSong.debounce(300).collect(scope) { song ->
            updateNotification()
            requestDiscordSync(
                reason =
                    if (song == null) {
                        "current_song_cleared"
                    } else {
                        "current_song_changed"
                    },
            )
            if (song != null && player.playWhenReady && player.playbackState == Player.STATE_READY) {
                ensurePresenceManager()
            }
        }

        combine(
            currentMediaMetadata.distinctUntilChangedBy { it?.id },
            dataStore.data.map { it[ShowLyricsKey] ?: false }.distinctUntilChanged(),
        ) { mediaMetadata, showLyrics ->
            mediaMetadata to showLyrics
        }.collectLatest(ioScope) { (mediaMetadata, showLyrics) ->
            if (showLyrics && mediaMetadata != null && database
                    .lyrics(mediaMetadata.id)
                    .first() == null
            ) {
                val lyrics = lyricsHelper.getLyrics(mediaMetadata)
                database.query {
                    insertLyricsIfAbsent(
                        id = mediaMetadata.id,
                        lyrics = lyrics,
                    )
                }
            }
        }

        dataStore.data
            .map { it[SkipSilenceKey] ?: false }
            .distinctUntilChanged()
            .collectLatest(scope) {
                localPlayer.skipSilenceEnabled = it
                secondaryCrossfadePlayer?.skipSilenceEnabled = it
            }

        dataStore.data
            .map { it[PauseOnDeviceMuteKey] ?: false }
            .distinctUntilChanged()
            .collectLatest(scope) { enabled ->
                pauseOnDeviceMuteEnabled = enabled
                if (!enabled) {
                    wasAutoPausedByDeviceMute = false
                    unregisterMuteRecoveryObserver()
                } else {
                    handleDeviceMuteStateChanged()
                }
            }

        dataStore.data
            .map { (it[DeviceMutePlaybackRecoveryVolumeKey] ?: 0).coerceIn(0, 100) }
            .distinctUntilChanged()
            .collectLatest(scope) { percent ->
                deviceMutePlaybackRecoveryVolumePercent = percent
            }

        dataStore.data
            .map { it[AutoStartOnBluetoothKey] ?: false }
            .distinctUntilChanged()
            .collectLatest(scope) { enabled ->
                autoStartOnBluetoothEnabled = enabled
                if (enabled) {
                    registerBluetoothReceiver()
                } else {
                    unregisterBluetoothReceiver()
                }
            }

        combine(
            dataStore.data.map { it[AudioOffload] ?: false },
            dataStore.data.map { it[CrossfadeEnabledKey] ?: false },
        ) { offloadEnabled, crossfadeEnabled ->
            offloadEnabled to crossfadeEnabled
        }.distinctUntilChanged()
            .collectLatest(scope) { (offloadEnabled, crossfadeEnabled) ->
                val effectiveOffload = offloadEnabled && !crossfadeEnabled
                updateAudioOffload(effectiveOffload)
                if (effectiveOffload) {
                    val skipSilenceEnabled = dataStore.get(SkipSilenceKey, false)
                    if (skipSilenceEnabled) {
                        dataStore.edit { it[SkipSilenceKey] = false }
                        localPlayer.skipSilenceEnabled = false
                    }
                }
            }

        combine(dataStore.data, togetherSessionState) { prefs, togetherState ->
            val enabled = prefs[CrossfadeEnabledKey] ?: false
            val durationSeconds = prefs[CrossfadeDurationKey] ?: 5f
            val gapless = prefs[CrossfadeGaplessKey] ?: true
            CrossfadeConfig(
                enabled = enabled && togetherState is moe.rukamori.archivetune.together.TogetherSessionState.Idle,
                durationSeconds = durationSeconds,
                gapless = gapless,
            )
        }.distinctUntilChanged()
            .collectLatest(scope) { config ->
                crossfadeEnabled = config.enabled
                crossfadeDurationMs =
                    (config.durationSeconds.coerceIn(0f, 10f) * 1000f)
                        .roundToLong()
                        .coerceAtLeast(0L)
                crossfadeGapless = config.gapless
                if (crossfadeEnabled && crossfadeDurationMs > 0L) {
                    scheduleCrossfade()
                } else {
                    cancelCrossfade(resetVolume = true, resetPauseAtEnd = true)
                }
            }

        dataStore.data
            .map { it[WakelockKey] ?: false }
            .distinctUntilChanged()
            .collectLatest(scope) { enabled ->
                wakelockEnabled = enabled
                updateWakeLock()
            }

        // Initialize lyrics pre-load manager
        lyricsPreloadManager =
            LyricsPreloadManager(
                context = this,
                database = database,
                networkConnectivity = connectivityObserver,
                lyricsHelper = lyricsHelper,
            )

        dataStore.data
            .map(::readEqSettingsFromPrefs)
            .distinctUntilChanged()
            .collectLatest(scope) { settings ->
                desiredEqSettings.value = settings
                applyEqSettingsToEffects(settings)
            }

        combine(
            currentMediaMetadata
                .map { it?.id }
                .distinctUntilChanged(),
            currentFormat,
            dataStore.data
                .map { it[AudioNormalizationKey] ?: true }
                .distinctUntilChanged(),
        ) { mediaId, format, normalizeAudio ->
            normalizeAudio to resolveAudioNormalizationFactor(mediaId, format, normalizeAudio)
        }.distinctUntilChanged()
            .collectLatest(scope) { (normalizeAudio, factor) ->
                audioNormalizationEnabled = normalizeAudio
                normalizeFactor.value = factor
            }

        dataStore.data
            .map { it[DiscordTokenKey] to (it[EnableDiscordRPCKey] ?: true) }
            .debounce(300)
            .distinctUntilChanged()
            .collectLatest(scope) { (key, enabled) ->
                requestDiscordSync(
                    reason =
                        when {
                            !enabled -> "discord_rpc_disabled"
                            key.isNullOrBlank() -> "discord_token_missing"
                            else -> "discord_token_or_toggle_changed"
                        },
                    force = !enabled || key.isNullOrBlank(),
                )
                if (!key.isNullOrBlank() && enabled) {
                    if (player.playbackState == Player.STATE_READY && player.playWhenReady) {
                        currentSong.value?.let {
                            ensurePresenceManager()
                        }
                    }
                }
            }

        dataStore.data
            .map { prefs ->
                (prefs[SmartTrimmerKey] ?: false) to (prefs[MaxSongCacheSizeKey] ?: 1024)
            }.debounce(300)
            .distinctUntilChanged()
            .collectLatest(ioScope) { (enabled, maxSongCacheSizeMb) ->
                if (!enabled) return@collectLatest
                if (maxSongCacheSizeMb <= 0 || maxSongCacheSizeMb == -1) return@collectLatest
                val bytesPerMb = 1024L * 1024L
                val safeSizeMb = maxSongCacheSizeMb.toLong().coerceAtMost(Long.MAX_VALUE / bytesPerMb)
                val limitBytes = safeSizeMb * bytesPerMb
                trimPlayerCacheToBytes(limitBytes)
            }

        dataStore.data
            .map { preferences ->
                val serviceConfig = LastFmServiceConfig.fromPreferences(preferences)
                Triple(
                    preferences[EnableLastFMScrobblingKey] ?: false,
                    !preferences[LastFMSessionKey].isNullOrBlank(),
                    serviceConfig.initialized,
                )
            }.debounce(300)
            .distinctUntilChanged()
            .collect(scope) { (enabled, hasSession, serviceConfigured) ->
                val shouldEnable = enabled && hasSession && serviceConfigured
                if (shouldEnable && scrobbleManager == null) {
                    val delayPercent = dataStore.get(ScrobbleDelayPercentKey, LastFM.DEFAULT_SCROBBLE_DELAY_PERCENT)
                    val minSongDuration = dataStore.get(ScrobbleMinSongDurationKey, LastFM.DEFAULT_SCROBBLE_MIN_SONG_DURATION)
                    val delaySeconds = dataStore.get(ScrobbleDelaySecondsKey, LastFM.DEFAULT_SCROBBLE_DELAY_SECONDS)

                    scrobbleManager =
                        moe.rukamori.archivetune.utils.ScrobbleManager(
                            ioScope,
                            minSongDuration = minSongDuration,
                            scrobbleDelayPercent = delayPercent,
                            scrobbleDelaySeconds = delaySeconds,
                        )
                    scrobbleManager?.useNowPlaying = dataStore.get(LastFMUseNowPlaying, false)
                } else if (!shouldEnable && scrobbleManager != null) {
                    scrobbleManager?.destroy()
                    scrobbleManager = null
                }
            }

        dataStore.data
            .map { it[LastFMUseNowPlaying] ?: false }
            .distinctUntilChanged()
            .collectLatest(scope) {
                scrobbleManager?.useNowPlaying = it
            }

        dataStore.data
            .map { prefs ->
                Triple(
                    prefs[ScrobbleDelayPercentKey] ?: LastFM.DEFAULT_SCROBBLE_DELAY_PERCENT,
                    prefs[ScrobbleMinSongDurationKey] ?: LastFM.DEFAULT_SCROBBLE_MIN_SONG_DURATION,
                    prefs[ScrobbleDelaySecondsKey] ?: LastFM.DEFAULT_SCROBBLE_DELAY_SECONDS,
                )
            }.distinctUntilChanged()
            .collect(scope) { (delayPercent, minSongDuration, delaySeconds) ->
                scrobbleManager?.let {
                    it.scrobbleDelayPercent = delayPercent
                    it.minSongDuration = minSongDuration
                    it.scrobbleDelaySeconds = delaySeconds
                }
            }

        scope.launch(Dispatchers.IO) {
            runCatching {
                if (dataStore.get(PersistentQueueKey, true)) {
                    playerInitialized.first { it }
                    val persistedQueue = readPersistentObject<PersistQueue>(PERSISTENT_QUEUE_FILE)
                    val persistedPlayerState = readPersistentObject<PersistPlayerState>(PERSISTENT_PLAYER_STATE_FILE)

                    if (persistedQueue != null || persistedPlayerState != null) {
                        isRestoringPersistentState = true
                    }

                    var restoredQueue = false
                    try {
                        persistedQueue?.let { queue ->
                            restorePersistentQueue(queue)
                            restoredQueue = true
                        }
                        persistedPlayerState?.let { playerState ->
                            restorePersistentPlayerState(playerState, restoredQueue)
                        }
                    } finally {
                        isRestoringPersistentState = false
                    }
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                Timber.tag(TAG).w(error, "Failed to restore persisted queue, clearing data")
                isRestoringPersistentState = false
                cancelRestoredQueueHydration()
                clearPersistedQueueFiles()
            }
            withContext(Dispatchers.Main) {
                queueRestoreCompleted.value = true
            }
        }

        scope.launch {
            while (isActive) {
                delay(if (player.isPlaying) 10.seconds else 30.seconds)
                val shouldSave = withContext(Dispatchers.IO) { dataStore.get(PersistentQueueKey, true) }
                if (shouldSave && player.mediaItemCount > 0) {
                    saveQueueToDisk()
                }
            }
        }
    }

    private fun ensureScopesActive() {
        if (!scopeJob.isActive) {
            scopeJob = Job()
        }
        if (!scope.isActive) {
            scope = CoroutineScope(Dispatchers.Main + scopeJob)
        }
        if (!ioScope.isActive) {
            ioScope = CoroutineScope(Dispatchers.IO + scopeJob)
        }
        startDiscordSyncWorker()
    }

    private fun startDiscordSyncWorker() {
        if (discordSyncWorkerJob?.isActive == true) return
        discordSyncWorkerJob =
            scope.launch(Dispatchers.IO) {
                for (request in discordSyncRequests) {
                    try {
                        syncDiscordStateInternal(request)
                    } catch (_: StaleDiscordSyncException) {
                        Timber.tag(DISCORD_SYNC_TAG).d(
                            "stale sync aborted epoch=%d reason=%s",
                            request.epoch,
                            request.reason,
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Timber.tag(DISCORD_SYNC_TAG).e(
                            error,
                            "sync failed epoch=%d reason=%s",
                            request.epoch,
                            request.reason,
                        )
                    }
                }
            }
    }

    private fun requestDiscordSync(
        reason: String,
        force: Boolean = false,
    ) {
        val request =
            DiscordSyncRequest(
                epoch = discordSyncEpoch.incrementAndGet(),
                reason = reason,
                force = force,
            )
        if (discordSyncRequests.trySend(request).isFailure) {
            Timber.tag(DISCORD_SYNC_TAG).w(
                "failed to enqueue sync epoch=%d reason=%s",
                request.epoch,
                request.reason,
            )
        }
    }

    fun forceDiscordSync(reason: String) {
        requestDiscordSync(
            reason = reason,
            force = true,
        )
    }

    private fun ensureDiscordSyncFresh(epoch: Long) {
        if (epoch != discordSyncEpoch.get()) {
            throw StaleDiscordSyncException()
        }
    }

    private fun updateActiveDiscordHoldState(nextHoldState: ActiveHoldState?) {
        val previousHoldState = activeDiscordHoldState
        activeDiscordHoldState = nextHoldState
        Timber.tag(DISCORD_SYNC_TAG).d(
            "hold state transition previous=%s next=%s",
            previousHoldState,
            nextHoldState,
        )
        reconcileDiscordHoldTimeoutJob(previousHoldState, nextHoldState)
    }

    private fun reconcileDiscordHoldTimeoutJob(
        previousHoldState: ActiveHoldState?,
        nextHoldState: ActiveHoldState?,
    ) {
        if (previousHoldState === nextHoldState) {
            Timber.tag(DISCORD_SYNC_TAG).v("hold timeout job unchanged for holdState=%s", nextHoldState)
            return
        }

        activeDiscordHoldTimeoutJob?.cancel()
        activeDiscordHoldTimeoutJob = null

        if (nextHoldState == null) {
            Timber.tag(DISCORD_SYNC_TAG).d("no active hold state, no timeout job scheduled")
            return
        }

        Timber.tag(DISCORD_SYNC_TAG).d(
            "scheduling hold timeout job state=%s timeoutMs=%d",
            nextHoldState,
            DISCORD_HOLD_TIMEOUT_MS,
        )
        activeDiscordHoldTimeoutJob =
            scope.launch {
                delay(DISCORD_HOLD_TIMEOUT_MS)
                Timber.tag(DISCORD_SYNC_TAG).d(
                    "hold timeout fired state=%s -> enqueue resync",
                    nextHoldState,
                )
                requestDiscordSync(
                    reason = "hold_timeout_check",
                    force = true,
                )
            }
    }

    private fun clearDiscordHoldState() {
        if (activeDiscordHoldState != null) {
            Timber.tag(DISCORD_SYNC_TAG).d("clearing active hold state=%s", activeDiscordHoldState)
        }
        updateActiveDiscordHoldState(null)
    }

    private fun markLastAppliedVisiblePresence(visibleDecision: DiscordPresenceDecision.Visible) {
        lastAppliedVisiblePresence =
            LastAppliedVisiblePresence(
                songId = visibleDecision.songId,
                mode = visibleDecision.mode,
                appliedAtMs = System.currentTimeMillis(),
            )
        Timber.tag(DISCORD_SYNC_TAG).d(
            "marked last applied visible presence songId=%s mode=%s",
            visibleDecision.songId,
            visibleDecision.mode,
        )
    }

    private suspend fun addPendingDiscordRefreshWaiter(waiter: CompletableDeferred<Boolean>) {
        discordRefreshWaitersMutex.withLock {
            pendingDiscordRefreshWaiters += waiter
        }
    }

    private suspend fun takePendingDiscordRefreshWaiters(): List<CompletableDeferred<Boolean>> =
        discordRefreshWaitersMutex.withLock {
            val snapshot = pendingDiscordRefreshWaiters.toList()
            pendingDiscordRefreshWaiters.removeAll(snapshot)
            snapshot
        }

    private suspend fun requeueDiscordRefreshWaiters(waiters: List<CompletableDeferred<Boolean>>) {
        if (waiters.isEmpty()) return
        discordRefreshWaitersMutex.withLock {
            waiters.forEach { waiter ->
                if (!waiter.isCompleted && !waiter.isCancelled) {
                    pendingDiscordRefreshWaiters += waiter
                }
            }
        }
    }

    private fun completeDiscordRefreshWaiters(
        waiters: List<CompletableDeferred<Boolean>>,
        result: Boolean,
    ) {
        waiters.forEach { waiter ->
            if (!waiter.isCompleted && !waiter.isCancelled) {
                waiter.complete(result)
            }
        }
    }

    suspend fun refreshDiscordNow(): Boolean {
        val waiter = CompletableDeferred<Boolean>()
        addPendingDiscordRefreshWaiter(waiter)
        requestDiscordSync(
            reason = "manual_refresh",
            force = true,
        )
        return try {
            withTimeout(15_000L) { waiter.await() }
        } catch (error: CancellationException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun syncDiscordStateInternal(request: DiscordSyncRequest) {
        val refreshWaiters = takePendingDiscordRefreshWaiters()
        try {
            ensureDiscordSyncFresh(request.epoch)

            val enabled = dataStore.get(EnableDiscordRPCKey, true)
            val token = dataStore.get(DiscordTokenKey, "")
            val hasToken = token.isNotBlank()
            val showWhenPaused = dataStore.get(DiscordShowWhenPausedKey, false)
            val (song, isPlaying, playWhenReady, playbackState) =
                withContext(Dispatchers.Main.immediate) {
                    Quadruple(
                        currentPresenceSong(),
                        player.isPlaying,
                        player.playWhenReady,
                        player.playbackState,
                    )
                }

            if (playWhenReady && pausedPresenceGate != PausedPresenceGate.FollowPreference) {
                pausedPresenceGate = PausedPresenceGate.FollowPreference
                Timber.tag(DISCORD_SYNC_TAG).d(
                    "sync epoch=%d reason=%s reset paused gate because playback intent resumed",
                    request.epoch,
                    request.reason,
                )
            }

            val inputs =
                DiscordPresenceInputs(
                    enabled = enabled,
                    hasToken = hasToken,
                    song = song,
                    isPlaying = isPlaying,
                    showWhenPaused = showWhenPaused,
                    pausedPresenceGate = pausedPresenceGate,
                    serviceStopping = discordServiceStopping,
                    playWhenReady = playWhenReady,
                    playbackState = playbackState,
                )
            val holdContext =
                DiscordHoldContext(
                    nowMs = System.currentTimeMillis(),
                    activeHoldState = activeDiscordHoldState,
                    lastAppliedVisiblePresence = lastAppliedVisiblePresence,
                    holdTimeoutMs = DISCORD_HOLD_TIMEOUT_MS,
                )
            val semanticState = derivePlaybackSemanticState(inputs)
            val rawDecision = deriveRawDiscordPresenceDecision(inputs, semanticState)
            val resolution = resolveDiscordPresenceDecision(rawDecision, holdContext)

            val decision = resolution.decision
            ensureDiscordSyncFresh(request.epoch)

            val effectiveForce = request.force || refreshWaiters.isNotEmpty()
            if (!effectiveForce && decision == lastDiscordPresenceDecision) {
                Timber.tag(DISCORD_SYNC_TAG).v(
                    "sync epoch=%d reason=%s unchanged decision=%s",
                    request.epoch,
                    request.reason,
                    decision,
                )
                completeDiscordRefreshWaiters(refreshWaiters, true)
                return
            }

            Timber.tag(DISCORD_SYNC_TAG).d(
                "sync epoch=%d reason=%s force=%s effectiveForce=%s songId=%s playWhenReady=%s playbackState=%d isPlaying=%s semantic=%s raw=%s decision=%s holdState=%s lastAppliedVisible=%s refreshWaiters=%d",
                request.epoch,
                request.reason,
                request.force,
                effectiveForce,
                song?.song?.id,
                playWhenReady,
                playbackState,
                isPlaying,
                semanticState,
                rawDecision,
                decision,
                resolution.nextHoldState,
                lastAppliedVisiblePresence,
                refreshWaiters.size,
            )

            val applied =
                applyDiscordPresenceDecision(
                    request = request,
                    resolution = resolution,
                    token = token,
                    song = song,
                )

            if (applied) {
                lastDiscordPresenceDecision = decision
            }
            if (decision is DiscordPresenceDecision.Hold) {
                requeueDiscordRefreshWaiters(refreshWaiters)
                Timber.tag(DISCORD_SYNC_TAG).d(
                    "refresh waiters requeued because decision is Hold count=%d",
                    refreshWaiters.size,
                )
            } else {
                completeDiscordRefreshWaiters(refreshWaiters, applied)
            }
        } catch (_: StaleDiscordSyncException) {
            requeueDiscordRefreshWaiters(refreshWaiters)
            Timber.tag(DISCORD_SYNC_TAG).d(
                "stale sync aborted epoch=%d reason=%s and refresh waiters requeued=%d",
                request.epoch,
                request.reason,
                refreshWaiters.size,
            )
        } catch (error: CancellationException) {
            completeDiscordRefreshWaiters(refreshWaiters, false)
            throw error
        } catch (error: Exception) {
            Timber.tag(DISCORD_SYNC_TAG).e(error, "syncDiscordStateInternal failed epoch=%d reason=%s", request.epoch, request.reason)
            completeDiscordRefreshWaiters(refreshWaiters, false)
            throw error
        }
    }

    private suspend fun applyDiscordPresenceDecision(
        request: DiscordSyncRequest,
        resolution: DiscordPresenceResolution,
        token: String,
        song: Song?,
    ): Boolean {
        ensureDiscordSyncFresh(request.epoch)

        val decision = resolution.decision
        Timber.tag(DISCORD_SYNC_TAG).d(
            "apply decision epoch=%d decision=%s tokenPresent=%s songId=%s",
            request.epoch,
            decision,
            token.isNotBlank() || !lastPresenceToken.isNullOrBlank(),
            song?.song?.id,
        )
        return when (decision) {
            is DiscordPresenceDecision.Hidden -> {
                clearDiscordHoldState()
                when (decision.reason) {
                    HiddenReason.NoSong,
                    HiddenReason.PausedByPreference,
                    HiddenReason.PausedByNotificationDismiss,
                    HiddenReason.NoStablePlaybackYet,
                    HiddenReason.PlaybackStalled,
                    -> {
                        ensureDiscordSyncFresh(request.epoch)
                        val cleared =
                            DiscordPresenceManager.clearNow(
                                context = this@MusicService,
                                token = token.takeIf { it.isNotBlank() } ?: lastPresenceToken,
                            )
                        if (!cleared) {
                            Timber.tag(DISCORD_SYNC_TAG).d(
                                "clear skipped or failed for hidden reason=%s",
                                decision.reason,
                            )
                        }
                        cleared
                    }

                    HiddenReason.Disabled,
                    HiddenReason.ServiceStopping,
                    -> {
                        val clearToken = token.takeIf { it.isNotBlank() } ?: lastPresenceToken
                        ensureDiscordSyncFresh(request.epoch)
                        val cleared =
                            DiscordPresenceManager.clearNow(
                                context = this@MusicService,
                                token = clearToken,
                            )
                        if (!cleared) {
                            Timber.tag(DISCORD_SYNC_TAG).d(
                                "terminal clear skipped or failed for hidden reason=%s",
                                decision.reason,
                            )
                        }
                        ensureDiscordSyncFresh(request.epoch)
                        DiscordPresenceManager.stop()
                        lastPresenceToken = null
                        true
                    }

                    HiddenReason.NoToken -> {
                        val clearToken = token.takeIf { it.isNotBlank() } ?: lastPresenceToken
                        ensureDiscordSyncFresh(request.epoch)
                        if (clearToken.isNullOrBlank()) {
                            Timber.tag(DISCORD_SYNC_TAG).v(
                                "no token available for terminal clear; stopping manager only",
                            )
                        } else {
                            val cleared =
                                DiscordPresenceManager.clearNow(
                                    context = this@MusicService,
                                    token = clearToken,
                                )
                            if (!cleared) {
                                Timber.tag(DISCORD_SYNC_TAG).d(
                                    "terminal clear skipped or failed for hidden reason=%s",
                                    decision.reason,
                                )
                            }
                        }
                        ensureDiscordSyncFresh(request.epoch)
                        DiscordPresenceManager.stop()
                        lastPresenceToken = null
                        true
                    }
                }
            }

            is DiscordPresenceDecision.Visible -> {
                clearDiscordHoldState()
                ensureDiscordSyncFresh(request.epoch)
                val snapshot = buildDiscordPresenceSnapshot(song, decision.isPaused) ?: return false
                ensureDiscordSyncFresh(request.epoch)
                val updated =
                    DiscordPresenceManager.updateNow(
                        context = this@MusicService,
                        token = token,
                        song = snapshot.song,
                        positionMs = snapshot.positionMs,
                        isPaused = snapshot.isPaused,
                        isMusicVideo = currentMediaMetadata.value?.isMusicVideo ?: false,
                    )
                if (!updated) {
                    Timber.tag(DISCORD_SYNC_TAG).d(
                        "visible update failed songId=%s paused=%s",
                        decision.songId,
                        decision.isPaused,
                    )
                    false
                } else {
                    if (token.isNotBlank()) {
                        lastPresenceToken = token
                    }
                    markLastAppliedVisiblePresence(decision)
                    true
                }
            }

            is DiscordPresenceDecision.Hold -> {
                updateActiveDiscordHoldState(resolution.nextHoldState)
                true
            }
        }
    }

    private suspend fun buildDiscordPresenceSnapshot(
        song: Song?,
        isPaused: Boolean,
    ): DiscordPresenceSnapshot? {
        val resolvedSong = song ?: return null
        val positionMs = withContext(Dispatchers.Main.immediate) { player.currentPosition }
        return DiscordPresenceSnapshot(
            song = resolvedSong,
            positionMs = positionMs,
            isPaused = isPaused,
        )
    }

    private fun cancelRestoredQueueHydration() {
        restoredQueueHydrationGeneration.incrementAndGet()
        restoredQueueBackfillJob?.cancel()
        restoredQueueBackfillJob = null
        isHydratingRestoredQueue = false
    }

    private suspend fun Queue.Status.filterPlaybackContent(
        hideExplicit: Boolean,
        hideVideo: Boolean,
    ): Queue.Status =
        filterExplicit(hideExplicit)
            .filterVideo(hideVideo)
            .filterBlockedArtists(loadBlockedArtistIds())

    private suspend fun List<MediaItem>.filterPlaybackContent(
        hideExplicit: Boolean,
        hideVideo: Boolean,
    ): List<MediaItem> =
        filterExplicit(hideExplicit)
            .filterVideo(hideVideo)
            .filterBlockedArtists(loadBlockedArtistIds())

    private suspend fun loadBlockedArtistIds(): Set<String> =
        withContext(Dispatchers.IO) {
            database.getBlockedArtistIds().toSet()
        }

    private fun removeBlockedArtistItems(updatedBlockedArtistIds: Set<String>) {
        if (updatedBlockedArtistIds.isEmpty() || player.mediaItemCount == 0) return

        removeQueueItems { item -> item.hasBlockedArtist(updatedBlockedArtistIds) }
    }

    private fun removeMusicVideoItems() {
        removeQueueItems { item -> item.metadata?.isMusicVideo == true }
    }

    private inline fun removeQueueItems(shouldRemove: (MediaItem) -> Boolean) {
        if (player.mediaItemCount == 0) return

        var blockedRangeEnd = C.INDEX_UNSET
        for (index in player.mediaItemCount - 1 downTo 0) {
            val item = player.getMediaItemAt(index)
            if (shouldRemove(item)) {
                autoAddedMediaIds.remove(item.mediaId)
                if (blockedRangeEnd == C.INDEX_UNSET) {
                    blockedRangeEnd = index + 1
                }
            } else if (blockedRangeEnd != C.INDEX_UNSET) {
                player.removeMediaItems(index + 1, blockedRangeEnd)
                blockedRangeEnd = C.INDEX_UNSET
            }
        }
        if (blockedRangeEnd != C.INDEX_UNSET) {
            player.removeMediaItems(0, blockedRangeEnd)
        }
        if (player.mediaItemCount == 0) {
            cancelInfiniteQueueBootstrap()
            currentQueue = EmptyQueue
            queueTitle = null
        }
    }

    private suspend fun restorePersistentQueue(persistedQueue: PersistQueue) {
        cancelRestoredQueueHydration()
        val hydrationGeneration = restoredQueueHydrationGeneration.incrementAndGet()
        isHydratingRestoredQueue = true

        val itemQueue = persistedQueue.toQueue()
        val continuationQueue = persistedQueue.toContinuationQueue()
        val hideExplicit = dataStore.get(HideExplicitKey, false)
        val hideVideo = dataStore.get(HideVideoKey, false)
        val initialStatus =
            itemQueue
                .getInitialStatus()
                .filterPlaybackContent(hideExplicit, hideVideo)

        withContext(Dispatchers.Main) {
            currentQueue = continuationQueue
            queueTitle = initialStatus.title

            val items = initialStatus.items
            if (items.isEmpty()) {
                if (hydrationGeneration == restoredQueueHydrationGeneration.get()) {
                    isHydratingRestoredQueue = false
                }
                return@withContext
            }

            val fullIndex = initialStatus.mediaItemIndex.coerceIn(0, items.lastIndex)
            val windowStart = (fullIndex - 20).coerceAtLeast(0)
            val windowEnd = (fullIndex + 50).coerceAtMost(items.size)

            val initialChunk = items.subList(windowStart, windowEnd)
            val relativeIndex = (fullIndex - windowStart).coerceIn(0, initialChunk.lastIndex)

            player.setMediaItems(
                initialChunk,
                relativeIndex,
                initialStatus.position,
            )
            player.prepare()
            player.playWhenReady = false
            currentMediaMetadata.value = player.currentMetadata
            updateNotification()

            if (items.size > initialChunk.size) {
                restoredQueueBackfillJob =
                    scope.launch(SilentHandler) {
                        try {
                            delay(2000)
                            if (!isActive || player.mediaItemCount == 0) return@launch
                            if (windowStart > 0) {
                                player.addMediaItems(0, items.subList(0, windowStart))
                            }
                            if (windowEnd < items.size) {
                                player.addMediaItems(items.subList(windowEnd, items.size))
                            }
                        } finally {
                            if (hydrationGeneration == restoredQueueHydrationGeneration.get()) {
                                isHydratingRestoredQueue = false
                                restoredQueueBackfillJob = null
                                if (isActive && dataStore.get(PersistentQueueKey, true) && player.mediaItemCount > 0) {
                                    saveQueueToDisk()
                                }
                            }
                        }
                    }
            } else {
                if (hydrationGeneration == restoredQueueHydrationGeneration.get()) {
                    isHydratingRestoredQueue = false
                }
            }
        }
    }

    private suspend fun restorePersistentPlayerState(
        playerState: PersistPlayerState,
        restoredQueue: Boolean,
    ) {
        withContext(Dispatchers.Main) {
            player.repeatMode = playerState.repeatMode
            player.shuffleModeEnabled = playerState.shuffleModeEnabled
            playerVolume.value = playerState.volume.coerceIn(0f, 1f)

            if (player.mediaItemCount > 0) {
                val index =
                    when {
                        restoredQueue -> {
                            player.currentMediaItemIndex.coerceIn(0, player.mediaItemCount - 1)
                        }

                        playerState.currentMediaItemIndex in 0 until player.mediaItemCount -> {
                            playerState.currentMediaItemIndex
                        }

                        else -> {
                            player.currentMediaItemIndex.coerceIn(0, player.mediaItemCount - 1)
                        }
                    }
                player.seekTo(index, playerState.currentPosition.coerceAtLeast(0L))
            }

            player.playWhenReady = false
            abandonAudioFocus()

            currentMediaMetadata.value = player.currentMetadata.takeIf { player.mediaItemCount > 0 }
            updateNotification()
        }
    }

    private fun ensurePresenceManager() {
        if (DiscordPresenceManager.isRunning() && lastPresenceToken != null) return

        // Launch in scope to avoid blocking
        scope.launch {
            // Don't start if Discord RPC is disabled in settings
            if (!dataStore.get(EnableDiscordRPCKey, true)) {
                if (DiscordPresenceManager.isRunning()) {
                    Timber.tag("MusicService").d("Discord RPC disabled → stopping presence manager")
                    try {
                        DiscordPresenceManager.stop()
                    } catch (_: Exception) {
                    }
                    lastPresenceToken = null
                }
                return@launch
            }

            val key: String = dataStore.get(DiscordTokenKey, "")
            if (key.isNullOrBlank()) {
                if (DiscordPresenceManager.isRunning()) {
                    Timber.tag("MusicService").d("No Discord OAuth session -> stopping presence manager")
                    try {
                        DiscordPresenceManager.stop()
                    } catch (_: Exception) {
                    }
                    lastPresenceToken = null
                }
                return@launch
            }

            if (DiscordPresenceManager.isRunning() && lastPresenceToken == key) {
                return@launch
            }

            try {
                DiscordPresenceManager.stop()
                DiscordPresenceManager.start(
                    context = this@MusicService,
                    token = key,
                )
                DiscordPresenceManager.setOnTransportInvalidated { reason ->
                    Timber.tag(DISCORD_SYNC_TAG).w(
                        "transport invalidated reason=%s; requesting forced sync",
                        reason,
                    )
                    requestDiscordSync(
                        reason = "transport_invalidated:$reason",
                        force = true,
                    )
                }
                Timber.tag("MusicService").d("Presence manager started")
                lastPresenceToken = key
                requestDiscordSync(
                    reason = "presence_manager_started",
                    force = true,
                )
            } catch (ex: Exception) {
                Timber.tag("MusicService").e(ex, "Failed to start presence manager")
            }
        }
    }

    private fun setupAudioFocusRequest() {
        audioFocusRequest =
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    android.media.AudioAttributes
                        .Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                ).setOnAudioFocusChangeListener { focusChange ->
                    handleAudioFocusChange(focusChange)
                }.setAcceptsDelayedFocusGain(true)
                .build()
    }

    private fun onAudioOutputDeviceChanged() {
        if (!::player.isInitialized) return
        val outputSignature = currentAudioOutputDeviceSignature()
        if (outputSignature == lastAudioOutputDeviceSignature) return
        lastAudioOutputDeviceSignature = outputSignature
        audioOutputResolver.refresh()
        cancelCrossfade(resetVolume = true, resetPauseAtEnd = true)
        player.setAudioAttributes(playbackAudioAttributes(), false)
        audioRouteRecoveryJob?.cancel()
        audioRouteRecoveryJob =
            scope.launch {
                delay(AUDIO_ROUTE_CHANGE_DEBOUNCE_MS)
                recoverAudioRouteAfterDeviceChange()
            }
    }

    private suspend fun recoverAudioRouteAfterDeviceChange() {
        if (!::player.isInitialized) return

        rebindAudioEffectsAfterRouteChange()

        if (!shouldRebuildPlaybackForAudioRouteChange()) return

        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastAudioRouteRecoveryRealtimeMs < AUDIO_ROUTE_RECOVERY_MIN_INTERVAL_MS) return
        lastAudioRouteRecoveryRealtimeMs = now

        val mediaItemIndex = player.currentMediaItemIndex.takeIf { it != C.INDEX_UNSET } ?: return
        val playbackPosition = player.currentPosition.coerceAtLeast(0L)
        val shouldResumePlayback = player.playWhenReady

        Timber.tag("MusicService").i(
            "Recovering audio route after output change at index=$mediaItemIndex position=$playbackPosition resume=$shouldResumePlayback",
        )

        if (shouldResumePlayback && !requestAudioFocus()) {
            wasPlayingBeforeAudioFocusLoss = true
            player.playWhenReady = false
            return
        }

        player.playWhenReady = false
        player.prepare()
        player.seekTo(mediaItemIndex, playbackPosition)
        delay(AUDIO_ROUTE_RECOVERY_RESUME_DELAY_MS)

        if (
            shouldResumePlayback &&
            player.currentMediaItem != null &&
            player.playbackState != Player.STATE_ENDED &&
            requestAudioFocus()
        ) {
            player.playWhenReady = true
        }
    }

    private suspend fun rebindAudioEffectsAfterRouteChange() {
        if (!isAudioEffectSessionOpened) return
        closeAudioEffectSession()
        if (!player.playWhenReady) return
        delay(AUDIO_EFFECT_ROUTE_REBIND_DELAY_MS)
        openAudioEffectSession()
    }

    private fun shouldRebuildPlaybackForAudioRouteChange(): Boolean {
        if (player.currentMediaItem == null) return false
        if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) return false
        return player.playWhenReady || player.playbackState == Player.STATE_BUFFERING
    }

    private fun currentAudioOutputDeviceSignature(): String =
        runCatching {
            audioManager
                .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .asSequence()
                .filter { it.isSink }
                .sortedWith(
                    compareBy<AudioDeviceInfo>(
                        { it.type },
                        { it.id },
                        { it.productName?.toString().orEmpty() },
                    ),
                ).joinToString(separator = "|") { device ->
                    "${device.type}:${device.id}:${device.productName?.toString().orEmpty()}"
                }
        }.getOrDefault("")

    private fun playbackAudioAttributes(): AudioAttributes =
        AudioAttributes
            .Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setAllowedCapturePolicy(C.ALLOW_CAPTURE_BY_ALL)
            .build()

    private fun calculateEffectivePlayerVolume(
        playerVolume: Float,
        normalizeFactor: Float,
        audioFocusVolumeFactor: Float,
    ): Float {
        val safePlayerVolume = playerVolume.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
        val safeNormalizeFactor =
            normalizeFactor.takeIf { it.isFinite() }?.coerceIn(MIN_AUDIO_NORMALIZATION_FACTOR, MAX_AUDIO_NORMALIZATION_FACTOR) ?: 1f
        val safeAudioFocusVolumeFactor =
            audioFocusVolumeFactor.takeIf { it.isFinite() }?.coerceIn(MIN_AUDIO_FOCUS_VOLUME_FACTOR, 1f) ?: 1f
        return (safePlayerVolume * safeNormalizeFactor * safeAudioFocusVolumeFactor).coerceIn(0f, maxSafeGainFactor)
    }

    private fun currentEffectivePlayerVolume(): Float =
        calculateEffectivePlayerVolume(playerVolume.value, normalizeFactor.value, audioFocusVolumeFactor.value)

    private fun currentEffectivePlayerVolumeForMediaId(mediaId: String): Float {
        val targetNormalizeFactor =
            if (audioNormalizationEnabled) {
                audioNormalizationFactorCache[mediaId] ?: 1f
            } else {
                1f
            }
        return calculateEffectivePlayerVolume(playerVolume.value, targetNormalizeFactor, audioFocusVolumeFactor.value)
    }

    private fun updateEffectiveVolume(finalVolume: Float) {
        if (!::player.isInitialized || !shouldRampEffectiveVolume(finalVolume)) {
            applyEffectiveVolumeImmediately(finalVolume)
            return
        }

        val startVolume = player.volume.takeIf { it.isFinite() }?.coerceIn(0f, maxSafeGainFactor) ?: finalVolume
        val targetVolume = finalVolume.coerceIn(0f, maxSafeGainFactor)
        if (abs(targetVolume - startVolume) <= EFFECTIVE_VOLUME_RAMP_MIN_DELTA) {
            applyEffectiveVolumeImmediately(targetVolume)
            return
        }

        effectiveVolumeRampJob?.cancel()
        effectiveVolumeRampJob =
            scope.launch {
                val durationMs =
                    if (targetVolume > startVolume) {
                        EFFECTIVE_VOLUME_RAMP_UP_MS
                    } else {
                        EFFECTIVE_VOLUME_RAMP_DOWN_MS
                    }
                val startedAtMs = android.os.SystemClock.elapsedRealtime()
                while (isActive) {
                    val elapsedMs = android.os.SystemClock.elapsedRealtime() - startedAtMs
                    val progress = (elapsedMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                    val easedProgress = progress * progress * (3f - (2f * progress))
                    val interpolatedVolume = startVolume + ((targetVolume - startVolume) * easedProgress)
                    applyEffectiveVolume(interpolatedVolume)
                    if (progress >= 1f) break
                    delay(EFFECTIVE_VOLUME_RAMP_FRAME_MS)
                }
                applyEffectiveVolume(targetVolume)
                effectiveVolumeRampJob = null
            }
    }

    private fun shouldRampEffectiveVolume(finalVolume: Float): Boolean {
        if (isCrossfading || crossfadeHandoffInProgress) return false
        if (!shouldKeepPlaybackAudible()) return false
        if (!finalVolume.isFinite()) return false
        if (player.volume <= STUCK_MUTED_VOLUME_EPSILON) return false
        return true
    }

    private fun applyEffectiveVolumeImmediately(finalVolume: Float = currentEffectivePlayerVolume()) {
        effectiveVolumeRampJob?.cancel()
        effectiveVolumeRampJob = null
        applyEffectiveVolume(finalVolume)
    }

    private fun applyEffectiveVolume(finalVolume: Float = currentEffectivePlayerVolume()) {
        crossfadeBaseVolume = finalVolume
        val incomingPlayer = secondaryCrossfadePlayer
        if (isCrossfading && incomingPlayer != null) {
            val incomingBaseVolume =
                secondaryCrossfadeTarget?.let { currentEffectivePlayerVolumeForMediaId(it.mediaId) }
                    ?: finalVolume
            crossfadeIncomingBaseVolume = incomingBaseVolume
            applyCrossfadeVolumes(crossfadeProgress, finalVolume, incomingBaseVolume, localPlayer, incomingPlayer)
            return
        }
        if (::player.isInitialized) {
            player.volume = finalVolume
        }
        incomingPlayer?.volume = 0f
    }

    private fun ensureAudiblePlaybackVolume(reason: String) {
        if (!::player.isInitialized) return
        if (isCrossfading || crossfadeHandoffInProgress) return
        if (!shouldKeepPlaybackAudible()) return
        if (playerVolume.value <= 0f) return

        val expectedVolume = currentEffectivePlayerVolume()
        if (expectedVolume <= MIN_AUDIBLE_EFFECTIVE_VOLUME) return
        if (player.volume > STUCK_MUTED_VOLUME_EPSILON) return

        Timber.tag(TAG).w(
            "Restoring muted primary player volume during active playback: reason=%s expected=%s actual=%s",
            reason,
            expectedVolume,
            player.volume,
        )
        applyEffectiveVolumeImmediately(expectedVolume)
    }

    private fun updateAudiblePlaybackRecovery() {
        if (!::player.isInitialized || !shouldKeepPlaybackAudible()) {
            audiblePlaybackRecoveryJob?.cancel()
            audiblePlaybackRecoveryJob = null
            return
        }

        if (audiblePlaybackRecoveryJob?.isActive == true) return
        audiblePlaybackRecoveryJob =
            scope.launch {
                while (isActive && shouldKeepPlaybackAudible()) {
                    ensureAudiblePlaybackVolume("watchdog")
                    delay(AUDIBLE_PLAYBACK_VOLUME_CHECK_MS)
                }
                audiblePlaybackRecoveryJob = null
            }
    }

    private fun applyCrossfadeVolumes(
        progress: Float,
        outgoingBaseVolume: Float,
        incomingBaseVolume: Float,
        outgoingPlayer: ExoPlayer,
        incomingPlayer: ExoPlayer,
    ) {
        val clampedProgress = progress.coerceIn(0f, 1f)
        val radians = clampedProgress.toDouble() * (PI / 2.0)
        outgoingPlayer.volume = (outgoingBaseVolume * cos(radians).toFloat()).coerceIn(0f, maxSafeGainFactor)
        incomingPlayer.volume = (incomingBaseVolume * sin(radians).toFloat()).coerceIn(0f, maxSafeGainFactor)
    }

    fun pauseFromSleepTimer() {
        sleepTimer.clear()
        crossfadeTriggerJob?.cancel()
        crossfadeTriggerJob = null
        cancelCrossfade(resetVolume = true, resetPauseAtEnd = true)
        releaseSecondaryCrossfadePlayer()
        player.pause()
        player.playWhenReady = false
        localPlayer.pause()
        localPlayer.playWhenReady = false
    }

    private fun scheduleCrossfade() {
        if (!::player.isInitialized) return
        crossfadeTriggerJob?.cancel()
        crossfadeTriggerJob = null

        if (isCrossfading) return
        if (!player.playWhenReady || sleepTimer.pauseWhenSongEnd) {
            localPlayer.pauseAtEndOfMediaItems = false
            releaseSecondaryCrossfadePlayer()
            return
        }

        val target = resolveCrossfadeTarget()
        val duration = player.duration
        val effectiveDuration = effectiveCrossfadeDuration(duration)
        if (target == null || effectiveDuration == null) {
            localPlayer.pauseAtEndOfMediaItems = false
            releaseSecondaryCrossfadePlayer()
            return
        }

        val currentMediaId = player.currentMediaItem?.mediaId ?: return
        val currentIndex = player.currentMediaItemIndex
        val triggerAt = duration - effectiveDuration - CROSSFADE_END_GUARD_MS

        crossfadeTriggerJob =
            scope.launch {
                var hasPreparedSecondaryPlayer = false
                while (isActive) {
                    if (!crossfadeEnabled || isCrossfading) return@launch
                    if (player.currentMediaItem?.mediaId != currentMediaId || player.currentMediaItemIndex != currentIndex) {
                        return@launch
                    }
                    if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
                        return@launch
                    }

                    val remainingToTrigger = triggerAt - player.currentPosition
                    if (!hasPreparedSecondaryPlayer && remainingToTrigger <= CROSSFADE_PREPARE_AHEAD_MS) {
                        prepareSecondaryCrossfadePlayer(target)
                        hasPreparedSecondaryPlayer = true
                    }
                    if (remainingToTrigger <= 0L) {
                        val adjustedDuration =
                            (duration - player.currentPosition - CROSSFADE_END_GUARD_MS)
                                .coerceAtMost(effectiveDuration)
                        if (adjustedDuration >= MIN_CROSSFADE_DURATION_MS) {
                            startCrossfade(target, adjustedDuration)
                        }
                        return@launch
                    }

                    val sleepMs =
                        when {
                            remainingToTrigger > 5_000L -> 1_000L
                            remainingToTrigger > 1_000L -> 250L
                            else -> 50L
                        }.coerceAtMost(remainingToTrigger).coerceAtLeast(1L)
                    delay(sleepMs)
                }
            }
    }

    private fun resolveCrossfadeTarget(): CrossfadeTarget? {
        if (!crossfadeEnabled || crossfadeDurationMs <= 0L) return null
        if (player.mediaItemCount == 0 || player.currentTimeline.isEmpty) return null
        if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) return null

        val currentIndex = player.currentMediaItemIndex
        if (currentIndex !in 0 until player.mediaItemCount) return null

        val repeatCurrent = player.repeatMode == REPEAT_MODE_ONE
        val targetIndex = if (repeatCurrent) currentIndex else player.nextMediaItemIndex
        if (targetIndex == C.INDEX_UNSET || targetIndex !in 0 until player.mediaItemCount) return null
        if (!repeatCurrent && targetIndex == currentIndex) return null

        val currentItem = player.getMediaItemAt(currentIndex)
        val targetItem = player.getMediaItemAt(targetIndex)
        if (!repeatCurrent && crossfadeGapless && isGaplessAlbumTransition(currentItem, targetItem)) return null

        return CrossfadeTarget(
            index = targetIndex,
            mediaId = targetItem.mediaId,
        )
    }

    private fun effectiveCrossfadeDuration(duration: Long): Long? {
        if (duration == C.TIME_UNSET || duration <= 0L) return null
        val maxDuration = duration - CROSSFADE_END_GUARD_MS
        if (maxDuration < MIN_CROSSFADE_DURATION_MS) return null
        return crossfadeDurationMs
            .coerceAtLeast(MIN_CROSSFADE_DURATION_MS)
            .coerceAtMost(maxDuration)
    }

    private fun isGaplessAlbumTransition(
        currentItem: MediaItem,
        targetItem: MediaItem,
    ): Boolean {
        val currentAlbum =
            currentItem.metadata
                ?.album
                ?.id
                ?.takeIf { it.isNotBlank() }
                ?: currentItem.metadata
                    ?.album
                    ?.title
                    ?.takeIf { it.isNotBlank() }
                ?: currentItem.mediaMetadata.albumTitle
                    ?.toString()
                    ?.takeIf { it.isNotBlank() }
        val targetAlbum =
            targetItem.metadata
                ?.album
                ?.id
                ?.takeIf { it.isNotBlank() }
                ?: targetItem.metadata
                    ?.album
                    ?.title
                    ?.takeIf { it.isNotBlank() }
                ?: targetItem.mediaMetadata.albumTitle
                    ?.toString()
                    ?.takeIf { it.isNotBlank() }
        return currentAlbum != null && currentAlbum == targetAlbum
    }

    private fun prepareSecondaryCrossfadePlayer(target: CrossfadeTarget): ExoPlayer? {
        val existingPlayer = secondaryCrossfadePlayer
        if (existingPlayer != null && secondaryCrossfadeTarget == target) {
            return existingPlayer
        }

        releaseSecondaryCrossfadePlayer()

        val targetItem =
            runCatching { player.getMediaItemAt(target.index) }
                .getOrNull()
                ?.takeIf { it.mediaId == target.mediaId }
                ?: return null

        return runCatching {
            createSecondaryCrossfadePlayer().also { secondaryPlayer ->
                secondaryCrossfadePlayer = secondaryPlayer
                secondaryCrossfadeTarget = target
                secondaryPlayer.setMediaItem(targetItem)
                secondaryPlayer.playbackParameters = player.playbackParameters
                secondaryPlayer.volume = 0f
                secondaryPlayer.prepare()
            }
        }.onFailure { error ->
            Timber.tag(TAG).w(error, "Failed to prepare crossfade player")
            releaseSecondaryCrossfadePlayer()
        }.getOrNull()
    }

    private fun createSecondaryCrossfadePlayer(): ExoPlayer =
        ExoPlayer
            .Builder(this)
            .setMediaSourceFactory(createMediaSourceFactory())
            .setRenderersFactory(createRenderersFactory())
            .setLoadControl(createCrossfadeLoadControl())
            .setTrackSelector(DefaultTrackSelector(this, SafeTrackSelectionFactory()))
            .setHandleAudioBecomingNoisy(false)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setAudioAttributes(playbackAudioAttributes(), false)
            .setSeekBackIncrementMs(5000)
            .setSeekForwardIncrementMs(5000)
            .build()
            .apply {
                addListener(secondaryCrossfadeListener)
                setOffloadEnabled(false)
                skipSilenceEnabled = localPlayer.skipSilenceEnabled
            }

    private fun startCrossfade(
        target: CrossfadeTarget,
        durationMs: Long,
    ) {
        if (isCrossfading || !crossfadeEnabled) return

        val incomingPlayer = prepareSecondaryCrossfadePlayer(target) ?: return
        val outgoingMediaId = player.currentMediaItem?.mediaId ?: return

        crossfadeTriggerJob?.cancel()
        crossfadeTriggerJob = null
        crossfadeJob?.cancel()
        crossfadeJob =
            scope.launch {
                isCrossfading = true
                crossfadeProgress = 0f
                crossfadeBaseVolume = currentEffectivePlayerVolume()
                crossfadeIncomingBaseVolume = currentEffectivePlayerVolumeForMediaId(target.mediaId)
                crossfadePlaybackRequested = player.playWhenReady
                localPlayer.pauseAtEndOfMediaItems = true

                try {
                    val requiredBufferedMs = requiredCrossfadeStartBufferMs(durationMs)
                    if (!awaitCrossfadePlayerReady(incomingPlayer, CROSSFADE_READY_TIMEOUT_MS, requiredBufferedMs)) {
                        cancelCrossfade(resetVolume = true, resetPauseAtEnd = true)
                        scheduleCrossfade()
                        return@launch
                    }

                    incomingPlayer.playbackParameters = player.playbackParameters
                    incomingPlayer.playWhenReady = crossfadePlaybackRequested
                    if (crossfadePlaybackRequested) {
                        incomingPlayer.play()
                    }

                    var elapsedMs = 0L
                    var lastTickMs = android.os.SystemClock.elapsedRealtime()
                    while (isActive && elapsedMs < durationMs) {
                        if (player.currentMediaItem?.mediaId != outgoingMediaId) {
                            cancelCrossfade(resetVolume = true, resetPauseAtEnd = true)
                            return@launch
                        }

                        val nowMs = android.os.SystemClock.elapsedRealtime()
                        if (crossfadePlaybackRequested) {
                            incomingPlayer.playWhenReady = true
                            elapsedMs = (elapsedMs + (nowMs - lastTickMs)).coerceAtMost(durationMs)
                            crossfadeProgress = (elapsedMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                            applyCrossfadeVolumes(
                                crossfadeProgress,
                                crossfadeBaseVolume,
                                crossfadeIncomingBaseVolume,
                                localPlayer,
                                incomingPlayer,
                            )
                        } else {
                            incomingPlayer.pause()
                        }
                        lastTickMs = nowMs
                        delay(CROSSFADE_FRAME_MS)
                    }

                    finishCrossfade(target, incomingPlayer)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Timber.tag(TAG).w(error, "Crossfade failed")
                    cancelCrossfade(resetVolume = true, resetPauseAtEnd = true)
                }
            }
    }

    private suspend fun awaitCrossfadePlayerReady(
        crossfadePlayer: ExoPlayer,
        timeoutMs: Long,
        minimumBufferedMs: Long,
    ): Boolean {
        val deadlineMs = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (kotlinx.coroutines.currentCoroutineContext().isActive && android.os.SystemClock.elapsedRealtime() < deadlineMs) {
            when (crossfadePlayer.playbackState) {
                Player.STATE_READY -> {
                    if (hasBufferedForSmoothStart(crossfadePlayer, minimumBufferedMs)) {
                        return true
                    }
                }

                Player.STATE_IDLE -> {
                    crossfadePlayer.prepare()
                }

                Player.STATE_ENDED -> {
                    return false
                }
            }
            delay(50L)
        }
        return crossfadePlayer.playbackState == Player.STATE_READY &&
            hasBufferedForSmoothStart(crossfadePlayer, minimumBufferedMs)
    }

    private suspend fun finishCrossfade(
        target: CrossfadeTarget,
        incomingPlayer: ExoPlayer,
    ) {
        val targetIndex = resolveCrossfadeTargetIndex(target)
        if (targetIndex == C.INDEX_UNSET) {
            cancelCrossfade(resetVolume = true, resetPauseAtEnd = true)
            return
        }

        val incomingPosition = incomingPlayer.currentPosition.coerceAtLeast(0L)
        val shouldContinuePlayback = crossfadePlaybackRequested

        var handoffCompleted = false
        try {
            localPlayer.pauseAtEndOfMediaItems = false
            player.volume = 0f
            crossfadeHandoffInProgress = true
            player.seekTo(targetIndex, incomingPosition)
            player.playWhenReady = shouldContinuePlayback
            if (shouldContinuePlayback) {
                if (awaitPrimaryCrossfadeHandoffReady(incomingPlayer)) {
                    val syncedIncomingPosition = incomingPlayer.currentPosition.coerceAtLeast(0L)
                    player.seekTo(targetIndex, syncedIncomingPosition)
                }
            }
            currentMediaMetadata.value = player.getMediaItemAt(targetIndex).metadata
            handoffCompleted = true
        } finally {
            if (!handoffCompleted) {
                crossfadeHandoffInProgress = false
                isCrossfading = false
                crossfadeProgress = 0f
                crossfadePlaybackRequested = false
                releaseSecondaryCrossfadePlayer()
                applyEffectiveVolumeImmediately()
            }
        }

        isCrossfading = false
        crossfadeHandoffInProgress = false
        crossfadeProgress = 0f
        crossfadeIncomingBaseVolume = 1f
        crossfadePlaybackRequested = false
        releaseSecondaryCrossfadePlayer()
        applyEffectiveVolumeImmediately()
        updateAudiblePlaybackRecovery()
        scheduleCrossfade()
    }

    private suspend fun awaitPrimaryCrossfadeHandoffReady(incomingPlayer: ExoPlayer): Boolean {
        val deadlineMs = android.os.SystemClock.elapsedRealtime() + CROSSFADE_HANDOFF_READY_TIMEOUT_MS
        while (kotlinx.coroutines.currentCoroutineContext().isActive && android.os.SystemClock.elapsedRealtime() < deadlineMs) {
            if (player.playbackState == Player.STATE_READY && canHandoffWithoutRebuffer(incomingPlayer)) {
                return true
            }
            if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
                return false
            }
            delay(25L)
        }
        return player.playbackState == Player.STATE_READY && canHandoffWithoutRebuffer(incomingPlayer)
    }

    private fun canHandoffWithoutRebuffer(incomingPlayer: ExoPlayer): Boolean {
        if (player.currentMediaItem
                ?.localConfiguration
                ?.uri
                ?.shouldBypassPlayerCache() == true
        ) {
            return true
        }
        if (hasBufferedForSmoothStart(localPlayer, CROSSFADE_HANDOFF_BUFFER_MS)) {
            val bufferedPosition = localPlayer.bufferedPosition
            val incomingPosition = incomingPlayer.currentPosition.coerceAtLeast(0L)
            return bufferedPosition == C.TIME_UNSET ||
                incomingPosition + CROSSFADE_HANDOFF_SEEK_GUARD_MS <= bufferedPosition
        }
        return false
    }

    private fun requiredCrossfadeStartBufferMs(durationMs: Long): Long =
        (durationMs + CROSSFADE_HANDOFF_BUFFER_MS)
            .coerceAtLeast(CROSSFADE_MIN_BUFFER_BEFORE_START_MS)
            .coerceAtMost(CROSSFADE_MAX_BUFFER_BEFORE_START_MS)

    private fun hasBufferedForSmoothStart(
        targetPlayer: ExoPlayer,
        minimumBufferedMs: Long,
    ): Boolean {
        if (minimumBufferedMs <= 0L) return true
        if (targetPlayer.currentMediaItem
                ?.localConfiguration
                ?.uri
                ?.shouldBypassPlayerCache() == true
        ) {
            return true
        }

        val duration = targetPlayer.duration
        val currentPosition = targetPlayer.currentPosition.coerceAtLeast(0L)
        val remainingDuration =
            if (duration != C.TIME_UNSET && duration > currentPosition) {
                duration - currentPosition
            } else {
                Long.MAX_VALUE
            }
        val requiredBufferedMs = minimumBufferedMs.coerceAtMost(remainingDuration)
        if (requiredBufferedMs <= 0L) return true

        val bufferedDuration = targetPlayer.totalBufferedDuration.coerceAtLeast(0L)
        if (bufferedDuration >= requiredBufferedMs) return true

        return duration != C.TIME_UNSET &&
            targetPlayer.bufferedPosition >= duration - CROSSFADE_END_GUARD_MS
    }

    private fun resolveCrossfadeTargetIndex(target: CrossfadeTarget): Int {
        if (target.index in 0 until player.mediaItemCount &&
            player.getMediaItemAt(target.index).mediaId == target.mediaId
        ) {
            return target.index
        }

        for (index in 0 until player.mediaItemCount) {
            if (player.getMediaItemAt(index).mediaId == target.mediaId) {
                return index
            }
        }
        return C.INDEX_UNSET
    }

    private fun cancelCrossfade(
        resetVolume: Boolean,
        resetPauseAtEnd: Boolean,
    ) {
        crossfadeTriggerJob?.cancel()
        crossfadeTriggerJob = null
        crossfadeJob?.cancel()
        crossfadeJob = null
        isCrossfading = false
        crossfadeHandoffInProgress = false
        crossfadeProgress = 0f
        crossfadeIncomingBaseVolume = 1f
        crossfadePlaybackRequested = false
        if (::player.isInitialized && resetPauseAtEnd) {
            localPlayer.pauseAtEndOfMediaItems = false
        }
        releaseSecondaryCrossfadePlayer()
        if (resetVolume && ::player.isInitialized) {
            applyEffectiveVolumeImmediately()
        }
    }

    private fun releaseSecondaryCrossfadePlayer() {
        val playerToRelease = secondaryCrossfadePlayer ?: return
        secondaryCrossfadePlayer = null
        secondaryCrossfadeTarget = null
        runCatching { playerToRelease.removeListener(secondaryCrossfadeListener) }
        runCatching { playerToRelease.stop() }
        runCatching { playerToRelease.clearMediaItems() }
        runCatching { playerToRelease.release() }
    }

    private fun calculateAudioNormalizationFactor(
        format: FormatEntity?,
        normalizeAudio: Boolean,
    ): Float {
        Timber.tag("AudioNormalization").d("Audio normalization enabled: $normalizeAudio")
        Timber
            .tag(
                "AudioNormalization",
            ).d("Format loudnessDb: ${format?.loudnessDb}, perceptualLoudnessDb: ${format?.perceptualLoudnessDb}")

        if (!normalizeAudio) {
            Timber.tag("AudioNormalization").d("Normalization disabled - using factor 1.0")
            return 1f
        }

        val loudnessDb = format?.normalizationLoudnessDb()
        if (loudnessDb == null || !loudnessDb.isFinite()) {
            Timber.tag("AudioNormalization").w("Normalization enabled but no valid loudness data available - no normalization applied")
            return 1f
        }

        val rawFactor = 10f.pow(-loudnessDb / 20)
        val factor =
            if (rawFactor.isFinite()) {
                rawFactor.coerceIn(MIN_AUDIO_NORMALIZATION_FACTOR, MAX_AUDIO_NORMALIZATION_FACTOR)
            } else {
                1f
            }

        if (factor != rawFactor) {
            Timber.tag("AudioNormalization").d("Normalization factor clamped from $rawFactor to $factor")
        }
        Timber.tag("AudioNormalization").i("Applying normalization factor: $factor")
        return factor
    }

    private fun resolveAudioNormalizationFactor(
        mediaId: String?,
        format: FormatEntity?,
        normalizeAudio: Boolean,
    ): Float {
        val currentMediaId = mediaId?.takeIf { it.isNotBlank() } ?: return 1f
        if (!normalizeAudio) {
            return 1f
        }

        if (format?.id == currentMediaId) {
            val factor = calculateAudioNormalizationFactor(format, normalizeAudio = true)
            audioNormalizationFactorCache[currentMediaId] = factor
            return factor
        }

        return audioNormalizationFactorCache[currentMediaId] ?: 1f
    }

    private fun FormatEntity.normalizationLoudnessDb(): Float? =
        sequenceOf(perceptualLoudnessDb, loudnessDb)
            .mapNotNull { it?.toFloat() }
            .firstOrNull { it.isFinite() }

    private fun shouldKeepPlaybackAudible(): Boolean {
        if (!::player.isInitialized) return false
        if (player.currentMediaItem == null || !player.playWhenReady) return false
        return player.playbackState != Player.STATE_IDLE && player.playbackState != Player.STATE_ENDED
    }

    private fun restoreAudioFocusVolume() {
        audioFocusVolumeFactor.value = 1f
        hasAudioFocus = true
        lastAudioFocusState = AudioManager.AUDIOFOCUS_GAIN
    }

    private fun pauseForAudioFocusLoss(resumeWhenFocusReturns: Boolean) {
        audioFocusVolumeFactor.value = 1f
        wasPlayingBeforeAudioFocusLoss = resumeWhenFocusReturns && player.playWhenReady
        if (player.playWhenReady) {
            player.pause()
        }
    }

    private fun ensureAudioFocusForActivePlayback(): Boolean {
        if (!player.playWhenReady) return true
        if (requestAudioFocus()) return true
        pauseForAudioFocusLoss(resumeWhenFocusReturns = true)
        return false
    }

    private fun handleAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                audioFocusVolumeFactor.value = 1f

                if (wasPlayingBeforeAudioFocusLoss) {
                    player.play()
                    wasPlayingBeforeAudioFocusLoss = false
                }

                lastAudioFocusState = focusChange
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                pauseForAudioFocusLoss(resumeWhenFocusReturns = false)

                abandonAudioFocus()

                lastAudioFocusState = focusChange
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasAudioFocus = false
                pauseForAudioFocusLoss(resumeWhenFocusReturns = true)

                lastAudioFocusState = focusChange
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                hasAudioFocus = false
                pauseForAudioFocusLoss(resumeWhenFocusReturns = true)

                lastAudioFocusState = focusChange
            }

            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT -> {
                hasAudioFocus = true
                audioFocusVolumeFactor.value = 1f

                if (wasPlayingBeforeAudioFocusLoss) {
                    player.play()
                    wasPlayingBeforeAudioFocusLoss = false
                }

                lastAudioFocusState = focusChange
            }

            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> {
                hasAudioFocus = true
                audioFocusVolumeFactor.value = 1f

                lastAudioFocusState = focusChange
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) {
            if (audioFocusVolumeFactor.value != 1f || lastAudioFocusState == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                restoreAudioFocusVolume()
            }
            return true
        }

        audioFocusRequest?.let { request ->
            val result = audioManager.requestAudioFocus(request)
            hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (hasAudioFocus) {
                restoreAudioFocusVolume()
            }
            return hasAudioFocus
        }
        return false
    }

    private fun abandonAudioFocus() {
        if (hasAudioFocus) {
            audioFocusRequest?.let { request ->
                audioManager.abandonAudioFocusRequest(request)
                hasAudioFocus = false
            }
        }
    }

    fun hasAudioFocusForPlayback(): Boolean = hasAudioFocus

    private fun isDeviceMutedNow(): Boolean {
        val streamVolume =
            runCatching {
                audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            }.getOrElse { error ->
                reportException(error)
                return player.isDeviceMuted || player.deviceVolume <= 0
            }
        val isStreamMuted =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                runCatching {
                    audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
                }.getOrElse { error ->
                    reportException(error)
                    false
                }

        return isStreamMuted || streamVolume <= 0
    }

    private fun isTogetherGuestSession(): Boolean {
        val joined = togetherSessionState.value as? moe.rukamori.archivetune.together.TogetherSessionState.Joined
        return joined?.role is moe.rukamori.archivetune.together.TogetherRole.Guest
    }

    private fun registerMuteRecoveryObserver() {
        if (muteRecoveryObserver != null) return
        val observer =
            object : ContentObserver(Handler(mainLooper)) {
                override fun onChange(selfChange: Boolean) {
                    if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) > 0) {
                        handleDeviceMuteStateChanged()
                    }
                }
            }
        contentResolver.registerContentObserver(
            android.provider.Settings.System.CONTENT_URI,
            true,
            observer,
        )
        muteRecoveryObserver = observer
    }

    private fun unregisterMuteRecoveryObserver() {
        muteRecoveryObserver?.let { contentResolver.unregisterContentObserver(it) }
        muteRecoveryObserver = null
    }

    private fun handleDeviceMuteStateChanged(playbackRequestedWhileMuted: Boolean = false) {
        if (!pauseOnDeviceMuteEnabled || isTogetherGuestSession()) {
            wasAutoPausedByDeviceMute = false
            unregisterMuteRecoveryObserver()
            return
        }

        if (isDeviceMutedNow()) {
            if (playbackRequestedWhileMuted && restoreDeviceMusicVolumeForPlayback()) {
                wasAutoPausedByDeviceMute = false
                unregisterMuteRecoveryObserver()
                return
            }

            val canPauseNow =
                player.currentMediaItem != null &&
                    player.playWhenReady &&
                    player.playbackState != Player.STATE_IDLE &&
                    player.playbackState != Player.STATE_ENDED

            if (canPauseNow) {
                player.pause()
                wasAutoPausedByDeviceMute = true
                registerMuteRecoveryObserver()
                if (playbackRequestedWhileMuted) {
                    showDeviceMutePlaybackNotice()
                }
            }
            return
        }

        unregisterMuteRecoveryObserver()

        if (!wasAutoPausedByDeviceMute) return

        wasAutoPausedByDeviceMute = false
        val canResumeNow =
            player.currentMediaItem != null &&
                player.playbackState != Player.STATE_IDLE &&
                player.playbackState != Player.STATE_ENDED
        if (canResumeNow) {
            player.play()
        }
    }

    private fun restoreDeviceMusicVolumeForPlayback(): Boolean {
        val recoveryPercent = deviceMutePlaybackRecoveryVolumePercent.coerceIn(0, 100)
        if (recoveryPercent <= 0) return false

        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (maxVolume <= 0) return false

        val targetVolume =
            ceil(maxVolume * (recoveryPercent / 100.0))
                .toInt()
                .coerceIn(1, maxVolume)

        return runCatching {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) > 0
        }.getOrElse {
            reportException(it)
            false
        }
    }

    private fun showDeviceMutePlaybackNotice() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastDeviceMutePlaybackNoticeAtElapsedMs < DEVICE_MUTE_PLAYBACK_NOTICE_INTERVAL_MS) return
        lastDeviceMutePlaybackNoticeAtElapsedMs = now
        scope.launch(SilentHandler) {
            Toast
                .makeText(
                    this@MusicService,
                    R.string.device_volume_zero_playback_paused,
                    Toast.LENGTH_SHORT,
                ).show()
        }
    }

    private val bluetoothReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
                if (!autoStartOnBluetoothEnabled) return

                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return

                val isAudioDevice =
                    try {
                        val majorClass = device.bluetoothClass?.majorDeviceClass
                        majorClass == BluetoothClass.Device.Major.AUDIO_VIDEO ||
                            majorClass == BluetoothClass.Device.Major.WEARABLE
                    } catch (_: SecurityException) {
                        true
                    }

                if (!isAudioDevice) return

                scope.launch {
                    delay(1500)
                    handleBluetoothAutoStart()
                }
            }
        }

    private fun handleBluetoothAutoStart() {
        if (isTogetherGuestSession()) return

        if (player.currentMediaItem != null &&
            player.playbackState != Player.STATE_IDLE &&
            player.playbackState != Player.STATE_ENDED
        ) {
            if (!player.playWhenReady) {
                player.play()
            }
            return
        }

        if (player.mediaItemCount > 0) {
            player.prepare()
            player.play()
        }
    }

    @Suppress("DEPRECATION")
    private fun registerBluetoothReceiver() {
        if (bluetoothReceiverRegistered) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val filter = IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(bluetoothReceiver, filter)
        }
        bluetoothReceiverRegistered = true
    }

    private fun unregisterBluetoothReceiver() {
        if (!bluetoothReceiverRegistered) return
        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (_: Exception) {
        }
        bluetoothReceiverRegistered = false
    }

    private fun waitOnNetworkError() {
        waitingForNetworkConnection.value = true
    }

    private fun skipOnError() {
        /**
         * Auto skip to the next media item on error.
         *
         * To prevent a "runaway diesel engine" scenario, force the user to take action after
         * too many errors come up too quickly. Pause to show player "stopped" state
         */
        consecutivePlaybackErr += 2
        val nextWindowIndex = player.nextMediaItemIndex

        if (consecutivePlaybackErr <= MAX_CONSECUTIVE_ERR && nextWindowIndex != C.INDEX_UNSET) {
            player.seekTo(nextWindowIndex, C.TIME_UNSET)
            player.prepare()
            player.play()
            return
        }

        player.pause()
        consecutivePlaybackErr = 0
    }

    private fun stopOnError() {
        player.pause()
    }

    private fun findStreamHttpFailure(
        error: PlaybackException,
    ): androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException? {
        var throwable: Throwable? = error.cause
        while (throwable != null) {
            if (throwable is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                return throwable
            }
            throwable = throwable.cause
        }
        return null
    }

    private fun isRetryableRemoteParserFailure(error: PlaybackException): Boolean {
        if (
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
        ) {
            return true
        }

        var throwable: Throwable? = error.cause
        while (throwable != null) {
            if (throwable.message?.contains("Skipping atom with length", ignoreCase = true) == true) {
                return true
            }
            throwable = throwable.cause
        }
        return false
    }

    private fun isCacheCorruptionError(
        error: PlaybackException,
        isContentCached: Boolean,
    ): Boolean {
        val isIoError =
            error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
        val isContainerParseError =
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED

        if (!isIoError && !isContainerParseError) {
            return false
        }

        var throwable: Throwable? = error.cause
        while (throwable != null) {
            when {
                throwable is EOFException -> {
                    return true
                }

                throwable is IOException &&
                    throwable.message?.contains("unexpected end of stream", ignoreCase = true) == true -> {
                    return true
                }

                throwable is IllegalStateException || throwable is IllegalArgumentException -> {
                    if (throwable.stackTrace.any { it.className.startsWith("androidx.media3.extractor") }) {
                        return true
                    }
                }

                isContainerParseError && isContentCached && throwable is ParserException -> {
                    return true
                }

                isContainerParseError && isContentCached &&
                    throwable.message?.let {
                        it.contains("Invalid integer size", ignoreCase = true) ||
                            it.contains("Skipping atom with length", ignoreCase = true) ||
                            it.contains("contentIsMalformed=true", ignoreCase = true)
                    } == true -> {
                    return true
                }
            }
            throwable = throwable.cause
        }
        return false
    }

    private fun retryPlaybackAfterStreamFailure(
        mediaId: String,
        isFullyDownloadedMedia: Boolean,
        responseException: androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException,
    ): Boolean {
        if (isFullyDownloadedMedia) return false

        val failedUrl = responseException.dataSpec.uri.toString()
        val requestProfile = StreamClientUtils.resolveRequestProfile(failedUrl)
        val authFingerprint = YouTube.currentPlaybackAuthState().fingerprint
        val extractorAuthFingerprint = ArchiveTuneExtractorCacheFingerprintPrefix + authFingerprint
        val cachedFailedUrl = playbackUrlCache[mediaId]?.takeIf { it.url == failedUrl }
        val cachedExtractorFailedUrl = extractorPlaybackUrlCache[mediaId]?.takeIf { it.url == failedUrl }
        val failedExpiredUrl =
            YTPlayerUtils.isExpiredOrNearExpiredStreamUrl(failedUrl) ||
                (
                    cachedFailedUrl?.let {
                        !it.isValidFor(
                            authFingerprint = authFingerprint,
                            minimumRemainingMs = YTPlayerUtils.STREAM_URL_EXPIRY_SAFETY_MS,
                        )
                    } == true
                ) ||
                (
                    cachedExtractorFailedUrl?.let {
                        !it.isValidFor(
                            authFingerprint = extractorAuthFingerprint,
                            minimumRemainingMs = 0L,
                        )
                    } == true
                )

        playbackUrlCache.remove(mediaId)
        extractorPlaybackUrlCache.remove(mediaId)
        YTPlayerUtils.invalidateCachedStreamUrls(mediaId)
        if (!failedExpiredUrl && cachedExtractorFailedUrl == null && requestProfile.clientKey.isNotEmpty()) {
            YTPlayerUtils.markStreamClientFailed(mediaId, requestProfile.clientKey, responseException.responseCode)
        }

        if (!playbackStreamRecoveryTracker.registerRetryAttempt(mediaId)) {
            return false
        }

        Timber.tag("MusicService").i(
            "Retrying playback for %s after stream HTTP %d from %s failed",
            mediaId,
            responseException.responseCode,
            requestProfile.variantLabel,
        )
        player.prepare()
        return true
    }

    private fun handleExtractorStreamHttpFailure(
        mediaId: String,
        isFullyDownloadedMedia: Boolean,
        responseException: HttpDataSource.InvalidResponseCodeException,
    ): Boolean {
        if (isFullyDownloadedMedia || !isExtractorPlaybackUri(responseException.dataSpec.uri)) return false

        return when (responseException.responseCode) {
            401 -> {
                Timber.tag(TAG).w("Extractor bearer token was rejected during playback")
                notifyExtractorAuthenticationRequired()
                stopOnError()
                true
            }

            403 -> {
                Timber.tag(TAG).w("Extractor rejected a tampered or invalid signed playback URL")
                stopOnError()
                true
            }

            410 -> {
                val retryStarted = retryPlaybackAfterStreamFailure(
                    mediaId = mediaId,
                    isFullyDownloadedMedia = false,
                    responseException = responseException,
                )
                if (!retryStarted) stopOnError()
                true
            }

            else -> false
        }
    }

    private fun notifyExtractorAuthenticationRequired() {
        extractorTokenRepository.clearToken()
        extractorPlaybackUrlCache.clear()
        _extractorAuthenticationEvents.tryEmit(Unit)
    }

    fun updateExtractorBearerToken(token: String) {
        extractorTokenRepository.updateToken(token)
        extractorPlaybackUrlCache.clear()
    }

    private fun updateNotification() {
        try {
            val customLayout =
                listOf(
                    CommandButton
                        .Builder()
                        .setDisplayName(
                            getString(
                                if (currentSong.value?.song?.liked == true) {
                                    R.string.action_remove_like
                                } else {
                                    R.string.action_like
                                },
                            ),
                        ).setIconResId(if (currentSong.value?.song?.liked == true) R.drawable.favorite else R.drawable.favorite_border)
                        .setSessionCommand(CommandToggleLike)
                        .setEnabled(currentSong.value != null)
                        .build(),
                    CommandButton
                        .Builder()
                        .setDisplayName(
                            getString(
                                when (player.repeatMode) {
                                    REPEAT_MODE_OFF -> R.string.repeat_mode_off
                                    REPEAT_MODE_ONE -> R.string.repeat_mode_one
                                    REPEAT_MODE_ALL -> R.string.repeat_mode_all
                                    else -> R.string.repeat_mode_off
                                },
                            ),
                        ).setIconResId(
                            when (player.repeatMode) {
                                REPEAT_MODE_OFF -> R.drawable.repeat
                                REPEAT_MODE_ONE -> R.drawable.repeat_one_on
                                REPEAT_MODE_ALL -> R.drawable.repeat_on
                                else -> R.drawable.repeat
                            },
                        ).setSessionCommand(CommandToggleRepeatMode)
                        .build(),
                    CommandButton
                        .Builder()
                        .setDisplayName(
                            getString(if (player.shuffleModeEnabled) R.string.action_shuffle_off else R.string.action_shuffle_on),
                        ).setIconResId(if (player.shuffleModeEnabled) R.drawable.shuffle_on else R.drawable.shuffle)
                        .setSessionCommand(CommandToggleShuffle)
                        .build(),
                    CommandButton
                        .Builder()
                        .setDisplayName(getString(R.string.start_radio))
                        .setIconResId(R.drawable.radio)
                        .setSessionCommand(CommandToggleStartRadio)
                        .setEnabled(currentSong.value != null)
                        .build(),
                )
            mediaSession.setCustomLayout(customLayout)
        } catch (e: Exception) {
            reportException(e)
        }
    }

    fun refreshPlaybackNotification() {
        updateNotification()
        onUpdateNotification(mediaSession, hasResumablePlaybackNotification())
    }

    private suspend fun recoverSong(
        mediaId: String,
        playbackData: YTPlayerUtils.PlaybackData? = null,
    ) {
        val song = database.song(mediaId).first()
        val mediaMetadata =
            withContext(Dispatchers.Main) {
                player.findNextMediaItemById(mediaId)?.metadata
            } ?: return
        val duration =
            song?.song?.duration?.takeIf { it != -1 }
                ?: mediaMetadata.duration.takeIf { it != -1 }
                ?: (
                    playbackData?.videoDetails ?: YTPlayerUtils
                        .playerResponseForMetadata(mediaId)
                        .getOrNull()
                        ?.videoDetails
                )?.lengthSeconds?.toInt()
                ?: -1
        database.query {
            if (song == null) {
                insert(mediaMetadata.copy(duration = duration))
            } else if (song.song.duration == -1) {
                update(song.song.copy(duration = duration))
            }
        }
        if (!database.hasRelatedSongs(mediaId)) {
            val relatedEndpoint =
                YouTube.next(WatchEndpoint(videoId = mediaId)).getOrNull()?.relatedEndpoint
                    ?: return
            val relatedPage = YouTube.related(relatedEndpoint).getOrNull() ?: return
            database.query {
                relatedPage.songs
                    .map(SongItem::toMediaMetadata)
                    .onEach(::insert)
                    .map {
                        RelatedSongMap(
                            songId = mediaId,
                            relatedSongId = it.id,
                        )
                    }.forEach(::insert)
            }
        }
    }

    fun playQueue(
        queue: Queue,
        playWhenReady: Boolean = true,
    ) {
        val joined = togetherSessionState.value as? moe.rukamori.archivetune.together.TogetherSessionState.Joined
        if (!isTogetherApplyingRemote() && joined?.role is moe.rukamori.archivetune.together.TogetherRole.Guest) {
            if (!joined.roomState.settings.allowGuestsToControlPlayback) {
                showTogetherNotice(getString(R.string.not_allowed), key = "GUEST_PLAYQUEUE_DISABLED")
                return
            }
            ensureScopesActive()
            scope.launch(SilentHandler) {
                val initialStatus =
                    withContext(Dispatchers.IO) {
                        queue
                            .getInitialStatus()
                            .filterPlaybackContent(
                                hideExplicit = dataStore.get(HideExplicitKey, false),
                                hideVideo = dataStore.get(HideVideoKey, false),
                            )
                    }

                val targetItem =
                    initialStatus.items.getOrNull(initialStatus.mediaItemIndex)
                        ?: queue.preloadItem
                            ?.toMediaItem()
                            ?.takeUnless { item ->
                                item.hasBlockedArtist(loadBlockedArtistIds())
                            }

                val meta = targetItem?.metadata
                val trackId =
                    meta?.id?.trim().orEmpty().ifBlank {
                        targetItem?.mediaId?.trim().orEmpty()
                    }
                if (trackId.isBlank()) {
                    showTogetherNotice(getString(R.string.not_allowed), key = "GUEST_PLAYQUEUE_NO_TRACK")
                    return@launch
                }

                val track =
                    moe.rukamori.archivetune.together.TogetherTrack(
                        id = trackId,
                        title = meta?.title ?: trackId,
                        artists = meta?.artists?.map { it.name }.orEmpty(),
                        durationSec = meta?.duration ?: -1,
                        thumbnailUrl = meta?.thumbnailUrl,
                    )

                val ops =
                    moe.rukamori.archivetune.together.TogetherGuestPlaybackPlanner.planPlayTrackNow(
                        roomState = joined.roomState,
                        track = track,
                        positionMs = initialStatus.position,
                        playWhenReady = playWhenReady,
                    )

                if (ops.isEmpty()) {
                    showTogetherNotice(getString(R.string.not_allowed), key = "GUEST_PLAYQUEUE_BLOCKED")
                    return@launch
                }

                showTogetherNotice(getString(R.string.together_requesting_song_change), key = "GUEST_PLAYQUEUE_REQUEST")
                ops.forEach { op ->
                    when (op) {
                        is moe.rukamori.archivetune.together.TogetherGuestOp.Control -> requestTogetherControl(op.action)
                        is moe.rukamori.archivetune.together.TogetherGuestOp.AddTrack -> requestTogetherAddTrack(op.track, op.mode)
                    }
                }
            }
            return
        }
        if (playWhenReady) {
            cancelIdleStop()
            promoteToStartedService()
            ensureStartedAsForeground()
        }
        cancelRestoredQueueHydration()
        ensureScopesActive()
        cancelCrossfade(resetVolume = true, resetPauseAtEnd = true)
        cancelInfiniteQueueBootstrap()
        suppressAutoPlayback = false
        currentQueue = queue
        queueTitle = null
        val permanentShuffle = dataStore.get(PermanentShuffleKey, false)
        if (!permanentShuffle) {
            player.shuffleModeEnabled = false
        }

        clearAutomix()
        autoAddedMediaIds.clear()
        scope.launch(SilentHandler) {
            val hideExplicit = dataStore.get(HideExplicitKey, false)
            val hideVideo = dataStore.get(HideVideoKey, false)
            val autoLoadMoreEnabled = dataStore.get(AutoLoadMoreKey, true)
            val preloadItem =
                queue.preloadItem
                    ?.toMediaItem()
                    ?.takeUnless { item ->
                        item.hasBlockedArtist(loadBlockedArtistIds())
                    }
            if (preloadItem != null) {
                player.setMediaItem(preloadItem)
                player.prepare()
                player.playWhenReady = playWhenReady
            }
            var initialStatus =
                withContext(Dispatchers.IO) {
                    queue
                        .getInitialStatus()
                        .filterPlaybackContent(hideExplicit, hideVideo)
                }
            if (!autoLoadMoreEnabled && queue.shouldExpandToFullQueueWhenAutoLoadMoreDisabled() && queue.hasNextPage()) {
                val expandedItems = initialStatus.items.toMutableList()
                var pagesLoaded = 0
                while (queue.hasNextPage() && pagesLoaded < 200) {
                    pagesLoaded++
                    val nextItems =
                        withContext(Dispatchers.IO) {
                            queue
                                .nextPage()
                                .filterPlaybackContent(hideExplicit, hideVideo)
                        }
                    if (nextItems.isNotEmpty()) {
                        expandedItems += nextItems
                    }
                }
                initialStatus = initialStatus.copy(items = expandedItems)
            }
            if (initialStatus.title != null) {
                queueTitle = initialStatus.title
            }
            if (initialStatus.items.isEmpty()) return@launch
            if (preloadItem != null) {
                val preloadMediaId = preloadItem.mediaId.trim()
                val insertionIndex =
                    initialStatus.mediaItemIndex.coerceIn(0, initialStatus.items.size)
                val itemsBeforeCurrent =
                    initialStatus.items
                        .subList(0, insertionIndex)
                        .filterNot { preloadMediaId.isNotEmpty() && it.mediaId.trim() == preloadMediaId }
                val itemsAfterCurrent =
                    initialStatus.items
                        .subList(insertionIndex, initialStatus.items.size)
                        .filterNot { preloadMediaId.isNotEmpty() && it.mediaId.trim() == preloadMediaId }

                player.addMediaItems(0, itemsBeforeCurrent)
                player.addMediaItems(itemsAfterCurrent)
                if (player.shuffleModeEnabled) {
                    applyCurrentFirstShuffleOrder()
                }
            } else {
                val items = initialStatus.items
                val index = initialStatus.mediaItemIndex

                player.setMediaItems(items, index, initialStatus.position)
                player.prepare()
                player.playWhenReady = playWhenReady
                if (player.shuffleModeEnabled) {
                    applyCurrentFirstShuffleOrder()
                }
            }
        }
    }

    private fun applyCurrentFirstShuffleOrder() {
        val count = player.mediaItemCount
        if (count <= 1) return
        val currentIndex = player.currentMediaItemIndex.coerceIn(0, count - 1)
        val shuffledIndices = IntArray(count) { it }
        shuffledIndices.shuffle()
        val currentPos = shuffledIndices.indexOf(currentIndex)
        if (currentPos >= 0) {
            shuffledIndices[currentPos] = shuffledIndices[0]
        }
        shuffledIndices[0] = currentIndex
        localPlayer.setShuffleOrder(DefaultShuffleOrder(shuffledIndices, System.currentTimeMillis()))
    }

    private fun buildPlayNextShuffleOrder(
        currentIndex: Int,
        insertionIndex: Int,
        insertionCount: Int,
    ): DefaultShuffleOrder? {
        if (insertionCount <= 0 || player.currentTimeline.isEmpty) return null

        fun adjustedIndex(index: Int): Int =
            if (index >= insertionIndex) {
                index + insertionCount
            } else {
                index
            }

        val timeline = player.currentTimeline
        val previousIndices = ArrayDeque<Int>()
        var traversalIndex = currentIndex
        while (true) {
            traversalIndex = timeline.getPreviousWindowIndex(traversalIndex, REPEAT_MODE_OFF, true)
            if (traversalIndex == C.INDEX_UNSET) {
                break
            }
            previousIndices.addFirst(adjustedIndex(traversalIndex))
        }

        val nextIndices = mutableListOf<Int>()
        traversalIndex = currentIndex
        while (true) {
            traversalIndex = timeline.getNextWindowIndex(traversalIndex, REPEAT_MODE_OFF, true)
            if (traversalIndex == C.INDEX_UNSET) {
                break
            }
            nextIndices += adjustedIndex(traversalIndex)
        }

        val shuffledIndices =
            buildList(player.mediaItemCount + insertionCount) {
                addAll(previousIndices)
                add(currentIndex)
                repeat(insertionCount) { offset ->
                    add(insertionIndex + offset)
                }
                addAll(nextIndices)
            }.toIntArray()

        return DefaultShuffleOrder(shuffledIndices, System.currentTimeMillis())
    }

    fun startRadioSeamlessly() {
        val joined = togetherSessionState.value as? moe.rukamori.archivetune.together.TogetherSessionState.Joined
        if (!isTogetherApplyingRemote() && joined?.role is moe.rukamori.archivetune.together.TogetherRole.Guest) {
            if (!joined.roomState.settings.allowGuestsToControlPlayback) {
                showTogetherNotice(getString(R.string.not_allowed), key = "GUEST_RADIO_DISABLED")
                return
            }
            showTogetherNotice(getString(R.string.not_allowed), key = "GUEST_RADIO_UNSUPPORTED")
            return
        }
        cancelInfiniteQueueBootstrap()
        suppressAutoPlayback = false
        val currentMediaMetadata = player.currentMetadata ?: return

        val currentIndex = player.currentMediaItemIndex
        val currentMediaId = currentMediaMetadata.id
        if (currentSong.value?.song?.isLocal == true || currentMediaId.isLocalMediaId()) {
            return
        }

        scope.launch(SilentHandler) {
            val radioQueue =
                YouTubeQueue(
                    endpoint = WatchEndpoint(videoId = currentMediaId),
                    followAutomixPreview = true,
                )
            val initialStatus =
                withContext(Dispatchers.IO) {
                    radioQueue
                        .getInitialStatus()
                        .filterPlaybackContent(
                            hideExplicit = dataStore.get(HideExplicitKey, false),
                            hideVideo = dataStore.get(HideVideoKey, false),
                        )
                }

            if (initialStatus.title != null) {
                queueTitle = initialStatus.title
            }

            val radioItems =
                initialStatus.items.filter { item ->
                    item.mediaId != currentMediaId
                }

            if (radioItems.isNotEmpty()) {
                val itemCount = player.mediaItemCount

                if (itemCount > currentIndex + 1) {
                    player.removeMediaItems(currentIndex + 1, itemCount)
                }

                player.addMediaItems(currentIndex + 1, radioItems)
            }

            currentQueue = radioQueue
        }
    }

    fun clearAutomix() {
        autoAddedMediaIds.clear()
    }

    fun onInfiniteQueueDisabled() {
        cancelInfiniteQueueBootstrap()
        val currentIndex = player.currentMediaItemIndex
        val idsToRemove = synchronized(autoAddedMediaIds) { autoAddedMediaIds.toSet() }
        if (idsToRemove.isEmpty()) {
            return
        }
        for (i in player.mediaItemCount - 1 downTo 0) {
            if (i == currentIndex) continue
            val item = player.getMediaItemAt(i)
            if (item.mediaId in idsToRemove) {
                player.removeMediaItem(i)
            }
        }
        autoAddedMediaIds.clear()
        currentQueue = EmptyQueue
    }

    fun onInfiniteQueueEnabled() {
        val currentMeta = player.currentMetadata ?: return
        if (isCurrentPlaybackItemLocal(currentMeta)) return
        if (infiniteQueueJob?.isActive == true) return

        val seedMediaId = currentMeta.id.trim().ifBlank { return }
        val generation = ++infiniteQueueGeneration
        infiniteQueueLoading.value = true

        infiniteQueueJob =
            scope.launch(SilentHandler) {
                try {
                    val hideExplicit = dataStore.get(HideExplicitKey, false)
                    val hideVideo = dataStore.get(HideVideoKey, false)
                    val radioQueue = YouTubeQueue(WatchEndpoint(videoId = seedMediaId), followAutomixPreview = true)
                    val status =
                        withContext(Dispatchers.IO) {
                            radioQueue
                                .getInitialStatus()
                                .filterPlaybackContent(hideExplicit, hideVideo)
                        }
                    val knownIds =
                        (0 until player.mediaItemCount)
                            .mapTo(mutableSetOf()) { player.getMediaItemAt(it).mediaId }
                    val newItems = status.items.filter { knownIds.add(it.mediaId) }.toMutableList()
                    var loadedPageCount = 1

                    while (
                        newItems.isEmpty() &&
                        radioQueue.hasNextPage() &&
                        loadedPageCount < INFINITE_QUEUE_MAX_BOOTSTRAP_PAGES
                    ) {
                        loadedPageCount++
                        val page =
                            withContext(Dispatchers.IO) {
                                radioQueue
                                    .nextPage()
                                    .filterPlaybackContent(hideExplicit, hideVideo)
                            }
                        newItems += page.filter { knownIds.add(it.mediaId) }
                    }

                    if (generation != infiniteQueueGeneration) return@launch

                    if (newItems.isNotEmpty()) {
                        player.addMediaItems(newItems)
                        newItems.forEach { autoAddedMediaIds.add(it.mediaId) }
                    }

                    currentQueue = radioQueue

                    if (player.playbackState == Player.STATE_ENDED ||
                        player.mediaItemCount == player.currentMediaItemIndex + 1
                    ) {
                        player.seekToNext()
                        player.play()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Failed to bootstrap auto-queue")
                } finally {
                    if (generation == infiniteQueueGeneration) {
                        infiniteQueueJob = null
                        infiniteQueueLoading.value = false
                    }
                }
            }
    }

    private fun cancelInfiniteQueueBootstrap() {
        infiniteQueueGeneration++
        infiniteQueueJob?.cancel()
        infiniteQueueJob = null
        infiniteQueueLoading.value = false
    }

    fun stopAndClearPlayback(clearPersistentState: Boolean = false) {
        cancelRestoredQueueHydration()
        cancelInfiniteQueueBootstrap()
        suppressAutoPlayback = true
        cancelCrossfade(resetVolume = true, resetPauseAtEnd = true)
        clearAutomix()
        currentQueue = EmptyQueue
        queueTitle = null
        waitingForNetworkConnection.value = false
        currentMediaMetadata.value = null
        player.playWhenReady = false
        player.stop()
        player.clearMediaItems()
        abandonAudioFocus()
        closeAudioEffectSession()
        consecutivePlaybackErr = 0
        if (clearPersistentState) {
            clearPersistedQueueFiles()
        }
    }

    fun playNext(items: List<MediaItem>) {
        val allowedItems =
            items
                .filterBlockedArtists(blockedArtistIds)
                .filterVideo(hideMusicVideos)
        if (allowedItems.isEmpty()) return
        val joined = togetherSessionState.value as? moe.rukamori.archivetune.together.TogetherSessionState.Joined
        if (joined?.role is moe.rukamori.archivetune.together.TogetherRole.Guest) {
            if (!joined.roomState.settings.allowGuestsToAddTracks) {
                return
            }
            val tracks =
                allowedItems.mapNotNull { it.metadata }.map { meta ->
                    moe.rukamori.archivetune.together.TogetherTrack(
                        id = meta.id,
                        title = meta.title,
                        artists = meta.artists.map { it.name },
                        durationSec = meta.duration,
                        thumbnailUrl = meta.thumbnailUrl,
                    )
                }
            tracks.asReversed().forEach { track ->
                requestTogetherAddTrack(track, moe.rukamori.archivetune.together.AddTrackMode.PLAY_NEXT)
            }
            return
        }
        suppressAutoPlayback = false
        val insertionIndex = if (player.mediaItemCount == 0) 0 else player.currentMediaItemIndex + 1
        val playNextShuffleOrder =
            if (player.shuffleModeEnabled && player.mediaItemCount > 0) {
                buildPlayNextShuffleOrder(
                    currentIndex = player.currentMediaItemIndex,
                    insertionIndex = insertionIndex,
                    insertionCount = allowedItems.size,
                )
            } else {
                null
            }

        player.addMediaItems(insertionIndex, allowedItems)
        playNextShuffleOrder?.let(localPlayer::setShuffleOrder)
        player.prepare()
    }

    fun addToQueue(items: List<MediaItem>) {
        val allowedItems =
            items
                .filterBlockedArtists(blockedArtistIds)
                .filterVideo(hideMusicVideos)
        if (allowedItems.isEmpty()) return
        val joined = togetherSessionState.value as? moe.rukamori.archivetune.together.TogetherSessionState.Joined
        if (joined?.role is moe.rukamori.archivetune.together.TogetherRole.Guest) {
            if (!joined.roomState.settings.allowGuestsToAddTracks) {
                return
            }
            val tracks =
                allowedItems.mapNotNull { it.metadata }.map { meta ->
                    moe.rukamori.archivetune.together.TogetherTrack(
                        id = meta.id,
                        title = meta.title,
                        artists = meta.artists.map { it.name },
                        durationSec = meta.duration,
                        thumbnailUrl = meta.thumbnailUrl,
                    )
                }
            tracks.forEach { track ->
                requestTogetherAddTrack(track, moe.rukamori.archivetune.together.AddTrackMode.ADD_TO_QUEUE)
            }
            return
        }
        suppressAutoPlayback = false
        player.addMediaItems(allowedItems)
        player.prepare()
    }

    fun playFromVoiceSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return
        ensureScopesActive()
        scope.launch(SilentHandler) {
            val mediaItems =
                withContext(Dispatchers.IO) {
                    mediaLibrarySessionCallback.resolveVoiceMediaItems(trimmed)
                }
            if (mediaItems.isEmpty()) return@launch
            playQueue(ListQueue(items = mediaItems))
        }
    }

        fun startTogetherHost(
        port: Int,
        displayName: String,
        settings: moe.rukamori.archivetune.together.TogetherRoomSettings,
    ) {
        ensureScopesActive()
        scope.launch(SilentHandler) {
            togetherSessionState.value = moe.rukamori.archivetune.together.TogetherSessionState.Idle
        }

        ioScope.launch(SilentHandler) {
            stopTogetherInternal()
            togetherIsOnlineSession = false

            val localIp = getLocalIpv4Address()
            val sessionId =
                java.util.UUID
                    .randomUUID()
                    .toString()
            val sessionKey =
                java.util.UUID
                    .randomUUID()
                    .toString()
            val joinInfo =
                moe.rukamori.archivetune.together.TogetherJoinInfo(
                    host = localIp ?: "127.0.0.1",
                    port = port,
                    sessionId = sessionId,
                    sessionKey = sessionKey,
                )
            val joinLink =
                moe.rukamori.archivetune.together.TogetherLink
                    .encode(joinInfo)

            val server = createTogetherServer(
                port = port,
                displayName = displayName,
                settings = settings,
                sessionId = sessionId,
                sessionKey = sessionKey,
            )

            scheduleTogetherHostInactivityTimeout(sessionId)

            scope.launch(SilentHandler) {
                togetherSessionState.value =
                    moe.rukamori.archivetune.together.TogetherSessionState.Hosting(
                        sessionId = sessionId,
                        joinLink = joinLink,
                        localAddressHint = localIp ?: "127.0.0.1",
                        port = port,
                        settings = settings,
                        roomState = null,
                    )
            }

            startBroadcastLoop(server = server, sessionId = sessionId)
        }
    }

        ioScope.launch(SilentHandler) {
            stopTogetherInternal()
            togetherIsOnlineSession = false

            val localIp = getLocalIpv4Address()
            val sessionId =
                java.util.UUID
                    .randomUUID()
                    .toString()
            val sessionKey =
                java.util.UUID
                    .randomUUID()
                    .toString()
            val joinInfo =
                moe.rukamori.archivetune.together.TogetherJoinInfo(
                    host = localIp ?: "127.0.0.1",
                    port = port,
                    sessionId = sessionId,
                    sessionKey = sessionKey,
                )
            val joinLink =
                moe.rukamori.archivetune.together.TogetherLink
                    .encode(joinInfo)

            val server =
                moe.rukamori.archivetune.together.TogetherServer(
                    scope = ioScope,
                    sessionId = sessionId,
                    sessionKey = sessionKey,
                    hostDisplayName = displayName.trim().ifBlank { getString(R.string.app_name) },
                    initialSettings = settings,
                    hostParticipantId = togetherHostId,
                )

            server.onEvent = { event ->
                ioScope.launch(SilentHandler) {
                    handleTogetherHostEvent(event) { server.currentSettings() }
                }
            }

            server.start(port)
            togetherServer = server
            scheduleTogetherHostInactivityTimeout(sessionId)

            scope.launch(SilentHandler) {
                togetherSessionState.value =
                    moe.rukamori.archivetune.together.TogetherSessionState.Hosting(
                        sessionId = sessionId,
                        joinLink = joinLink,
                        localAddressHint = localIp,
                        port = port,
                        settings = settings,
                        roomState = null,
                    )
            }

            togetherBroadcastJob =
                ioScope.launch(SilentHandler) {
                    while (togetherServer === server) {
                        if (togetherAuthorityParticipantId == null || togetherAuthorityParticipantId == togetherHostId) {
                            val state = buildTogetherRoomState(sessionId = sessionId, hostId = togetherHostId)
                            server.broadcastRoomState(state)
                            scope.launch(SilentHandler) {
                                val hosting = togetherSessionState.value as? moe.rukamori.archivetune.together.TogetherSessionState.Hosting
                                if (hosting?.sessionId == sessionId) {
                                    togetherSessionState.value =
                                        hosting.copy(
                                            settings = server.currentSettings(),
                                            roomState =
                                                state.copy(
                                                    participants = server.currentParticipants(),
                                                    settings = server.currentSettings(),
                                                ),
                                        )
                                }
                            }
                        }
                        kotlinx.coroutines.delay(TogetherPlaybackSync.BroadcastIntervalMs)
                    }
                }
        }
    }

    private fun togetherOnlineErrorMessage(t: Throwable): String {
        if (t is moe.rukamori.archivetune.together.TogetherOnlineApiException) {
            val code = t.statusCode
            return when {
                code == 404 -> getString(R.string.together_session_not_found)
                code != null && code in 500..599 -> getString(R.string.together_server_error)
                else -> t.message ?: getString(R.string.network_unavailable)
            }
        }
        val root = generateSequence(t) { it.cause }.lastOrNull() ?: t
        return when (root) {
            is UnknownHostException -> getString(R.string.together_server_unreachable)
            is ConnectException -> getString(R.string.together_server_unreachable)
            is SocketTimeoutException -> getString(R.string.together_connection_timed_out)
            is javax.net.ssl.SSLHandshakeException -> getString(R.string.together_server_unreachable)
            else -> getString(R.string.network_unavailable)
        }
    }

    fun startTogetherOnlineHost(
        displayName: String,
        settings: moe.rukamori.archivetune.together.TogetherRoomSettings,
    ) {
        ensureScopesActive()
        scope.launch(SilentHandler) {
            togetherSessionState.value = moe.rukamori.archivetune.together.TogetherSessionState.Idle
        }

        ioScope.launch(SilentHandler) {
            stopTogetherInternal()
            togetherIsOnlineSession = true

            val baseUrl =
                moe.rukamori.archivetune.together.TogetherOnlineEndpoint
                    .baseUrlOrNull(dataStore)
            if (baseUrl == null) {
                scope.launch(SilentHandler) {
                    togetherSessionState.value =
                        moe.rukamori.archivetune.together.TogetherSessionState.Error(
                            message = getString(R.string.together_online_not_configured),
                            recoverable = true,
                        )
                }
                return@launch
            }

            val togetherToken =
                moe.rukamori.archivetune.BuildConfig.TOGETHER_BEARER_TOKEN
                    .trim()
                    .takeIf { it.isNotBlank() }
            if (togetherToken == null) {
                scope.launch(SilentHandler) {
                    togetherSessionState.value =
                        moe.rukamori.archivetune.together.TogetherSessionState.Error(
                            message = getString(R.string.together_token_missing),
                            recoverable = true,
                        )
                }
                return@launch
            }

            val api =
                moe.rukamori.archivetune.together
                    .TogetherOnlineApi(baseUrl = baseUrl, bearerToken = togetherToken)
            val hostName = displayName.trim().ifBlank { getString(R.string.app_name) }

            val created =
                runCatching {
                    api.createSession(
                        hostDisplayName = hostName,
                        settings = settings,
                    )
                }.getOrElse { t ->
                    scope.launch(SilentHandler) {
                        togetherSessionState.value =
                            moe.rukamori.archivetune.together.TogetherSessionState.Error(
                                message = togetherOnlineErrorMessage(t),
                                recoverable = true,
                            )
                    }
                    reportException(t)
                    return@launch
                }

            val onlineHost =
                moe.rukamori.archivetune.together.TogetherOnlineHost(
                    externalScope = ioScope,
                    sessionId = created.sessionId,
                    sessionKey = created.hostKey,
                    hostId = togetherHostId,
                    hostDisplayName = hostName,
                    initialSettings = created.settings,
                    clientId = getOrCreateTogetherClientId(),
                    bearerToken = togetherToken,
                )

            onlineHost.onEvent = { event ->
                ioScope.launch(SilentHandler) {
                    handleTogetherHostEvent(event) { onlineHost.currentSettings() }
                }
            }

            togetherOnlineHost = onlineHost
            scheduleTogetherHostInactivityTimeout(created.sessionId) {
                api.endSession(
                    sessionId = created.sessionId,
                    hostKey = created.hostKey,
                )
            }

            scope.launch(SilentHandler) {
                togetherSessionState.value =
                    moe.rukamori.archivetune.together.TogetherSessionState.HostingOnline(
                        sessionId = created.sessionId,
                        code = created.code,
                        settings = created.settings,
                        roomState = null,
                    )
            }

            val wsUrl =
                moe.rukamori.archivetune.together.TogetherOnlineEndpoint.onlineWebSocketUrlOrNull(
                    rawWsUrl = created.wsUrl,
                    baseUrl = baseUrl,
                )
            if (wsUrl == null) {
                scope.launch(SilentHandler) {
                    togetherSessionState.value =
                        moe.rukamori.archivetune.together.TogetherSessionState.Error(
                            message = "Connection failed: Invalid server websocket URL",
                            recoverable = true,
                        )
                }
                ioScope.launch(SilentHandler) { stopTogetherInternal() }
                return@launch
            }

            togetherOnlineConnectJob?.cancel()
            togetherOnlineConnectJob =
                ioScope.launch(SilentHandler) {
                    onlineHost.connect(wsUrl)
                }

            togetherBroadcastJob =
                ioScope.launch(SilentHandler) {
                    while (togetherOnlineHost === onlineHost) {
                        val state =
                            if (togetherAuthorityParticipantId == null || togetherAuthorityParticipantId == togetherHostId) {
                                buildTogetherRoomState(
                                    sessionId = created.sessionId,
                                    hostId = togetherHostId,
                                )
                            } else {
                                null
                            }
                        if (state != null) {
                            onlineHost.broadcastRoomState(state)
                            scope.launch(SilentHandler) {
                                val hosting =
                                    togetherSessionState.value as? moe.rukamori.archivetune.together.TogetherSessionState.HostingOnline
                                if (hosting?.sessionId == created.sessionId) {
                                    val currentSettings = onlineHost.currentSettings()
                                    togetherSessionState.value =
                                        hosting.copy(
                                            settings = currentSettings,
                                            roomState =
                                                state.copy(
                                                    participants = onlineHost.currentParticipants(),
                                                    settings = currentSettings,
                                                ),
                                        )
                                }
                            }
                        }
                        kotlinx.coroutines.delay(TogetherPlaybackSync.BroadcastIntervalMs)
                    }
                }
        }
    }


                fun startTogetherPersonalHost(
        displayName: String,
        settings: moe.rukamori.archivetune.together.TogetherRoomSettings,
    ) {
        ensureScopesActive()
        scope.launch(SilentHandler) {
            togetherSessionState.value = moe.rukamori.archivetune.together.TogetherSessionState.Idle
        }

        ioScope.launch(SilentHandler) {
            stopTogetherInternal()
            togetherIsOnlineSession = false

            // Discover tunnel URL from provider
            val tunnelResult = tunnelProvider.discoverTunnelUrl()
            when (tunnelResult) {
                is TunnelResult.Success -> {
                    val publicUrl = tunnelResult.publicUrl
                    // Build WebSocket URL for Together
                    val wsUrl = publicUrl.newBuilder()
                        .scheme(if (publicUrl.isHttps) "wss" else "ws")
                        .addEncodedPathSegment("together")
                        .build()
                        .toString()

                    // Generate session details
                    val sessionId = java.util.UUID.randomUUID().toString()
                    val sessionKey = java.util.UUID.randomUUID().toString()

                    // Use the same port as LAN (or could be configurable)
                    val port = dataStore.get(TogetherDefaultPortKey, 42117)

                    val server = createTogetherServer(
                        port = port,
                        displayName = displayName,
                        settings = settings,
                        sessionId = sessionId,
                        sessionKey = sessionKey,
                    )

                    // Build join info with wsUrl
                    val joinInfo = moe.rukamori.archivetune.together.TogetherJoinInfo(
                        host = "tunnel",  // dummy, wsUrl overrides
                        port = 443,       // dummy, wsUrl overrides
                        sessionId = sessionId,
                        sessionKey = sessionKey,
                        wsUrl = wsUrl,
                    )
                    val joinLink = moe.rukamori.archivetune.together.TogetherLink.encode(joinInfo)

                    scheduleTogetherHostInactivityTimeout(sessionId)

                    scope.launch(SilentHandler) {
                        togetherSessionState.value =
                            moe.rukamori.archivetune.together.TogetherSessionState.Hosting(
                                sessionId = sessionId,
                                joinLink = joinLink,
                                localAddressHint = "tunnel",
                                port = port,
                                settings = settings,
                                roomState = null,
                            )
                    }

                    startBroadcastLoop(server = server, sessionId = sessionId)
                }
                is TunnelResult.Error -> {
                    scope.launch(SilentHandler) {
                        togetherSessionState.value =
                            moe.rukamori.archivetune.together.TogetherSessionState.Error(
                                message = tunnelResult.message,
                            )
                    }
                }
            }
        }
    }

    private suspend fun createTogetherServer(
        port: Int,
        displayName: String,
        settings: moe.rukamori.archivetune.together.TogetherRoomSettings,
        sessionId: String,
        sessionKey: String,
    ): moe.rukamori.archivetune.together.TogetherServer {
        val server = moe.rukamori.archivetune.together.TogetherServer(
            scope = ioScope,
            sessionId = sessionId,
            sessionKey = sessionKey,
            hostDisplayName = displayName.trim().ifBlank { getString(R.string.app_name) },
            initialSettings = settings,
            hostParticipantId = togetherHostId,
        )
        server.onEvent = { event ->
            ioScope.launch(SilentHandler) {
                handleTogetherHostEvent(event) { server.currentSettings() }
            }
        }
        server.start(port)
        togetherServer = server
        return server
    }

    private suspend fun startBroadcastLoop(
        server: moe.rukamori.archivetune.together.TogetherServer,
        sessionId: String,
    ) {
        togetherBroadcastJob =
            ioScope.launch(SilentHandler) {
                while (togetherServer === server) {
                    if (togetherAuthorityParticipantId == null || togetherAuthorityParticipantId == togetherHostId) {
                        val state = buildTogetherRoomState(sessionId = sessionId, hostId = togetherHostId)
                        server.broadcastRoomState(state)
                        scope.launch(SilentHandler) {
                            val hosting = togetherSessionState.value as? moe.rukamori.archivetune.together.TogetherSessionState.Hosting
                            if (hosting?.sessionId == sessionId) {
                                togetherSessionState.value =
                                    hosting.copy(
                                        settings = server.currentSettings(),
                                        roomState =
                                            state.copy(
                                                participants = server.currentParticipants(),
                                                settings = server.currentSettings(),
                                            ),
                                    )
                            }
                        }
                    }
                    kotlinx.coroutines.delay(TogetherPlaybackSync.BroadcastIntervalMs)
                }
            }
    }
    companion object {
}
