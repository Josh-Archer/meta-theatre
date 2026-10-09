package casa.archer.metatheatre

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
@Config(sdk = [34], qualifiers = "w1600dp-h900dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class LibraryPanelIntegrationTest {
    @get:Rule val compose = createComposeRule()
    private var model: TheatreModel? = null

    @Before fun immediateModelWork() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun release() { model?.release(); Dispatchers.resetMain() }

    @Test fun renderedSceneControlsMatchQuestRayCoordinatesWithoutLinkingPlex() {
        val player = mock(ScreenPlayer::class.java)
        `when`(player.transport).thenReturn(MutableStateFlow(Transport()))
        val currentModel = TheatreModel(RuntimeEnvironment.getApplication(), player,
            mock(PlexClient::class.java), Dispatchers.Unconfined)
        model = currentModel
        val turns = mutableListOf<Float>()
        var returns = 0
        currentModel.onViewTurn = { turns.add(it) }
        currentModel.onVisitorsReturn = { returns++ }
        compose.setContent {
            Box(Modifier.requiredSize(1600.dp, 900.dp)) { LibraryPanel(currentModel, player) }
        }
        for (control in SceneControl.entries) {
            val bounds = compose.onNodeWithText(control.label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            compose.runOnIdle {
                assertTrue(currentModel.sceneControlPress(bounds.center.x / root.width, bounds.center.y / root.height))
            }
        }
        compose.runOnIdle {
            assertEquals(listOf(-45f, 45f), turns)
            assertEquals(1, returns)
            assertEquals(Browse.SignedOut, currentModel.browse.value)
            assertFalse(currentModel.sceneControlPress(.1f, .96f)) // Previous remains shelf navigation
            assertFalse(currentModel.sceneControlPress(.9f, .96f)) // Next remains shelf navigation
            assertFalse(currentModel.sceneControlPress(.52f, .5f)) // poster area cannot turn the view
        }
    }

    @Test fun renderedKeyboardKeysMatchQuestRayCoordinatesAndFilterTheShelf() {
        val context = RuntimeEnvironment.getApplication()
        val player = mock(ScreenPlayer::class.java)
        `when`(player.transport).thenReturn(MutableStateFlow(Transport()))
        val plex = mock(PlexClient::class.java)
        val server = PlexServer("Test", "https://example.invalid", "fake-token")
        val section = PlexSection("1", "Movies", "movie")
        `when`(plex.library(server, section)).thenReturn(listOf(
            PlexItem("1", "2001 A Space Odyssey", "", "movie"),
            PlexItem("2", "Moon", "", "movie"),
        ))
        val currentModel = TheatreModel(context, player, plex, Dispatchers.Unconfined)
        model = currentModel
        compose.setContent {
            Box(Modifier.requiredSize(1600.dp, 900.dp)) { LibraryPanel(currentModel, player) }
        }
        compose.runOnIdle { currentModel.openSection(server, section) }
        compose.waitUntil(5_000) { currentModel.browse.value is Browse.Items }
        val searchBounds = compose.onNodeWithText("Search titles").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        press(searchBounds, currentModel)

        for (character in "2001") {
            // The final match is the keyboard key; the query field can contain the same digit.
            val bounds = compose.onAllNodesWithText(character.toString()).fetchSemanticsNodes().last().boundsInRoot
            press(bounds, currentModel)
        }
        press(compose.onNodeWithText("Done").assertIsDisplayed().fetchSemanticsNode().boundsInRoot, currentModel)

        compose.runOnIdle {
            assertEquals("2001", currentModel.librarySearchQuery.value)
            assertFalse(currentModel.librarySearchKeyboardOpen.value)
            assertEquals("2001 A Space Odyssey", (currentModel.browse.value as Browse.Items).items.single().title)
        }
    }

    private fun press(bounds: Rect, model: TheatreModel) {
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            assertTrue(model.librarySearchPress(bounds.center.x / root.width, bounds.center.y / root.height))
        }
        compose.waitForIdle()
    }
}
