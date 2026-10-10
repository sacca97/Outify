package cc.tomko.outify.core.spirc

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import android.util.Log
import cc.tomko.outify.core.AuthManager
import cc.tomko.outify.core.Session
import cc.tomko.outify.core.SessionCallback
import cc.tomko.outify.data.repository.SettingsRepository
import cc.tomko.outify.playback.PlaybackStateHolder
import cc.tomko.outify.playback.model.getSpeed
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "SpircController"

/** Upper bound for [SpircController.restartAndAwaitReady]. */
private const val RESTART_TIMEOUT_MS = 30_000L

/** Automatic retries after a failure back off from the first delay up to the maximum. */
private const val INITIAL_RETRY_DELAY_MS = 2_000L
private const val MAX_RETRY_DELAY_MS = 30_000L

/** Automatic retries before giving up until the user tries to play something. */
private const val MAX_AUTO_RETRIES = 5

/** Minimum time between two retries triggered by dropped commands. */
private const val MIN_COMMAND_RETRY_GAP_MS = 2_000L

/** How long the network must stay unchanged before the session is rebuilt for it. */
private const val NETWORK_SETTLE_MS = 2_000L

/** Minimum time between two rebuilds triggered by network changes. */
private const val MIN_NETWORK_RESTART_GAP_MS = 10_000L

/**
 * Owns the spirc lifecycle.
 */
@Singleton
class SpircController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: Session,
    private val authManager: AuthManager,
    private val spirc: SpircWrapper,
    private val playbackStateHolder: PlaybackStateHolder,
    private val settingsRepository: SettingsRepository,
) {
    private val _state = MutableStateFlow(SpircState.Stopped)

    /** Current lifecycle state of the Connect runtime. */
    val state: StateFlow<SpircState> = _state.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)

    /** Reason of the last failure, or `null` when the last attempt succeeded. */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var bufferCallbacksRegistered = false

    private var dropCollectorStarted = false
    private var retryJob: Job? = null
    private var retryDelayMs = INITIAL_RETRY_DELAY_MS
    private var autoRetries = 0
    private var lastCommandRetryAt = 0L

    private var networkWatched = false
    private var networkJob: Job? = null
    private var lastNetworkRestartAt = 0L

    fun start() {
        collectDroppedCommands()
        watchNetwork()

        session.initializeSession(object : SessionCallback {
            override fun onInitialized() {
                spirc.scope.launch {
                    initializeSpirc()
                }
            }

            override fun onShutdown() {
                // The core session is gone; a rebuild is already under way.
                updateState(SpircState.Rebuilding)
            }

            override fun onRestarting() {
                updateState(SpircState.Rebuilding)
            }

            override fun onRestarted() {
                // A first start without a login never reached the registration in
                // initializeSpirc.
                registerCallbacks()
                updateState(SpircState.Ready)
                refreshPlaybackState()
            }

            override fun onFailed(reason: String) {
                Log.e(TAG, "spirc failed: $reason")
                _lastError.value = reason
                updateState(SpircState.Failed)
            }
        })
    }

    /**
     * Rebuilds the session when the device switches network, for example from
     * Wi-Fi to mobile data. The connections of the old network can die without
     * any error, which leaves the player "playing" with no audio arriving.
     */
    private fun watchNetwork() {
        if (networkWatched) return
        networkWatched = true

        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return
        var current: Network? = null

        try {
            connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val previous = current
                    current = network
                    // The first callback only reports the network we start on.
                    if (previous != null && previous != network) onNetworkChanged()
                }
            })
        } catch (e: SecurityException) {
            Log.w(TAG, "cannot watch the network", e)
        }
    }

    private fun onNetworkChanged() {
        networkJob?.cancel()
        networkJob = spirc.scope.launch {
            delay(NETWORK_SETTLE_MS)

            val now = SystemClock.elapsedRealtime()
            if (now - lastNetworkRestartAt < MIN_NETWORK_RESTART_GAP_MS) return@launch

            when {
                // A failed runtime may work on the new network, so skip the backoff.
                _state.value == SpircState.Failed -> {
                    retryJob?.cancel()
                    autoRetries = 0
                }
                // Only the device that is playing has something to lose; rebuilding
                // activates the device and must not take over from another one.
                _state.value == SpircState.Ready &&
                        playbackStateHolder.state.value.isActiveDevice -> Unit
                else -> return@launch
            }

            lastNetworkRestartAt = now
            Log.i(TAG, "network changed, rebuilding the session")
            restart("network changed")
        }
    }

    /**
     * The user is waiting for a command that was dropped: if the runtime has
     * failed, try to bring it back right away instead of waiting for the backoff.
     */
    private fun collectDroppedCommands() {
        if (dropCollectorStarted) return
        dropCollectorStarted = true

        spirc.scope.launch {
            spirc.droppedCommands.collect { command ->
                if (_state.value != SpircState.Failed) return@collect

                val now = SystemClock.elapsedRealtime()
                if (now - lastCommandRetryAt < MIN_COMMAND_RETRY_GAP_MS) return@collect
                lastCommandRetryAt = now

                Log.w(TAG, "retrying spirc, '$command' was dropped while failed")
                retryJob?.cancel()
                autoRetries = 0
                restart("command while failed")
            }
        }
    }

    /** Retries after a failure, with an increasing delay and a bounded number of attempts. */
    private fun scheduleRetry() {
        retryJob?.cancel()

        if (autoRetries >= MAX_AUTO_RETRIES) {
            Log.w(TAG, "spirc still failing after $autoRetries retries, waiting for a playback command")
            return
        }

        val delayMs = retryDelayMs
        retryDelayMs = (delayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
        autoRetries++

        Log.w(TAG, "spirc failed (${_lastError.value ?: "unknown reason"}), retry $autoRetries/$MAX_AUTO_RETRIES in $delayMs ms")
        retryJob = spirc.scope.launch {
            delay(delayMs)
            restart("auto retry $autoRetries")
        }
    }

    /**
     * Rebuilds the Connect runtime, applying the current playback settings.
     */
    fun restart(reason: String = "requested") {
        if (!waitingForLogin()) return
        updateState(SpircState.Rebuilding)

        spirc.scope.launch {
            _lastError.value = null

            val gapless = settingsRepository.gaplessPlayback.first()
            val normalise = settingsRepository.normalizePlayback.first()
            val bitrate = settingsRepository.bitrate.first()
            val crossfadeMillis = settingsRepository.crossfadeMillis.first()
            val deviceName = settingsRepository.deviceName.first()
            val autoTransfer = settingsRepository.autoTransfer.first()

            if (!Spirc.requestRestart(reason, gapless, normalise, bitrate.getSpeed(), crossfadeMillis, deviceName, autoTransfer)) {
                Log.e(TAG, "restart request was rejected")
                updateState(SpircState.Failed)
            }
        }
    }

    /**
     * Without a playback login there is nothing to start, and trying anyway would
     * only fail, drop the session and retry. The login screen needs that session
     * to build its authorization URL. Returns `true` when a login exists.
     */
    private fun waitingForLogin(): Boolean {
        if (authManager.hasCachedCredentials()) return true

        Log.i(TAG, "no playback login yet, not starting spirc")
        retryJob?.cancel()
        updateState(SpircState.Stopped)
        return false
    }

    /**
     * Requests a restart and suspends until the runtime is usable again.
     */
    suspend fun restartAndAwaitReady(
        reason: String = "retry",
        timeoutMs: Long = RESTART_TIMEOUT_MS,
    ): Boolean {
        if (!authManager.hasCachedCredentials()) return false

        restart(reason)

        val settled = withTimeoutOrNull(timeoutMs.milliseconds) {
            state.first { it == SpircState.Ready || it == SpircState.Failed }
        }

        return settled == SpircState.Ready
    }

    private suspend fun initializeSpirc() {
        if (!waitingForLogin()) return

        val gapless = settingsRepository.gaplessPlayback.first()
        val normalise = settingsRepository.normalizePlayback.first()
        val bitrate = settingsRepository.bitrate.first()
        val crossfadeMillis = settingsRepository.crossfadeMillis.first()
        val deviceName = settingsRepository.deviceName.first()

        updateState(SpircState.Starting)

        Spirc.initializeSpirc(object : SpircInitializationCallback {
            override fun initialized() {
                registerCallbacks()
                updateState(SpircState.Ready)

                spirc.scope.launch {
                    activateAndTransfer()
                }
            }

            override fun failed() {
                Log.e(TAG, "spirc initialization failed")
                updateState(SpircState.Failed)
            }

        }, gapless, normalise, bitrate.getSpeed(), crossfadeMillis, deviceName)
    }

    /**
     * Registers the native playback callbacks once per process.
     */
    private fun registerCallbacks() {
        if (bufferCallbacksRegistered) return
        bufferCallbacksRegistered = true

        Spirc.bufferCallback(object : SpircBufferCallback {
            override fun started() {
                playbackStateHolder.setBuffering(true)
            }

            override fun stopped() {
                playbackStateHolder.setBuffering(false)
            }

        })

        Spirc.deviceCallback(object : SpircDeviceCallback {
            override fun becameActive() {
                spirc.startPlaybackService()
                playbackStateHolder.setActiveDevice(true)
            }

            override fun becameInactive() {
                playbackStateHolder.setActiveDevice(false)
            }

            override fun volumeChanged(volume: Int) {
                //volumeController.onRemoteVolumeChanged(volume)
            }
        })
    }

    /**
     * Re-applies the persisted playback preferences to the fresh runtime.
     */
    private fun refreshPlaybackState() {
        spirc.scope.launch {
            val shuffle = settingsRepository.shuffleEnabled.first()
            val repeat = settingsRepository.repeatEnabled.first()
            val repeatTrack = settingsRepository.repeatTrackEnabled.first()

            spirc.shuffle(shuffle)
            spirc.repeat(repeat, repeatTrack)
        }
    }

    private suspend fun activateAndTransfer() {
        spirc.startPlaybackService()

        if (!spirc.activate()) {
            Log.e(TAG, "Failed to activate Spirc session!")
            return
        }

        if (!settingsRepository.autoTransfer.first()) {
            return
        }

        if (!spirc.smartTransfer()) {
            Log.w(TAG, "Spirc session did not transfer!")
            playbackStateHolder.setActiveDevice(false)
            return
        }

        playbackStateHolder.setActiveDevice(true)
    }

    private fun updateState(state: SpircState) {
        if (_state.value == state) return

        Log.i(TAG, "state ${_state.value} -> $state")
        _state.value = state
        spirc.onStateChanged(state)

        when (state) {
            SpircState.Ready -> {
                retryJob?.cancel()
                retryDelayMs = INITIAL_RETRY_DELAY_MS
                autoRetries = 0
            }

            SpircState.Failed -> scheduleRetry()
            else -> Unit
        }
    }
}

/**
 * Lifecycle of the Connect runtime.
 */
enum class SpircState {
    /** Nothing is running. */
    Stopped,

    /** First startup is in progress. */
    Starting,

    /** Runtime is usable; playback commands are accepted. */
    Ready,

    /** Runtime is being rebuilt; playback commands are dropped. */
    Rebuilding,

    /** Initialization or the last rebuild gave up. */
    Failed;

    /** Whether playback commands can be forwarded right now. */
    val isUsable: Boolean
        get() = this == Ready
}
