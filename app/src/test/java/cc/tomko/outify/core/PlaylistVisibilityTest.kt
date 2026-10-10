package cc.tomko.outify.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistVisibilityTest {

    @Test
    fun readsPublicAndPrivate() {
        assertEquals(true, Json.decodeFromString<PlaylistVisibility>("""{"public":true}""").public)
        assertEquals(false, Json.decodeFromString<PlaylistVisibility>("""{"public":false}""").public)
    }

    @Test
    fun nullMeansNeitherPublicNorPrivate() {
        assertEquals(null, Json.decodeFromString<PlaylistVisibility>("""{"public":null}""").public)
    }
}
