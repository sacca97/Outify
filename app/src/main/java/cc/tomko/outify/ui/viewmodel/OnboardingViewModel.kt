package cc.tomko.outify.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.net.toUri
import cc.tomko.outify.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.tomko.outify.BuildConfig
import cc.tomko.outify.LibrespotFfi
import cc.tomko.outify.core.AuthCallbackServerManager
import cc.tomko.outify.core.AuthManager
import cc.tomko.outify.core.AuthStateEvent
import cc.tomko.outify.core.AuthStateEventBus
import cc.tomko.outify.core.SpClient
import cc.tomko.outify.core.model.CurrentUserProfile
import cc.tomko.outify.core.spirc.SpircController
import cc.tomko.outify.data.repository.SettingsRepository
import cc.tomko.outify.services.OAuthService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * Which login a given onboarding step needs.
 */
enum class OnboardingStep(val isPlayback: Boolean, val isAccount: Boolean) {
    PLAYBACK(isPlayback = true, isAccount = false),
    ACCOUNT(isPlayback = false, isAccount = true),
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val authManager: AuthManager,
    private val spClient: SpClient,
    private val spircController: SpircController,
    private val settingsRepository: SettingsRepository,
    private val serverManager: AuthCallbackServerManager,
) : ViewModel() {
    private val json = Json { ignoreUnknownKeys = true }

    private val _isPlaybackLoggedIn = MutableStateFlow(false)
    val isPlaybackLoggedIn: StateFlow<Boolean> = _isPlaybackLoggedIn.asStateFlow()

    private val _isAccountLoggedIn = MutableStateFlow(false)
    val isAccountLoggedIn: StateFlow<Boolean> = _isAccountLoggedIn.asStateFlow()

    private val _steps = MutableStateFlow<List<OnboardingStep>>(emptyList())
    val steps: StateFlow<List<OnboardingStep>> = _steps.asStateFlow()

    /** Index of the step currently being shown. */
    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _isComplete = MutableStateFlow(false)
    val isComplete: StateFlow<Boolean> = _isComplete.asStateFlow()

    /**
     * Whether a Web API client ID is available, from the build or saved by the user.
     * The login uses PKCE, so no client secret is needed.
     */
    val hasClientId: StateFlow<Boolean> = settingsRepository.clientId
        .map { !(it ?: BuildConfig.SPOTIFY_CLIENT_ID).isEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    init {
        refreshState()
        viewModelScope.launch {
            AuthStateEventBus.events.collect { event ->
                when (event) {
                    is AuthStateEvent.AccountLoggedOut,
                    is AuthStateEvent.PlaybackLoggedOut -> refreshState()

                    is AuthStateEvent.AccountLoggedIn,
                    is AuthStateEvent.PlaybackLoggedIn -> refreshState()
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        serverManager.stop()
    }

    fun refreshState() {
        viewModelScope.launch {
            val playback = withContext(Dispatchers.IO) { authManager.hasCachedCredentials() }
            val account = withContext(Dispatchers.IO) { spClient.isOAuthAuthenticated() }
            _isPlaybackLoggedIn.value = playback
            _isAccountLoggedIn.value = account

            val needed = buildList {
                if (!playback) add(OnboardingStep.PLAYBACK)
                if (!account) add(OnboardingStep.ACCOUNT)
            }
            _steps.value = needed

            if (needed.isEmpty()) {
                _isComplete.value = true
            } else {
                _currentIndex.value = 0
                _isComplete.value = false
            }
        }
    }

    fun startPlaybackAuth(context: Context) {
        OAuthService.start(context)

        serverManager.start(onCodeReceived = { code, state ->
            OAuthService.stop(context)
            val result = authManager.handleOAuthCode(code, state)
            val isSuccess = result.contains("\"success\":true")
            if (isSuccess) {
                _isPlaybackLoggedIn.value = authManager.hasCachedCredentials()
                AuthStateEventBus.tryEmitPlaybackLoggedIn()
                spircController.restart()
                advanceAfterSuccess()
            }
        })

        val url = authManager.getAuthorizationURL()
        if (url == null) {
            OAuthService.stop(context)
            Toast.makeText(context, R.string.auth_not_ready, Toast.LENGTH_LONG).show()
            return
        }
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    }

    /**
     * Starts the Web API login. When a [clientId] is given it is saved first, so builds
     * without a bundled client ID can still sign in.
     */
    fun startAccountAuth(context: Context, clientId: String = "") {
        viewModelScope.launch {
            if (clientId.isNotBlank()) {
                val secret = settingsRepository.clientSecret.firstOrNull() ?: BuildConfig.SPOTIFY_CLIENT_SECRET
                settingsRepository.setClientId(clientId.trim())
                withContext(Dispatchers.IO) {
                    LibrespotFfi.updateClientCredentials(clientId.trim(), secret)
                }
            }

            OAuthService.start(context)

            serverManager.start(onCodeReceived = { code, _ ->
                OAuthService.stop(context)
                val result = spClient.completeOAuthFlow(code)
                val isSuccess = result.contains("\"success\":true")
                if (isSuccess) {
                    // Restart the Web API client so it picks up the fresh credentials
                    spClient.reset()
                    _isAccountLoggedIn.value = spClient.isOAuthAuthenticated()
                    AuthStateEventBus.tryEmitAccountLoggedIn()
                    fetchProfile()
                    advanceAfterSuccess()
                }
            })

            val url = withContext(Dispatchers.IO) { spClient.startOAuthFlow() }
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        }
    }

    private fun advanceAfterSuccess() {
        viewModelScope.launch {
            delay(150)
            val next = _currentIndex.value + 1
            if (next >= _steps.value.size) {
                _isComplete.value = true
            } else {
                _currentIndex.value = next
            }
        }
    }

    private fun fetchProfile() {
        viewModelScope.launch {
            try {
                val profile = withContext(Dispatchers.IO) { spClient.getCurrentUserProfile() } ?: return@launch
                val jsonObject = json.decodeFromString<CurrentUserProfile>(profile)
                settingsRepository.saveUserProfile(
                    jsonObject.id,
                    jsonObject.displayName,
                    jsonObject.images.firstOrNull()?.url,
                )
            } catch (_: Exception) {
            }
        }
    }
}
