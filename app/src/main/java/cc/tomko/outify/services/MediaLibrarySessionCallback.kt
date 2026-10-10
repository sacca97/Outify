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
import cc.tomko.outify.core.model.OutifyUri
import cc.tomko.outify.core.model.getCover
import cc.tomko.outify.core.model.toPlayableAudio
import cc.tomko.outify.core.spirc.SpircController
import cc.tomko.outify.core.spirc.SpircWrapper
import cc.tomko.outify.data.dao.PlaylistDao
import cc.tomko.outify.data.metadata.Metadata
import cc.tomko.outify.data.repository.LikedRepository
import cc.tomko.outify.data.repository.SettingsRepository
import cc.tomko.outify.playback.PlaybackStateHolder
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
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

@UnstableApi
class MediaLibrarySessionCallback @Inject constructor(
    @ApplicationContext private val context: Context,
    private val spClient: SpClient,
    private val spircController: SpircController,
    private val spirc: SpircWrapper,
    private val metadata: Metadata,
    private val settings: SettingsRepository,
    private val playbackStateHolder: PlaybackStateHolder,
    private val likedRepository: LikedRepository,
    private val playlistDao: PlaylistDao,
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

    /**
     * The car asks for what was playing last, either to show it or to play it.
     * The Spotify runtime may be down by then, such as after the app was killed.
     */
    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = browseScope.future {
        if (!spirc.isUsable) {
            scope.launch { spircController.restart() }
        }

        val last = lastPlayed()
            ?: throw UnsupportedOperationException("Nothing was played yet")

        if (playbackStateHolder.state.value.currentAudio == null) {
            // Shown paused, nothing plays until the car asks for it.
            playbackStateHolder.seekTo(last.positionMs.milliseconds)
            playbackStateHolder.updatePosition(last.positionMs)
            playbackStateHolder.setAudio(last.track.toPlayableAudio())
        }

        if (isForPlayback) {
            waitUntilReady()
            val playing = playbackStateHolder.state.value.currentAudio?.uri
            // A player that still holds the track only needs to go on, which the session does.
            if (playing != last.track.uri || !spirc.isUsable) {
                spirc.load(last.context, OutifyUri.fromUriString(last.track.uri))
                seekOnceLoaded(last.track.uri, last.positionMs)
            }
        }

        MediaSession.MediaItemsWithStartPosition(
            listOf(last.track.toMediaItem(last.contextId)), 0, last.positionMs
        )
    }

    private class LastPlayed(val track: Track, val context: OutifyUri?, val positionMs: Long) {
        val contextId: String get() = context?.toUriString() ?: NO_CONTEXT
    }

    private suspend fun lastPlayed(): LastPlayed? {
        val trackUri = settings.lastTrackUri.first() ?: return null
        val track = metadata.getTrackMetadata(listOf(trackUri)).firstOrNull() ?: return null
        val context = settings.lastContextUri.first()?.let { OutifyUri.fromUriString(it) }
        return LastPlayed(track, context, settings.lastPositionMs.first() ?: 0L)
    }

    /** A car connecting while the app is still starting asks before the session is up. */
    private suspend fun waitUntilReady() {
        withTimeoutOrNull(READY_TIMEOUT_MS) { spirc.isUsableFlow.first { it } }
    }

    /** A load always starts a track from its beginning, so going back to where it was takes a seek. */
    private suspend fun seekOnceLoaded(trackUri: String, positionMs: Long) {
        if (positionMs < MIN_RESUME_POSITION_MS) return
        withTimeoutOrNull(READY_TIMEOUT_MS) {
            playbackStateHolder.state.first { it.currentAudio?.uri == trackUri && it.isPlaying }
        } ?: return
        spirc.seekTo(positionMs)
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
        // Tells Android Auto to show its search button.
        val rootParams = MediaLibraryService.LibraryParams.Builder()
            .setExtras(Bundle().apply { putBoolean(EXTRA_SEARCH_SUPPORTED, true) })
            .build()
        return Futures.immediateFuture(LibraryResult.ofItem(rootItem, rootParams))
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

    /** The search box of the car: the results are ready when the host asks for them. */
    override fun onSearch(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> {
        browseScope.launch {
            val results = runCatching { searchResults(query) }.getOrDefault(emptyList())
            session.notifySearchResultChanged(browser, query, results.size, params)
        }
        return Futures.immediateFuture(LibraryResult.ofVoid())
    }

    override fun onGetSearchResult(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = browseScope.future {
        try {
            LibraryResult.ofItemList(searchResults(query).pageOf(page, pageSize), params)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to search for $query", e)
            LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
        }
    }

    /**
     * Plays what a controller asked for: a track listed while browsing, which plays on in the
     * context it was listed in, or something said to the voice assistant. Spotify's player
     * queues the rest, nothing is queued here.
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
        val spoken = requested?.requestMetadata?.searchQuery

        when {
            track != null -> scope.launch(Dispatchers.IO) {
                waitUntilReady()
                playTrack(context, track)
            }

            spoken != null -> scope.launch(Dispatchers.IO) { playFromSearch(spoken) }
            else -> Log.w(TAG, "Don't know how to play ${requested?.mediaId}")
        }

        // The Spotify player reports what it plays, the session has no queue of its own.
        return Futures.immediateFuture(
            MediaSession.MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET)
        )
    }

    /** Without a context the track plays by itself, a load would pick it out of the liked songs. */
    private fun playTrack(context: OutifyUri?, trackUri: String) {
        if (context == null) {
            spirc.setQueue(arrayOf(trackUri), trackUri)
        } else {
            spirc.load(context, OutifyUri.fromUriString(trackUri))
        }
    }

    /** "Play <something>" to the assistant. Nothing was said: go on with what played last. */
    private suspend fun playFromSearch(query: String) {
        waitUntilReady()
        if (query.isBlank()) {
            val last = lastPlayed()
            if (last != null) {
                spirc.load(last.context, OutifyUri.fromUriString(last.track.uri))
                seekOnceLoaded(last.track.uri, last.positionMs)
            } else {
                spirc.load(OutifyUri.Liked, null)
            }
            return
        }

        val uri = runCatching { spClient.search(query, SEARCH_TYPES).firstOrNull() }.getOrNull()
        if (uri == null) {
            Log.w(TAG, "Nothing found for \"$query\"")
            return
        }
        // A track plays by itself, anything else plays as the context it is.
        if (uri.startsWith(TRACK_PREFIX)) {
            playTrack(null, uri)
        } else {
            spirc.load(OutifyUri.fromUriString(uri), null)
        }
    }

    private suspend fun searchResults(query: String): List<MediaItem> {
        if (query.isBlank()) return emptyList()
        val uris = spClient.search(query, SEARCH_TYPES)?.toList().orEmpty()

        val tracks = uris.filter { it.startsWith(TRACK_PREFIX) }.take(SEARCH_TRACKS)
        val albums = uris.filter { it.startsWith("spotify:album:") }.take(SEARCH_FOLDERS)
        val playlists = uris.filter { it.startsWith("spotify:playlist:") }.take(SEARCH_FOLDERS)

        return coroutineScope {
            val albumItems = albums.map { async { albumFolder(it) } }
            val playlistItems = playlists.map { async { playlistFolder(it) } }
            tracksOf(tracks).map { it.toMediaItem(NO_CONTEXT) } +
                    albumItems.awaitAll().filterNotNull() +
                    playlistItems.awaitAll().filterNotNull()
        }
    }

    private fun getRootChildren(): List<MediaItem> = listOf(
        folder(PlaybackService.LIKED, context.getString(R.string.common_liked_songs)),
        folder(PlaybackService.PLAYLIST, context.getString(R.string.search_section_playlists)),
        folder(PlaybackService.ALBUM, context.getString(R.string.search_section_albums)),
    )

    /** Without a connection the lists come from what was synced before. */
    private suspend fun getLikedTracks(page: Int, pageSize: Int): List<MediaItem> {
        val uris = metadata.getLikedUris().ifEmpty { likedRepository.getCachedLikedUris() }
            .pageOf(page, pageSize)
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
        val uris = metadata.getPlaylistUris().ifEmpty { settings.cachedUris.first() }
            .filter { it.startsWith(PLAYLIST_PREFIX) }
            .pageOf(page, pageSize)

        // Names and covers that are already stored are enough for a list. Playlists
        // never opened have to be fetched, a few at a time keeps this quick.
        val stored = playlistDao.getPlaylists(uris.map { it.removePrefix(PLAYLIST_PREFIX) })
            .associateBy { it.id }
        val limit = Semaphore(METADATA_FETCHES)
        return coroutineScope {
            uris.map { uri ->
                async {
                    val entity = stored[uri.removePrefix(PLAYLIST_PREFIX)]
                    if (entity != null) {
                        playlistFolder(entity.id, entity.name, entity.pictureId.takeIf { it.isNotEmpty() }
                            ?.let { ALBUM_COVER_URL + it })
                    } else {
                        limit.withPermit { playlistFolder(uri) }
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    private suspend fun getAlbums(page: Int, pageSize: Int): List<MediaItem> {
        val uris = runCatching {
            spClient.getSavedItems(SpClient.ALBUMS).split(",")
        }.getOrDefault(emptyList())
            .filter { it.startsWith(ALBUM_PREFIX) }
            .ifEmpty { metadata.observeLikedAlbumUris().first() }
            .pageOf(page, pageSize)

        val limit = Semaphore(METADATA_FETCHES)
        return coroutineScope {
            uris.map { uri -> async { limit.withPermit { albumFolder(uri) } } }
                .awaitAll().filterNotNull()
        }
    }

    private suspend fun playlistFolder(uri: String): MediaItem? {
        val playlist = runCatching { metadata.getPlaylistMetadata(uri, allowCached = true) }
            .getOrNull() ?: return null
        return playlistFolder(
            playlist.id,
            playlist.attributes.name,
            runCatching { playlist.getCover(metadata, CoverSize.MEDIUM) }.getOrNull(),
        )
    }

    private fun playlistFolder(id: String, name: String, artworkUrl: String?): MediaItem =
        folder("${PlaybackService.PLAYLIST}:$id", name, artworkUrl = artworkUrl)

    private suspend fun albumFolder(uri: String): MediaItem? {
        val album = runCatching { metadata.getAlbumMetadata(uri) }.getOrNull() ?: return null
        return folder(
            mediaId = "${PlaybackService.ALBUM}:${album.id}",
            title = album.name,
            subtitle = album.artists.joinToString { it.name },
            artworkUrl = album.getCover(CoverSize.LARGE)?.let { ALBUM_COVER_URL + it.uri },
        )
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
        const val PLAYLIST_PREFIX = "spotify:playlist:"
        const val ALBUM_PREFIX = "spotify:album:"
        const val PLAY_PREFIX = "play|"
        const val NO_CONTEXT = "-"
        const val EXTRA_SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"
        const val SEARCH_TYPES = "track,artist,album,playlist"
        const val SEARCH_TRACKS = 20
        const val SEARCH_FOLDERS = 5
        const val METADATA_FETCHES = 6
        const val MIN_RESUME_POSITION_MS = 5_000L
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
            val contextUri = when (context) {
                NO_CONTEXT -> null
                PlaybackService.LIKED -> OutifyUri.Liked
                else -> OutifyUri.fromUriString(context)
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
