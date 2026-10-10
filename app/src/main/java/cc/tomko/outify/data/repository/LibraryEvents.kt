package cc.tomko.outify.data.repository

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tells the library screen that the user's playlists, albums or shows changed,
 * so it can reload without a manual refresh.
 */
@Singleton
class LibraryEvents @Inject constructor() {
    private val _changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val changed: SharedFlow<Unit> = _changed

    fun notifyChanged() {
        _changed.tryEmit(Unit)
    }
}
