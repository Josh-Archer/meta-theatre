package casa.archer.metatheatre

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.AlphaMode
import com.meta.spatial.runtime.PanelConfigOptions
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PanelPresentationIntegrationTest {
    @Test fun menusDisableGlassWhileTheMovieCarrierRetainsItsEmptyAlphaChannel() {
        val panel=PanelConfigOptions().apply { includeGlass=true;unlit=false }
        PanelPresentation.configure(panel)
        assertFalse(panel.includeGlass)
        assertTrue(panel.unlit)
        assertEquals(AlphaMode.TRANSLUCENT,panel.alphaMode)
        val movie=PanelConfigOptions()
        VideoPresentation.configure(movie)
        assertEquals(AlphaMode.HOLE_PUNCH,movie.alphaMode)
        assertFalse(movie.enableTransparent)
    }

    @Test fun selectorBackingFollowsResizeAndFoldButCannotBlackOutTheMovie() {
        val eye=Vector3(0f,1.6f,0f)
        for(yaw in listOf(0f,90f,180f)) {
            val pose=Pose(Vector3(.2f,1.3f,2.5f),Quaternion(pitch=0f,yaw=yaw,roll=0f))
            val size=ScreenSize(4.2f,2.3625f)
            val state=PanelPresentation.libraryBacking(pose,size,false,1f)
            assertTrue(state.visible)
            assertEquals(size,state.size)
            // The backing lies behind the UI on its opposite side from the panel's front normal.
            assertTrue((state.pose.t-pose.t).dot(pose.forward())<0f)
            assertEquals(pose.q,state.pose.q)
            assertEquals(2.1f,PanelPresentation.libraryBacking(pose,size,false,.5f).size.width,.0001f)
            assertFalse(PanelPresentation.libraryBacking(pose,size,false,0f).visible)
            assertFalse(PanelPresentation.libraryBacking(pose,size,true,1f).visible)
            assertTrue(PanelPresentation.libraryBacking(pose,size,false,1f).visible)
        }
        val hit=Vector3(.8f,1.4f,2.4f)
        val cursor=PanelPresentation.cursorPosition(hit,eye)
        assertTrue((cursor-eye).length()<(hit-eye).length())
        assertEquals(.025f,(cursor-hit).length(),.00001f)
    }
}
