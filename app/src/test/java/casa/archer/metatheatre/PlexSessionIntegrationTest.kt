package casa.archer.metatheatre

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1600dp-h900dp-mdpi")
class PlexSessionIntegrationTest {
    @get:Rule val compose = createComposeRule()
    private var model: TheatreModel? = null
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun prepare() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context.getSharedPreferences("theatre", 0).edit().clear().commit()
        context.getSharedPreferences("theatre_state", 0).edit().clear().commit()
    }
    @After fun release() { model?.release(); Dispatchers.resetMain() }

    @Test fun savedTokenAndDeviceIdentitySurviveNewClientAndModelInstances() {
        val first = PlexClient(context)
        first.token = "synthetic-session-token"
        val second = PlexClient(context)
        assertEquals(first.clientId, second.clientId)
        assertEquals("synthetic-session-token", second.token)
        val m = fixture(second)
        m.release()
        assertEquals("synthetic-session-token", PlexClient(context).token)
    }

    @Test fun renderedSignOutPromptRequiresConfirmationAndCancelPreservesSession() {
        val plex = PlexClient(context).apply { token = "synthetic-session-token" }
        val m = fixture(plex)
        compose.setContent { Box(Modifier.requiredSize(1600.dp, 900.dp)) { LibraryPanel(m, player) } }
        compose.runOnIdle { m.requestSignOut() }
        compose.onNodeWithText("Sign out of Plex?").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(m.signOutConfirmationPress(0.95f, 0.05f))
            assertEquals("synthetic-session-token", plex.token)
        }
        press("Keep signed in", m)
        compose.runOnIdle {
            assertFalse(m.signOutConfirmation.value)
            assertEquals("synthetic-session-token", PlexClient(context).token)
            m.requestSignOut()
        }
        press("Sign out", m)
        compose.runOnIdle {
            assertNull(PlexClient(context).token)
            assertEquals(Browse.SignedOut, m.browse.value)
        }
    }

    @Test fun backCancelsConfirmationAndAnUnrequestedConfirmCannotClearCredentials() {
        val plex = PlexClient(context).apply { token = "synthetic-session-token" }
        val m = fixture(plex)
        m.confirmSignOut()
        assertNotNull(plex.token)
        m.requestSignOut()
        m.back()
        assertFalse(m.signOutConfirmation.value)
        assertNotNull(plex.token)
    }

    @Test fun hidingPreservesTheMovieAndBrowsingAndRestoringDoesNotCloseOrReopenMedia() {
        val plex = PlexClient(context).apply { token = "synthetic-session-token" }
        val m = fixture(plex)
        m.libraryOpen.value = false
        val browse = m.browse.value
        val visibility = mutableListOf<Boolean>()
        m.onScreenHidden = visibility::add
        m.setScreenHidden(true)
        m.setScreenHidden(true)
        assertEquals(browse, m.browse.value)
        assertFalse(m.libraryOpen.value)
        m.requestSignOut()
        assertFalse(m.signOutConfirmation.value)
        verify(player).setScreenHidden(true)
        verify(player, never()).close()
        m.setScreenHidden(false)
        verify(player).setScreenHidden(false)
        assertEquals(listOf(true, false), visibility)
        assertNotNull(plex.token)
    }

    private lateinit var player: ScreenPlayer
    private fun fixture(plex: PlexClient): TheatreModel {
        player = mock(ScreenPlayer::class.java)
        `when`(player.transport).thenReturn(MutableStateFlow(Transport(title = "Test movie")))
        return TheatreModel(context, player, plex, Dispatchers.Unconfined).also {
            model = it
            it.browse.value = Browse.Home(emptyList()) {}
        }
    }
    private fun press(label: String, model: TheatreModel) {
        val bounds = compose.onNodeWithText(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { assertTrue(model.signOutConfirmationPress(
            (bounds.center.x - root.left) / root.width, (bounds.center.y - root.top) / root.height)) }
    }
}
