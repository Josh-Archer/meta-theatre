package casa.archer.metatheatre

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import org.junit.Assert.assertEquals
import org.junit.Test

class HandTrackingSpaceTest {
    private fun assertPoint(expected: HandPoint, actual: HandPoint) {
        assertEquals(expected.x, actual.x, 1e-5f)
        assertEquals(expected.y, actual.y, 1e-5f)
        assertEquals(expected.z, actual.z, 1e-5f)
    }

    @Test fun teleportTranslatesPositionButNeverPalmNormal() {
        val palm = OpenPalm(0, HandPoint(0.3f, 1.2f, 0.5f), HandPoint(0f, 0f, 1f))
        val moved = HandTrackingSpace.toScene(palm, Pose(Vector3(2.2f, 0f, -2.2f)))
        assertPoint(HandPoint(2.5f, 1.2f, -1.7f), moved.position)
        assertPoint(palm.normal, moved.normal)
        assertEquals(palm.side, moved.side)
    }

    @Test fun snapTurnRotatesPositionAndPalmNormalTogether() {
        val palm = OpenPalm(1, HandPoint(0f, 1.2f, 0.5f), HandPoint(0f, 0f, 1f))
        val turned = HandTrackingSpace.toScene(palm,
            Pose(Vector3(0f, 0f, 0f), Quaternion(pitch = 0f, yaw = 90f, roll = 0f)))
        assertPoint(HandPoint(0.5f, 1.2f, 0f), turned.position)
        assertPoint(HandPoint(1f, 0f, 0f), turned.normal)
    }

    @Test fun headAlignmentIncludesPhysicalRecenterAndTrackedHeadRotation() {
        val localHead = Pose(Vector3(0.4f, 1.6f, -0.3f), Quaternion(pitch = 12f, yaw = 20f, roll = 4f))
        val worldFromLocal = Pose(Vector3(-2f, 0f, -4f), Quaternion(pitch = 0f, yaw = -45f, roll = 0f))
        val worldHead = worldFromLocal * localHead
        val palm = OpenPalm(0, HandPoint(0.1f, 1.2f, 0.3f), HandPoint(0f, 0f, 1f))
        val aligned = HandTrackingSpace.toScene(palm, worldHead * localHead.inverse())
        val expected = HandTrackingSpace.toScene(palm, worldFromLocal)
        assertPoint(expected.position, aligned.position)
        assertPoint(expected.normal, aligned.normal)
    }
}
