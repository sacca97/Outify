package cc.tomko.outify.ui.viewmodel.player

import org.junit.Assert.assertEquals
import org.junit.Test

class QueueViewModelTest {

    // Queue of 3 previous entries, the current one, then n, n1, n2, n3
    private val next = listOf("n0", "n1", "n2", "n3")

    @Test
    fun everythingAfterTheLoadedWindowIsUnloaded() {
        // Indexes 0..4 are loaded: 3 previous, the current one and n0.
        assertEquals(listOf("n1", "n2", "n3"), nextTailAfter(next, previousCount = 3, loadedEnd = 5))
    }

    @Test
    fun nothingIsUnloadedWhenTheWindowReachesTheEnd() {
        assertEquals(emptyList<String>(), nextTailAfter(next, previousCount = 3, loadedEnd = 8))
    }

    @Test
    fun windowEndingBeforeTheNextEntriesLeavesAllOfThemUnloaded() {
        assertEquals(next, nextTailAfter(next, previousCount = 3, loadedEnd = 2))
    }

    @Test
    fun windowEndingOnTheCurrentEntryLeavesAllOfThemUnloaded() {
        assertEquals(next, nextTailAfter(next, previousCount = 3, loadedEnd = 4))
    }
}
