package casa.archer.metatheatre

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import org.junit.Assert.*
import org.junit.Test

class ScenePointerTest {
    @Test fun everyPoseComponentRejectsNonFiniteTrackingValues() {
        assertTrue(ScenePointer.valid(Pose()))
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            for (index in 0..6) {
                val parts = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f)
                parts[index] = invalid
                val pose = Pose(Vector3(parts[0], parts[1], parts[2]),
                    Quaternion(w = parts[3], x = parts[4], y = parts[5], z = parts[6]))
                assertFalse("Invalid component $index", ScenePointer.valid(pose))
            }
        }
    }

    @Test fun nearestVisitorWinsRegardlessOfOrderAndScreenStillOccludesIt() {
        val near = VisitorTarget("near", Pose(Vector3(0f, 0f, 4f)), 1f, 1f)
        val far = VisitorTarget("far", Pose(Vector3(0f, 0f, 8f)), 1f, 1f)
        val pointer = VisitorPointer(1, Pose(), false)
        for (targets in listOf(listOf(near, far), listOf(far, near))) {
            val inspection = VisitorInspection()
            assertEquals("near", inspection.update(listOf(pointer), targets, Pose(), 0).hover?.asset)
            assertNull(inspection.update(listOf(pointer.copy(maxDistance = 2f)), targets, Pose(), 1).hover)
            assertEquals(setOf(1L), inspection.update(listOf(pointer.copy(down = true)), targets, Pose(), 2).owned)
            assertEquals(setOf("near"), inspection.overrides.keys)
        }
    }

    @Test fun rayStartingInsideSphereUsesTheExitIntersection() {
        val target = VisitorTarget("inside", Pose(), 1f, 2f)
        assertEquals(2f, ScenePointer.hit(VisitorPointer(1, Pose(), false), target)!!.distance, 0f)
    }
}
