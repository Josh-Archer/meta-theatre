package casa.archer.metatheatre

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3
import org.junit.Assert.*
import org.junit.Test

class ScenePlacementCacheTest {
    @Test fun flybySubmitsEveryDistinctPoseWithoutResubmittingItsFixedScale() {
        val cache = ScenePlacementCache(.5f)
        val positions = mutableListOf<Pose>()
        val scales = mutableListOf<Float>()
        repeat(90) { frame ->
            val pose = Pose(Vector3(frame / 90f, 0f, 0f))
            if (cache.updatePose(pose)) positions.add(pose)
            if (cache.updateScale(.5f)) scales.add(.5f)
        }
        assertEquals(90, positions.size)
        assertTrue(scales.isEmpty())
        if (cache.updateScale(.1f)) scales.add(.1f)
        if (cache.updateScale(.1f)) scales.add(.1f)
        if (cache.updateScale(.5f)) scales.add(.5f)
        assertEquals(listOf(.1f, .5f), scales)
    }

    @Test fun stationaryInspectionSkipsWritesButInPlacePoseChangesAreObservedExactly() {
        val cache = ScenePlacementCache(1f)
        val pose = Pose()
        var writes = 0
        repeat(90) { if (cache.updatePose(pose)) writes++ }
        assertEquals(1, writes)
        pose.t.x = .000001f
        if (cache.updatePose(pose)) writes++
        pose.q.y = .000001f
        if (cache.updatePose(pose)) writes++
        assertEquals(3, writes)
    }
}
