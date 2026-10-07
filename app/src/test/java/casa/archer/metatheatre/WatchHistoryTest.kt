package casa.archer.metatheatre

import org.junit.Assert.*
import org.junit.Test

class WatchHistoryTest {
    private fun bookmark(id: String = "1", server: String = "a", played: Long = 1L, position: Long = 20_000L,
                         completed: Boolean = false) = Bookmark(MovieKey(server, id), "Movie", "", "movie", "/library/metadata/$id/thumb",
        position, 100_000L, played, completed)

    @Test fun mostRecentlyStartedMovieComesFirstAndProgressDoesNotReorder() {
        val history = WatchHistory()
        history.begin(bookmark("1", played = 100))
        history.begin(bookmark("2", played = 200))
        history.progress(MovieKey("a", "1"), 30_000, 100_000, false)
        assertEquals(listOf("2", "1"), history.recent().map { it.key.ratingKey })
        assertEquals(30_000L, history.find(MovieKey("a", "1"))!!.resumeMs)
    }
    @Test fun appRestartRestoresActiveBookmarkButDeliberateCloseKeepsHistoryAndClearsAutoResume() {
        val history = WatchHistory(); history.begin(bookmark())
        val reopened = WatchHistory(history.recent(), history.active)
        assertEquals(MovieKey("a", "1"), reopened.active)
        assertEquals(20_000L, reopened.find(reopened.active!!)!!.resumeMs)
        reopened.leavePlayer()
        assertNull(WatchHistory(reopened.recent(), reopened.active).active)
        assertEquals(20_000L, reopened.recent().first().resumeMs)
    }
    @Test fun sameRatingKeyOnDifferentServersKeepsSeparateBookmarks() {
        val history = WatchHistory(); history.begin(bookmark(server = "a")); history.begin(bookmark(server = "b", position = 40_000))
        assertEquals(2, history.recent().size)
        assertEquals(20_000L, history.find(MovieKey("a", "1"))!!.resumeMs)
        assertEquals(40_000L, history.find(MovieKey("b", "1"))!!.resumeMs)
    }
    @Test fun completedMovieReplaysFromBeginningAndCannotAutoResume() {
        val history = WatchHistory(); history.begin(bookmark())
        history.progress(MovieKey("a", "1"), 100_000, 100_000, true)
        assertEquals(0L, history.recent().first().resumeMs)
        assertNull(history.active)
        assertNull(WatchHistory(history.recent(), MovieKey("a", "1")).active)
    }
    @Test fun unknownDurationSurvivesAndResumeCannotSeekBeyondKnownDuration() {
        val history = WatchHistory(); history.begin(bookmark())
        history.progress(MovieKey("a", "1"), 200_000, 0, false)
        assertEquals(100_000L, history.recent().first().durationMs)
        assertEquals(99_000L, history.recent().first().resumeMs)
        assertEquals(0L, bookmark(position = -1).resumeMs)
        assertEquals(0L, bookmark(position = 20_000).copy(durationMs = 100).resumeMs)
    }
    @Test fun remoteHistoryCannotOverwriteNewerDeviceBookmark() {
        val history = WatchHistory(); history.begin(bookmark(played = 200, position = 35_000))
        history.importRecent(listOf(bookmark(played = 100, position = 15_000)))
        assertEquals(35_000L, history.recent().first().resumeMs)
        history.importRecent(listOf(bookmark(played = 300, position = 45_000)))
        assertEquals(45_000L, history.recent().first().resumeMs)
    }
    @Test fun historyIsBoundedAndSignOutClearsIt() {
        val history = WatchHistory()
        repeat(120) { history.begin(bookmark(it.toString(), played = it.toLong() + 1)) }
        assertEquals(100, history.recent().size)
        assertEquals("119", history.recent().first().key.ratingKey)
        assertNull(history.find(MovieKey("a", "0")))
        history.clear(); assertTrue(history.recent().isEmpty()); assertNull(history.active)
    }
}
