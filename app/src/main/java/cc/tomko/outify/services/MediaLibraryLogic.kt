package cc.tomko.outify.services

import cc.tomko.outify.core.model.OutifyUri

// Pure helpers of MediaLibrarySessionCallback, kept free of Android types so they run in unit tests.

internal const val TRACK_PREFIX = "spotify:track:"
internal const val PLAYLIST_PREFIX = "spotify:playlist:"
internal const val ALBUM_PREFIX = "spotify:album:"

private const val PLAY_PREFIX = "play|"

/** Stands in for the context of a track that plays by itself. */
internal const val NO_CONTEXT = "-"

internal const val SEARCH_TRACKS = 20
internal const val SEARCH_FOLDERS = 5

/** Below this a restored track just starts over, a seek is not worth it. */
internal const val MIN_RESUME_POSITION_MS = 5_000L

private const val MAX_PAGE_SIZE = 100

/**
 * Page [page] of [pageSize] items. The size is limited because a page costs one metadata
 * request per item, and a host that wants everything (Int.MAX_VALUE) gets the first 100.
 */
internal fun <T> List<T>.pageOf(page: Int, pageSize: Int): List<T> {
    val size = pageSize.coerceIn(1, MAX_PAGE_SIZE)
    val start = page.toLong() * size
    if (start < 0 || start >= this.size) return emptyList()
    return drop(start.toInt()).take(size)
}

/** `playlist:<id>` or `album:<id>` as the Spotify URI of that context, null if there is no id. */
internal fun contextUriOf(folderId: String): String? {
    val (kind, id) = folderId.split(":", limit = 2).let { it[0] to it.getOrNull(1) }
    if (id.isNullOrEmpty()) return null
    return "spotify:$kind:$id"
}

/** What a media id made by [playIdOf] stands for, context null when the track plays by itself. */
internal class PlayId(val context: OutifyUri?, val trackUri: String)

internal fun playIdOf(context: String, trackUri: String): String = "$PLAY_PREFIX$context|$trackUri"

/** The context part of a play id, for a browse folder or for the context a track was last played in. */
internal fun playContextOf(context: OutifyUri?): String = context?.toUriString() ?: NO_CONTEXT

/** Null when the id is not one of ours, or lacks its context or track. */
internal fun parsePlayId(mediaId: String): PlayId? {
    if (!mediaId.startsWith(PLAY_PREFIX)) return null
    val parts = mediaId.removePrefix(PLAY_PREFIX).split("|", limit = 2)
    val context = parts[0]
    val trackUri = parts.getOrNull(1)
    if (context.isEmpty() || trackUri.isNullOrEmpty()) return null

    val contextUri = when (context) {
        NO_CONTEXT -> null
        PlaybackService.LIKED -> OutifyUri.Liked
        else -> OutifyUri.fromUriString(context)
    }
    return PlayId(contextUri, trackUri)
}

/** The last track that played, with the context it was started from and how far it got. */
internal class LastPlayed(val trackUri: String, val context: OutifyUri?, val positionMs: Long)

internal fun restoreLastPlayed(trackUri: String?, contextUri: String?, positionMs: Long?): LastPlayed? {
    if (trackUri.isNullOrBlank()) return null
    return LastPlayed(
        trackUri,
        contextUri?.let { OutifyUri.fromUriString(it) },
        (positionMs ?: 0L).coerceAtLeast(0L),
    )
}

/** A player that still holds the track only needs to go on, anything else must be loaded. */
internal fun needsLoad(playingUri: String?, resumeUri: String, runtimeUsable: Boolean): Boolean =
    playingUri != resumeUri || !runtimeUsable

/** A load always starts a track from its beginning, so going back to where it was takes a seek. */
internal fun needsSeek(positionMs: Long): Boolean = positionMs >= MIN_RESUME_POSITION_MS

/** What to load for "play" without anything said, and where to seek once it plays. */
internal data class PlayTarget(val context: OutifyUri?, val track: OutifyUri?, val seekMs: Long)

/** Goes on with what played last, or with the liked songs if nothing did. */
internal fun resumeTarget(last: LastPlayed?): PlayTarget =
    if (last == null) PlayTarget(OutifyUri.Liked, null, 0L)
    else PlayTarget(last.context, OutifyUri.fromUriString(last.trackUri), last.positionMs)

/** The search results worth showing: many tracks, few albums and playlists. */
internal class SearchPicks(
    val tracks: List<String>,
    val albums: List<String>,
    val playlists: List<String>,
)

internal fun pickSearchResults(uris: List<String>) = SearchPicks(
    tracks = uris.filter { it.startsWith(TRACK_PREFIX) }.take(SEARCH_TRACKS),
    albums = uris.filter { it.startsWith(ALBUM_PREFIX) }.take(SEARCH_FOLDERS),
    playlists = uris.filter { it.startsWith(PLAYLIST_PREFIX) }.take(SEARCH_FOLDERS),
)
