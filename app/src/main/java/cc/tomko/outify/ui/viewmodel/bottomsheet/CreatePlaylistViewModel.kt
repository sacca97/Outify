package cc.tomko.outify.ui.viewmodel.bottomsheet

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.tomko.outify.core.SpClient
import cc.tomko.outify.data.dao.PlaylistDao
import cc.tomko.outify.data.database.PlaylistEntity
import cc.tomko.outify.data.metadata.Metadata
import cc.tomko.outify.data.repository.LibraryEvents
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class CreatePlaylistViewModel @Inject constructor(
    private val spClient: SpClient,
    private val playlistDao: PlaylistDao,
    private val libraryEvents: LibraryEvents,
    private val metadata: Metadata,
) : ViewModel() {

    private val _result = MutableSharedFlow<Result<String>>(extraBufferCapacity = 1)
    val result: SharedFlow<Result<String>> = _result

    private val _deleted = MutableSharedFlow<Result<String>>(extraBufferCapacity = 1)
    val deleted: SharedFlow<Result<String>> = _deleted

    fun deletePlaylist(playlistId: String) {
        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) { spClient.deletePlaylist(playlistId) }
            if (!success) {
                _deleted.tryEmit(Result.failure(RuntimeException("Failed to delete playlist")))
                return@launch
            }
            metadata.removeLikedPlaylist("spotify:playlist:$playlistId")
            playlistDao.deleteItems(playlistId)
            playlistDao.deletePlaylist(playlistId)
            libraryEvents.notifyChanged()
            _deleted.tryEmit(Result.success(playlistId))
        }
    }

    /** Whether the playlist is public, or null when that is unknown. */
    suspend fun loadPublic(playlistId: String): Boolean? = withContext(Dispatchers.IO) {
        try {
            spClient.playlistVisibility(playlistId)?.public
        } catch (e: Exception) {
            Log.w("CreatePlaylistViewModel", "Failed to load playlist visibility", e)
            null
        }
    }

    fun createPlaylist(
        name: String,
        description: String?,
        isPublic: Boolean,
        isCollaborative: Boolean
    ) {
        viewModelScope.launch {
            try {
                val playlistId = withContext(Dispatchers.IO) {
                    spClient.createPlaylist(name, description ?: "", isPublic, isCollaborative)
                } ?: return@launch

                playlistDao.upsertPlaylist(
                    PlaylistEntity(
                        id = playlistId,
                        uri = "spotify:playlist:$playlistId",
                        ownerUsername = withContext(Dispatchers.IO) { spClient.username() } ?: "",
                        revision = "",
                        name = name,
                        description = description ?: "",
                        pictureId = "",
                        isCollaborative = isCollaborative,
                        isDeletedByOwner = false,
                        timestamp = System.currentTimeMillis(),
                    )
                )
                libraryEvents.notifyChanged()
                _result.tryEmit(Result.success(playlistId))
            } catch (e: Exception) {
                _result.tryEmit(Result.failure(e))
            }
        }
    }

    fun modifyPlaylist(
        playlistId: String,
        name: String,
        description: String?,
        isPublic: Boolean?,
        isCollaborative: Boolean
    ) {
        viewModelScope.launch {
            try {
                val status = withContext(Dispatchers.IO) {
                    spClient.modifyPlaylist(
                        playlistId,
                        name,
                        description ?: "",
                        isPublic,
                        isCollaborative
                    )
                }
                if (status == 200) {
                    playlistDao.upsertPlaylist(
                        PlaylistEntity(
                            id = playlistId,
                            uri = "spotify:playlist:$playlistId",
                            ownerUsername = withContext(Dispatchers.IO) { spClient.username() } ?: "",
                            revision = "",
                            name = name,
                            description = description ?: "",
                            pictureId = "",
                            isCollaborative = isCollaborative,
                            isDeletedByOwner = false,
                            timestamp = System.currentTimeMillis(),
                        )
                    )
                    _result.tryEmit(Result.success(playlistId))
                } else {
                    Log.w("CreatePlaylistViewModel", "Failed to modify with status code: $status")
                    val message = when (status) {
                        403 -> "You don't have permission to modify this playlist"
                        else -> "Failed to modify playlist (status: $status)"
                    }
                    _result.tryEmit(Result.failure(RuntimeException(message)))
                }
            } catch (e: Exception) {
                _result.tryEmit(Result.failure(e))
            }
        }
    }
}
