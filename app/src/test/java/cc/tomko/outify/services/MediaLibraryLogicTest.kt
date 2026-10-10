package cc.tomko.outify.services

import cc.tomko.outify.core.model.OutifyUri
import cc.tomko.outify.core.model.SpotifyUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TRACK = "spotify:track:abc"

class PlayIdTest {

    @Test
    fun `builds the id from context and track`() {
        assertEquals("play|spotify:album:a1|spotify:track:abc", playIdOf("spotify:album:a1", TRACK))
        assertEquals("play|-|spotify:track:abc", playIdOf(NO_CONTEXT, TRACK))
    }

    @Test
    fun `round trips an album context`() {
        val id = parsePlayId(playIdOf(playContextOf(SpotifyUri.Album("a1")), TRACK))!!

        assertEquals(SpotifyUri.Album("a1"), id.context)
        assertEquals(TRACK, id.trackUri)
    }

    @Test
    fun `round trips a playlist context`() {
        val id = parsePlayId(playIdOf(playContextOf(SpotifyUri.Playlist("p1")), TRACK))!!

        assertEquals(SpotifyUri.Playlist("p1"), id.context)
    }

    @Test
    fun `no context means the track plays by itself`() {
        val id = parsePlayId(playIdOf(playContextOf(null), TRACK))!!

        assertNull(id.context)
        assertEquals(TRACK, id.trackUri)
    }

    @Test
    fun `the liked folder id and the liked uri both mean the liked songs`() {
        assertEquals(OutifyUri.Liked, parsePlayId("play|liked|$TRACK")!!.context)
        assertEquals(OutifyUri.Liked, parsePlayId(playIdOf(playContextOf(OutifyUri.Liked), TRACK))!!.context)
    }

    @Test
    fun `an unknown prefix is not a play id`() {
        assertNull(parsePlayId("queue|-|$TRACK"))
        assertNull(parsePlayId("Play|-|$TRACK"))
        assertNull(parsePlayId(TRACK))
        assertNull(parsePlayId(""))
    }

    @Test
    fun `missing parts are rejected instead of crashing`() {
        assertNull(parsePlayId("play|"))
        assertNull(parsePlayId("play|-"))
        assertNull(parsePlayId("play|-|"))
        assertNull(parsePlayId("play||$TRACK"))
    }

    @Test
    fun `a separator inside the track stays part of the track`() {
        assertEquals("spotify:local:a|b", parsePlayId("play|-|spotify:local:a|b")!!.trackUri)
    }
}

class ContextUriTest {

    @Test
    fun `turns a folder id into a spotify uri`() {
        assertEquals("spotify:playlist:p1", contextUriOf("playlist:p1"))
        assertEquals("spotify:album:a1", contextUriOf("album:a1"))
    }

    @Test
    fun `keeps colons inside the id`() {
        assertEquals("spotify:playlist:a:b", contextUriOf("playlist:a:b"))
    }

    @Test
    fun `a folder id without an id has no uri`() {
        assertNull(contextUriOf("playlist"))
        assertNull(contextUriOf("playlist:"))
        assertNull(contextUriOf(""))
    }
}

class PageOfTest {

    private val items = (1..10).toList()

    @Test
    fun `returns the requested page`() {
        assertEquals(listOf(1, 2, 3), items.pageOf(0, 3))
        assertEquals(listOf(4, 5, 6), items.pageOf(1, 3))
    }

    @Test
    fun `the last page can be short`() {
        assertEquals(listOf(10), items.pageOf(3, 3))
    }

    @Test
    fun `a page past the end is empty`() {
        assertEquals(emptyList<Int>(), items.pageOf(4, 3))
        assertEquals(emptyList<Int>(), items.pageOf(Int.MAX_VALUE, 100))
        assertEquals(emptyList<Int>(), emptyList<Int>().pageOf(0, 10))
    }

    @Test
    fun `a size of zero or less still returns one item`() {
        assertEquals(listOf(1), items.pageOf(0, 0))
        assertEquals(listOf(2), items.pageOf(1, -5))
    }

    @Test
    fun `a huge size is limited to 100 items`() {
        val many = (1..250).toList()

        assertEquals(100, many.pageOf(0, Int.MAX_VALUE).size)
        assertEquals((101..200).toList(), many.pageOf(1, Int.MAX_VALUE))
        assertEquals((201..250).toList(), many.pageOf(2, 1_000))
    }

    @Test
    fun `a negative page is empty`() {
        assertEquals(emptyList<Int>(), items.pageOf(-1, 3))
    }
}

class ResumeTest {

    @Test
    fun `nothing saved means nothing to restore`() {
        assertNull(restoreLastPlayed(null, "spotify:album:a1", 9_000L))
        assertNull(restoreLastPlayed("", null, null))
        assertNull(restoreLastPlayed("  ", null, null))
    }

    @Test
    fun `restores track context and position`() {
        val last = restoreLastPlayed(TRACK, "spotify:album:a1", 42_000L)!!

        assertEquals(TRACK, last.trackUri)
        assertEquals(SpotifyUri.Album("a1"), last.context)
        assertEquals(42_000L, last.positionMs)
    }

    @Test
    fun `a missing context or position falls back to none and zero`() {
        val last = restoreLastPlayed(TRACK, null, null)!!

        assertNull(last.context)
        assertEquals(0L, last.positionMs)
    }

    @Test
    fun `a negative position is clamped to zero`() {
        assertEquals(0L, restoreLastPlayed(TRACK, null, -1L)!!.positionMs)
    }

    @Test
    fun `seeks only from 5 seconds on`() {
        assertFalse(needsSeek(0L))
        assertFalse(needsSeek(4_999L))
        assertTrue(needsSeek(5_000L))
        assertTrue(needsSeek(120_000L))
    }

    @Test
    fun `a player that still holds the track only goes on`() {
        assertFalse(needsLoad(TRACK, TRACK, runtimeUsable = true))
    }

    @Test
    fun `loads when another track plays or nothing does`() {
        assertTrue(needsLoad("spotify:track:other", TRACK, runtimeUsable = true))
        assertTrue(needsLoad(null, TRACK, runtimeUsable = true))
    }

    @Test
    fun `loads when the runtime is down even if the track matches`() {
        assertTrue(needsLoad(TRACK, TRACK, runtimeUsable = false))
    }

    @Test
    fun `a blank query resumes the last track in its context at its position`() {
        val last = restoreLastPlayed(TRACK, "spotify:playlist:p1", 30_000L)!!

        val target = resumeTarget(last)

        assertEquals(SpotifyUri.Playlist("p1"), target.context)
        assertEquals(SpotifyUri.Track("abc"), target.track)
        assertEquals(30_000L, target.seekMs)
    }

    @Test
    fun `a blank query without history plays the liked songs from the start`() {
        val target = resumeTarget(null)

        assertEquals(OutifyUri.Liked, target.context)
        assertNull(target.track)
        assertEquals(0L, target.seekMs)
    }

    @Test
    fun `a blank query resumes a track that was played alone`() {
        val target = resumeTarget(restoreLastPlayed(TRACK, null, 0L))

        assertNull(target.context)
        assertNotNull(target.track)
    }
}

class SearchPicksTest {

    private fun uris(prefix: String, count: Int) = (1..count).map { "$prefix$it" }

    @Test
    fun `keeps 20 tracks and 5 albums and playlists`() {
        val picks = pickSearchResults(
            uris(TRACK_PREFIX, 30) + uris(ALBUM_PREFIX, 12) + uris(PLAYLIST_PREFIX, 9)
        )

        assertEquals(uris(TRACK_PREFIX, 20), picks.tracks)
        assertEquals(uris(ALBUM_PREFIX, 5), picks.albums)
        assertEquals(uris(PLAYLIST_PREFIX, 5), picks.playlists)
    }

    @Test
    fun `keeps the order the search returned`() {
        val picks = pickSearchResults(
            listOf("${ALBUM_PREFIX}1", "${TRACK_PREFIX}2", "${TRACK_PREFIX}1", "${ALBUM_PREFIX}0")
        )

        assertEquals(listOf("${TRACK_PREFIX}2", "${TRACK_PREFIX}1"), picks.tracks)
        assertEquals(listOf("${ALBUM_PREFIX}1", "${ALBUM_PREFIX}0"), picks.albums)
    }

    @Test
    fun `drops artists and anything else`() {
        val picks = pickSearchResults(
            listOf("spotify:artist:x", "spotify:show:y", "spotify:episode:z", "garbage", "")
        )

        assertTrue(picks.tracks.isEmpty())
        assertTrue(picks.albums.isEmpty())
        assertTrue(picks.playlists.isEmpty())
    }

    @Test
    fun `no results gives empty lists`() {
        val picks = pickSearchResults(emptyList())

        assertTrue(picks.tracks.isEmpty() && picks.albums.isEmpty() && picks.playlists.isEmpty())
    }
}
