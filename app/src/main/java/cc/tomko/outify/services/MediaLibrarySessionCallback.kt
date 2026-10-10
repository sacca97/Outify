package cc.tomko.outify.services

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MediaMetadata.MEDIA_TYPE_MUSIC
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import cc.tomko.outify.ALBUM_COVER_URL
import cc.tomko.outify.MediaSessionConstants
import cc.tomko.outify.R
import cc.tomko.outify.core.SpClient
import cc.tomko.outify.core.model.CoverSize
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.core.model.getCover
import cc.tomko.outify.core.model.OutifyUri
import cc.tomko.outify.core.spirc.SpircController
import cc.tomko.outify.core.spirc.SpircWrapper
import cc.tomko.outify.data.metadata.Metadata
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.qualifiers.ApplicationContext
import jakarta.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.coroutineScope

@UnstableApi
class MediaLibrarySessionCallback @Inject constructor(
    @ApplicationContext private val context: Context,
    private val spClient: SpClient,
    private val spircController: SpircController,
    private val spirc: SpircWrapper,
    private val metadata: Metadata,
) : MediaLibraryService.MediaLibrarySession.Callback {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** Browsing reads the database and the network, so it must stay off the main thread. */
    private val browseScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    lateinit var service: PlaybackService
    var toggleLike: () -> Unit = {}
    var toggleStartRadio: () -> Unit = {}
    var toggleRepeatMode: () -> Unit = {}

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo
    ): MediaSession.ConnectionResult {
        val connectionResult = super.onConnect(session, controller)
        return MediaSession.ConnectionResult.accept(
            connectionResult.availableSessionCommands
                .buildUpon()
                .add(MediaSessionConstants.CommandToggleLike)
                .add(MediaSessionConstants.CommandToggleStartRadio)
                .add(MediaSessionConstants.CommandToggleShuffle)
                .add(MediaSessionConstants.CommandToggleRepeatMode)
                .build(),
            connectionResult.availablePlayerCommands
        )
    }

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        // A play request on a session without items lands here too, such as right
        // after a browsed track was started. Only a runtime that is down needs a restart.
        if (!spirc.isUsable) {
            scope.launch {
                spircController.restart()
            }
        }
        return super.onPlaybackResumption(mediaSession, controller, isForPlayback)
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle
    ): ListenableFuture<SessionResult> {
        when (customCommand.customAction) {
            MediaSessionConstants.ACTION_TOGGLE_LIKE -> toggleLike()
            MediaSessionConstants.ACTION_TOGGLE_START_RADIO -> toggleStartRadio()
            MediaSessionConstants.ACTION_TOGGLE_REPEAT_MODE -> toggleRepeatMode()
        }
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    override fun onGetLibraryRoot(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val rootItem = folder(PlaybackService.ROOT, "Outify")
        return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
    }

    override fun onGetChildren(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = browseScope.future {
        val children = try {
            when {
                parentId == PlaybackService.ROOT -> getRootChildren()
                parentId == PlaybackService.LIKED -> getLikedTracks(page, pageSize)
                parentId == PlaybackService.PLAYLIST -> getPlaylists(page, pageSize)
                parentId == PlaybackService.ALBUM -> getAlbums(page, pageSize)
                parentId.startsWith("${PlaybackService.PLAYLIST}:") ->
                    getContextTracks(parentId, page, pageSize) {
                        metadata.getPlaylistMetadata(it, allowCached = true)
                            ?.contents?.map { item -> item.uri }
                    }

                parentId.startsWith("${PlaybackService.ALBUM}:") ->
                    getContextTracks(parentId, page, pageSize) {
                        metadata.getAlbumMetadata(it)?.tracks
                    }

                else -> emptyList()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to browse $parentId", e)
            return@future LibraryResult.ofError<ImmutableList<MediaItem>>(SessionError.ERROR_UNKNOWN)
        }

        LibraryResult.ofItemList(children, params)
    }

    override fun onGetItem(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val item = service.player.currentMediaItem
        return if (item != null && item.mediaId == mediaId) {
            Futures.immediateFuture(LibraryResult.ofItem(item, null))
        } else {
            Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_UNKNOWN))
        }
    }

    /**
     * A browsed track carries the context it was listed in, so playing it plays
     * on from there. Spotify's player queues the rest, nothing is queued here.
     */
    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val requested = mediaItems.getOrNull(startIndex.coerceAtLeast(0)) ?: mediaItems.firstOrNull()
        val (context, track) = requested?.let { parsePlayId(it.mediaId) } ?: (null to null)
        if (track != null) {
            scope.launch(Dispatchers.IO) {
                // A car connecting while the app is still starting asks before the session is up.
                withTimeoutOrNull(READY_TIMEOUT_MS) { spirc.isUsableFlow.first { it } }
                spirc.load(context, OutifyUri.fromUriString(track))
            }
        } else {
            Log.w(TAG, "Don't know how to play ${requested?.mediaId}")
        }

        // The Spotify player reports what it plays, the session has no queue of its own.
        return Futures.immediateFuture(
            MediaSession.MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET)
        )
    }

    private fun getRootChildren(): List<MediaItem> = listOf(
        folder(PlaybackService.LIKED, context.getString(R.string.common_liked_songs)),
        folder(PlaybackService.PLAYLIST, context.getString(R.string.search_section_playlists)),
        folder(PlaybackService.ALBUM, context.getString(R.string.search_section_albums)),
    )

    private suspend fun getLikedTracks(page: Int, pageSize: Int): List<MediaItem> {
        val uris = metadata.getLikedUris().pageOf(page, pageSize)
        return tracksOf(uris).map { it.toMediaItem(PlaybackService.LIKED) }
    }

    private suspend fun getContextTracks(
        parentId: String,
        page: Int,
        pageSize: Int,
        trackUris: suspend (contextUri: String) -> List<String>?,
    ): List<MediaItem> {
        val contextUri = contextUriOf(parentId)
        val uris = (trackUris(contextUri) ?: return emptyList())
            .filter { it.startsWith(TRACK_PREFIX) }
            .pageOf(page, pageSize)
        return tracksOf(uris).map { it.toMediaItem(contextUri) }
    }

    private suspend fun tracksOf(uris: List<String>): List<Track> {
        val byUri = metadata.getTrackMetadata(uris).associateBy { it.uri }
        return uris.mapNotNull { byUri[it] }
    }

    private suspend fun getPlaylists(page: Int, pageSize: Int): List<MediaItem> {
        val uris = metadata.getPlaylistUris()
            .filter { it.startsWith("spotify:playlist:") }
            .pageOf(page, pageSize)

        // Playlists never opened are not cached yet, a few at a time keeps this quick.
        val limit = Semaphore(PLAYLIST_FETCHES)
        return coroutineScope {
            uris.map { uri ->
                async {
                    val playlist = limit.withPermit {
                        runCatching { metadata.getPlaylistMetadata(uri, allowCached = true) }.getOrNull()
                    } ?: return@async null
                    folder(
                        mediaId = "${PlaybackService.PLAYLIST}:${playlist.id}",
                        title = playlist.attributes.name,
                        artworkUrl = runCatching { playlist.getCover(metadata, CoverSize.MEDIUM) }.getOrNull(),
                    )
                }
            }.awaitAll().filterNotNull()
        }
    }

    private suspend fun getAlbums(page: Int, pageSize: Int): List<MediaItem> {
        val uris = spClient.getSavedItems(SpClient.ALBUMS)
            .split(",")
            .filter { it.startsWith("spotify:album:") }
            .pageOf(page, pageSize)

        val limit = Semaphore(PLAYLIST_FETCHES)
        return coroutineScope {
            uris.map { uri ->
                async {
                    val album = limit.withPermit {
                        runCatching { metadata.getAlbumMetadata(uri) }.getOrNull()
                    } ?: return@async null
                    folder(
                        mediaId = "${PlaybackService.ALBUM}:${album.id}",
                        title = album.name,
                        subtitle = album.artists.joinToString { it.name },
                        artworkUrl = album.getCover(CoverSize.LARGE)?.let { ALBUM_COVER_URL + it.uri },
                    )
                }
            }.awaitAll().filterNotNull()
        }
    }

    private fun folder(
        mediaId: String,
        title: String,
        subtitle: String? = null,
        artworkUrl: String? = null,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(mediaId)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setIsPlayable(false)
                .setIsBrowsable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtworkUri(artworkUrl?.toUri())
                .build()
        )
        .build()

    private companion object {
        const val TAG = "MediaLibrary"
        const val TRACK_PREFIX = "spotify:track:"
        const val PLAY_PREFIX = "play|"
        const val PLAYLIST_FETCHES = 6
        const val READY_TIMEOUT_MS = 20_000L

        /** Page `page` of `pageSize` items, the whole list for an unbounded size. */
        fun <T> List<T>.pageOf(page: Int, pageSize: Int): List<T> {
            val size = pageSize.coerceIn(1, MAX_PAGE_SIZE)
            return drop(page * size).take(size)
        }

        const val MAX_PAGE_SIZE = 100

        /** `playlist:<id>` or `album:<id>` as the Spotify URI of that context. */
        fun contextUriOf(folderId: String): String {
            val (kind, id) = folderId.split(":", limit = 2)
            return "spotify:$kind:$id"
        }

        /** The context and the track out of an id made by [toMediaItem], context null for liked songs. */
        fun parsePlayId(mediaId: String): Pair<OutifyUri?, String?> {
            if (!mediaId.startsWith(PLAY_PREFIX)) return null to null
            val (context, track) = mediaId.removePrefix(PLAY_PREFIX).split("|", limit = 2)
            val contextUri = if (context == PlaybackService.LIKED) {
                OutifyUri.Liked
            } else {
                OutifyUri.fromUriString(context)
            }
            return contextUri to track
        }
    }
}

private fun Track.toMediaItem(context: String, isPlayable: Boolean = true, isBrowsable: Boolean = false) =
    MediaItem.Builder()
        .setMediaId("play|$context|$uri")
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(name)
                .setSubtitle(artists.joinToString { it.name })
                .setArtist(artists.joinToString { it.name })
                .setAlbumTitle(album?.name)
                .setArtworkUri(album?.getCover(CoverSize.LARGE)?.let { ALBUM_COVER_URL + it.uri }
                    ?.toUri())
                .setIsPlayable(isPlayable)
                .setIsBrowsable(isBrowsable)
                .setMediaType(MEDIA_TYPE_MUSIC)
                .build()
        )
        .build()
