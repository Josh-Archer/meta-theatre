package casa.archer.metatheatre

import androidx.core.net.toUri
import com.meta.spatial.core.Color4
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.Material
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.Plane
import com.meta.spatial.toolkit.SupportsLocomotion
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible

/** Small SDK primitives give the open-space scene real, upward-facing teleport targets. */
internal class LocomotionTargets {
    private val pads = listOf(
        Vector3(0f, 0f, 0f),       // Return to the chair.
        Vector3(-2.2f, 0f, 0f),
        Vector3(2.2f, 0f, 0f),
        Vector3(-2.2f, 0f, -2.2f),
        Vector3(2.2f, 0f, -2.2f),
        Vector3(0f, 0f, -4.4f),
    ).map { position ->
        Entity.create(
            Mesh("mesh://plane".toUri(), hittable = MeshCollision.LineTest),
            Plane(width = 1.4f, depth = 1.4f),
            Material().apply {
                baseColor = Color4(red = 0.22f, green = 0.18f, blue = 0.10f, alpha = 1f)
                unlit = true
            },
            SupportsLocomotion(),
            Transform(Pose(position)),
            Visible(false),
        )
    }
    private var visible = false

    fun show(value: Boolean) {
        if (value == visible) return
        visible = value
        pads.forEach { it.setComponent(Visible(value)) }
    }
}
