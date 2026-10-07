package casa.archer.metatheatre

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TheatreNavigationIntegrationTest {
    private val mainDispatcher = StandardTestDispatcher()
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val ioDispatcher = ioExecutor.asCoroutineDispatcher()

    @Before fun setMainDispatcher() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After fun resetMainDispatcher() {
        ioDispatcher.close()
        Dispatchers.resetMain()
    }

    @Test fun backCancelsDelayedBrowseAndIgnoresLateSuccess() = runTest(mainDispatcher) {
        delayedBrowseDoesNotChangeParentAfterBack(failure = null)
    }

    @Test fun backCancelsDelayedBrowseAndIgnoresLateError() = runTest(mainDispatcher) {
        delayedBrowseDoesNotChangeParentAfterBack(failure = IllegalStateException("late failure"))
    }

    @Test fun backAtRootRemainsANoOp() = runTest(mainDispatcher) {
        val model = model()
        try {
            model.start()
            runCurrent()
            assertEquals(Browse.SignedOut, model.browse.value)
            assertFalse(model.canGoBack.value)

            model.back()

            assertEquals(Browse.SignedOut, model.browse.value)
            assertFalse(model.canGoBack.value)
            assertFalse(model.loading.value)
        } finally {
            model.release()
        }
    }

    private suspend fun TestScope.delayedBrowseDoesNotChangeParentAfterBack(failure: Exception?) {
        val plex = mock(PlexClient::class.java)
        val player = mock(ScreenPlayer::class.java)
        org.mockito.Mockito.`when`(player.transport).thenReturn(MutableStateFlow(Transport()))
        val server = PlexServer("Test", "https://example.invalid", "token")
        val section = PlexSection("1", "Movies", "movie")
        val sectionsFinished = CountDownLatch(1)
        doAnswer {
            sectionsFinished.countDown()
            listOf(section)
        }.`when`(plex).sections(server)

        val libraryStarted = CountDownLatch(1)
        val finishLibrary = CountDownLatch(1)
        val libraryFinished = CountDownLatch(1)
        doAnswer {
            libraryStarted.countDown()
            try {
                check(finishLibrary.await(5, TimeUnit.SECONDS)) { "test did not release delayed Plex response" }
                if (failure != null) throw failure
                listOf(PlexItem("42", "Late movie", "", "movie"))
            } finally {
                libraryFinished.countDown()
            }
        }.`when`(plex).library(server, section)

        val model = model(plex = plex, player = player)
        try {
            model.start()
            runCurrent()
            assertEquals(Browse.SignedOut, model.browse.value)

            model.openServer(server)
            runCurrent()
            assertTrue(await(sectionsFinished))
            drainIo()
            runCurrent()
            assertTrue(model.browse.value is Browse.Sections)
            assertTrue(model.canGoBack.value)

            model.openSection(server, section)
            runCurrent()
            assertTrue(await(libraryStarted))
            assertTrue(model.loading.value)

            model.back()
            val parent = Browse.SignedOut
            assertEquals(parent, model.browse.value)
            assertFalse(model.loading.value)
            assertFalse(model.canGoBack.value)

            finishLibrary.countDown()
            assertTrue(await(libraryFinished))
            drainIo()
            runCurrent()
            advanceUntilIdle()

            assertEquals(parent, model.browse.value)
            assertFalse(model.loading.value)
            assertNull(model.error.value)
        } finally {
            finishLibrary.countDown()
            model.release()
        }
    }

    private fun model(
        plex: PlexClient = mock(PlexClient::class.java),
        player: ScreenPlayer = mock(ScreenPlayer::class.java).also {
            org.mockito.Mockito.`when`(it.transport).thenReturn(MutableStateFlow(Transport()))
        },
    ): TheatreModel = TheatreModel(
        RuntimeEnvironment.getApplication() as Context,
        player,
        plex,
        ioDispatcher,
    )

    private suspend fun drainIo() = withContext(Dispatchers.IO) {
        // The marker runs after the request coroutine has queued its Main continuation.
        ioExecutor.submit {}.get(5, TimeUnit.SECONDS)
    }

    private suspend fun await(latch: CountDownLatch): Boolean = withContext(Dispatchers.IO) {
        latch.await(5, TimeUnit.SECONDS)
    }
}
