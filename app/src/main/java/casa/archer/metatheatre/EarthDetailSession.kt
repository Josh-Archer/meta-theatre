package casa.archer.metatheatre

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import kotlin.math.acos
import kotlin.math.min

/**
 * Geographic surface submission. The same authored vertices stay in place.
 * Zoom is remembered for diagnostics and is not a visibility cutoff.
 */
internal class EarthDetailSubmission(
    val positions: FloatArray,
    val normals: FloatArray,
    val uvs: FloatArray,
    val blend: FloatArray,
    val indices: IntArray,
    val triangleCount: Int,
    val authoredTriangles: Int,
    val visibleTriangles: Int,
    val drawCalls: Int,
    val detailVisible: Boolean,
    val horizonRadians: Float,
    val outerRadians: Float,
    val center: FloatArray,
    val refreshed: Boolean,
    val zoom: Float,
) {
    val textureMaps: List<String> = TEXTURE_MAPS

    companion object {
        val TEXTURE_MAPS: List<String> = listOf("day.jpg", "night.jpg", "normal.jpg", "ocean.jpg", "clouds.png")
        const val SURFACE_DRAW_CALLS = 1
    }
}

internal data class EarthViewSample(val center: FloatArray, val distance: Float, val zoom: Float)

internal object EarthDetailFrames {
    /** Viewer direction in the surface mesh's local frame. +Z is the authored front. */
    fun sample(frame: Pose, spinDegrees: Float, viewer: Vector3, zoom: Float): EarthViewSample {
        val spin = Quaternion(pitch = 0f, yaw = spinDegrees, roll = 0f)
        val rotation = (frame.q * EarthRenderer.orientation() * spin).normalize()
        val earthCenter = frame * EarthRenderer.CENTER
        val offset = viewer - earthCenter
        val distance = offset.length()
        val local = rotation.inverse() * offset
        val length = local.length()
        val center = if (length > 1e-4f) floatArrayOf(local.x / length, local.y / length, local.z / length)
        else floatArrayOf(0f, 0f, 1f)
        return EarthViewSample(center, distance, if (zoom.isFinite()) zoom else Float.NaN)
    }
}

internal class EarthDetailSession(private val mesh: EarthSurfaceGeometry) {
    private var retained: EarthDetailSubmission? = null

    /**
     * Submit the authored cap that faces [center], plus the mesh margin.
     * A retained cap is kept only while it still covers the new horizon.
     * Below any particular zoom, including 1.3, a region change still rebuilds.
     * The patch is hidden only when it would contribute no exterior surface;
     * the authored mesh and its textures are submitted instead, then the same
     * vertices return with the next covering view.
     */
    fun view(centerIn: FloatArray, distance: Float, zoom: Float): EarthDetailSubmission {
        val center = centerIn.asUnitDirection()
        val recordedZoom = if (zoom.isFinite()) zoom else Float.NaN
        if (!distance.isFinite() || distance <= EarthRenderer.RADIUS) {
            retained = null
            return authoredMesh(center, recordedZoom, horizon = 0f, refreshed = true)
        }
        val horizon = acos((EarthRenderer.RADIUS / distance).coerceIn(-1f, 1f))
        val current = retained
        if (current != null && current.detailVisible && covers(current, center, horizon)) {
            return EarthDetailSubmission(
                current.positions, current.normals, current.uvs, current.blend, current.indices,
                current.triangleCount, current.authoredTriangles, current.visibleTriangles, current.drawCalls,
                true, current.horizonRadians, current.outerRadians, current.center, refreshed = false, recordedZoom,
            )
        }
        val patch = buildPatch(center, horizon, recordedZoom)
        return if (patch.visibleTriangles == 0) {
            retained = null
            authoredMesh(center, recordedZoom, horizon, refreshed = true)
        } else {
            retained = patch
            patch
        }
    }

    private fun covers(current: EarthDetailSubmission, center: FloatArray, horizon: Float): Boolean {
        val separation = sphericalAngle(current.center, center)
        return separation + horizon + mesh.maxEdgeAngle <= current.outerRadians + 1e-4f
    }

    private fun buildPatch(center: FloatArray, horizon: Float, zoom: Float): EarthDetailSubmission {
        val outer = min(horizon + mesh.marginRadians, Math.PI.toFloat())
        val angles = FloatArray(mesh.vertexCount) { sphericalAngle(mesh.direction(it), center) }
        val include = BooleanArray(mesh.triangleCount)
        var visible = 0
        for (triangle in 0 until mesh.triangleCount) {
            val nearest = min(
                angles[mesh.indices[triangle * 3]],
                min(angles[mesh.indices[triangle * 3 + 1]], angles[mesh.indices[triangle * 3 + 2]]),
            )
            if (nearest <= outer) {
                include[triangle] = true
                if (nearest <= horizon) visible++
            }
        }
        val remap = IntArray(mesh.vertexCount) { -1 }
        var used = 0
        var written = 0
        val patchTriangles = include.count { it }
        val indices = IntArray(patchTriangles * 3)
        for (triangle in 0 until mesh.triangleCount) {
            if (!include[triangle]) continue
            for (corner in 0 until 3) {
                val source = mesh.indices[triangle * 3 + corner]
                var next = remap[source]
                if (next < 0) {
                    next = used++
                    remap[source] = next
                }
                indices[written++] = next
            }
        }
        val positions = FloatArray(used * 3)
        val normals = FloatArray(used * 3)
        val uvs = FloatArray(used * 2)
        val blend = FloatArray(used)
        for (source in 0 until mesh.vertexCount) {
            val next = remap[source]
            if (next < 0) continue
            positions[next * 3] = mesh.positions[source * 3]
            positions[next * 3 + 1] = mesh.positions[source * 3 + 1]
            positions[next * 3 + 2] = mesh.positions[source * 3 + 2]
            normals[next * 3] = mesh.normals[source * 3]
            normals[next * 3 + 1] = mesh.normals[source * 3 + 1]
            normals[next * 3 + 2] = mesh.normals[source * 3 + 2]
            uvs[next * 2] = mesh.uvs[source * 2]
            uvs[next * 2 + 1] = mesh.uvs[source * 2 + 1]
            blend[next] = feather(angles[source], horizon, outer)
        }
        return EarthDetailSubmission(
            positions, normals, uvs, blend, indices, patchTriangles, mesh.triangleCount, visible,
            EarthDetailSubmission.SURFACE_DRAW_CALLS, true, horizon, outer, center, refreshed = true, zoom,
        )
    }

    private fun authoredMesh(center: FloatArray, zoom: Float, horizon: Float, refreshed: Boolean): EarthDetailSubmission {
        return EarthDetailSubmission(
            mesh.positions.copyOf(), mesh.normals.copyOf(), mesh.uvs.copyOf(),
            FloatArray(mesh.vertexCount) { 1f }, mesh.indices.copyOf(), mesh.triangleCount, mesh.triangleCount,
            visibleTriangles = 0, EarthDetailSubmission.SURFACE_DRAW_CALLS, detailVisible = false,
            horizon, outerRadians = horizon, center, refreshed, zoom,
        )
    }

    private fun feather(angle: Float, inner: Float, outer: Float): Float {
        if (outer <= inner + 1e-6f || angle <= inner) return 1f
        if (angle >= outer) return 0f
        val t = ((angle - inner) / (outer - inner)).coerceIn(0f, 1f)
        val smooth = t * t * (3f - 2f * t)
        return 1f - smooth
    }
}
