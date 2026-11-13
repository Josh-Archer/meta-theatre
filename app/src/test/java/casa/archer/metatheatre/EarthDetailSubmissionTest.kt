package casa.archer.metatheatre

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/** Geographic surface submission against the packaged 16,128-triangle sphere. */
class EarthDetailSubmissionTest {
    private val mesh = EarthSurfaceGeometry.parseGlb(fixture())
    private val seat = Pose(EarthRenderer.SEAT_EYE.copy())

    @Test fun packagedSurfaceIsTheAuthoredSphereRatherThanAQuarterMillionTriangleStandIn() {
        val bytes = fixture()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("6b4b883781dbe73c0552682ea8bc083e08f52164c3fd3e433bd2ec740789f1c3", hash)
        assertEquals(366_668, bytes.size)
        assertEquals(EarthSurfaceGeometry.AUTHORED_TRIANGLES, mesh.triangleCount)
        assertEquals(8_387, mesh.vertexCount)
        assertTrue(mesh.maxEdgeAngle < Math.toRadians(6.0))
        assertTrue(mesh.marginRadians >= mesh.maxEdgeAngle * 2f)
        try {
            EarthSurfaceGeometry.parseGlb(byteArrayOf(1, 2, 3, 4))
            error("A non-GLB surface must not parse")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("GLB"))
        }
    }

    @Test fun seatPolesDatelineAndHorizonKeepAuthoredDensityAndBlendTheMargin() {
        val seatSample = EarthDetailFrames.sample(Pose(), 0f, EarthRenderer.SEAT_EYE, 1f)
        assertDirection(floatArrayOf(0f, 0f, 1f), seatSample.center)
        val north = explore(EarthControl.NORTH_POLE)
        val south = explore(EarthControl.SOUTH_POLE)
        val far = explore(EarthControl.FAR_SIDE)
        assertDirection(floatArrayOf(0f, 1f, 0f), north.center)
        assertDirection(floatArrayOf(0f, -1f, 0f), south.center)
        assertDirection(floatArrayOf(0f, 0f, -1f), far.center)

        val views = linkedMapOf(
            "seat" to submission(seatSample),
            "north" to submission(north),
            "south" to submission(south),
            "far" to submission(far),
            "dateline" to EarthDetailSession(mesh).view(seamCenter(), 160f, 1.1f),
            "close" to submission(zoomed(3f)),
            "wide" to submission(zoomed(.5f)),
        )
        views.forEach { (name, patch) ->
            assertTrue("$name detail is visible", patch.detailVisible)
            assertTrue("$name submits a subset", patch.triangleCount < mesh.triangleCount)
            assertEquals(1, patch.drawCalls)
            assertEquals(mesh.triangleCount, patch.authoredTriangles)
            assertEquals(EarthDetailSubmission.TEXTURE_MAPS, patch.textureMaps)
            assertSubset(patch)
            assertCovers(patch, patch.center, patch.horizonRadians)
            assertBlend(patch)
            assertInteriorEdgesMatch(patch)
        }
        assertEquals(3_474, views.getValue("seat").triangleCount)
        assertEquals(2_620, views.getValue("seat").visibleTriangles)
        assertEquals(5_760, views.getValue("north").triangleCount)
        assertEquals(4_992, views.getValue("north").visibleTriangles)
        assertEquals(5_760, views.getValue("south").triangleCount)
        assertEquals(4_992, views.getValue("south").visibleTriangles)
        assertEquals(3_474, views.getValue("far").triangleCount)
        assertEquals(2_620, views.getValue("far").visibleTriangles)
        assertEquals(3_605, views.getValue("dateline").triangleCount)
        assertEquals(2_724, views.getValue("dateline").visibleTriangles)
        assertEquals(2_184, views.getValue("close").triangleCount)
        assertEquals(1_544, views.getValue("close").visibleTriangles)
        assertEquals(4_744, views.getValue("wide").triangleCount)
        assertEquals(3_670, views.getValue("wide").visibleTriangles)
        val seatPatch = views.getValue("seat")
        val closePatch = views.getValue("close")
        val widePatch = views.getValue("wide")
        assertTrue(closePatch.triangleCount < seatPatch.triangleCount)
        assertTrue(seatPatch.triangleCount < widePatch.triangleCount)
        assertTrue(containsDirection(views.getValue("north"), floatArrayOf(0f, 1f, 0f)))
        assertFalse(containsDirection(views.getValue("north"), floatArrayOf(0f, -1f, 0f)))
        assertTrue(containsDirection(views.getValue("south"), floatArrayOf(0f, -1f, 0f)))
        assertFalse(containsDirection(views.getValue("south"), floatArrayOf(0f, 1f, 0f)))
    }

    @Test fun regionMovesDatelineAndPolesKeepTheVisibleCapAtFullBlend() {
        val origin = floatArrayOf(0f, 0f, 1f)
        val shifted = yaw(origin, Math.toRadians(20.0).toFloat())
        val first = EarthDetailSession(mesh).view(origin, 160f, 1.4f)
        val second = EarthDetailSession(mesh).view(shifted, 160f, 1.4f)
        assertCovers(first, origin, first.horizonRadians)
        assertCovers(second, shifted, second.horizonRadians)
        val overlap = overlapInterior(first, origin, second, shifted)
        assertTrue("Region change keeps an interior overlap", overlap > 100)
        assertSeamVerticesIncluded()
        assertTrue(containsDirection(EarthDetailSession(mesh).view(floatArrayOf(0f, 1f, 0f), 160f, 1f), floatArrayOf(0f, 1f, 0f)))
    }

    @Test fun zoomBelowOnePointThreeDoesNotFreezeAStalePatch() {
        val session = EarthDetailSession(mesh)
        val closeCenter = floatArrayOf(0f, 0f, 1f)
        val close = session.view(closeCenter, 140f, 1.45f)
        assertTrue(close.detailVisible)
        val elsewhere = yaw(closeCenter, Math.toRadians(50.0).toFloat())
        val belowCutoff = session.view(elsewhere, 200f, 1.0f)
        assertTrue(belowCutoff.detailVisible)
        assertTrue(belowCutoff.refreshed)
        assertEquals(1f, belowCutoff.zoom, 0f)
        assertFalse(close.center.contentEquals(belowCutoff.center))
        assertCovers(belowCutoff, elsewhere, belowCutoff.horizonRadians)
        assertTrue(belowCutoff.triangleCount > close.triangleCount)
        val samePlace = EarthDetailSession(mesh)
        val high = samePlace.view(closeCenter, 160f, 1.45f)
        val nudged = yaw(closeCenter, mesh.marginRadians * .2f)
        val low = samePlace.view(nudged, 160f, 1.0f)
        assertFalse("A still-covering cap is retained instead of being discarded at zoom 1.0", low.refreshed)
        assertTrue(low.detailVisible)
        assertCovers(low, nudged, acos((EarthRenderer.RADIUS / 160f).coerceIn(-1f, 1f)))
        assertEquals(high.triangleCount, low.triangleCount)
    }

    @Test fun detailHidesOnlyWhenItContributesNothingAndReturnsOnTheSameVertices() {
        val session = EarthDetailSession(mesh)
        val center = floatArrayOf(0f, 0f, 1f)
        val hidden = session.view(center, EarthRenderer.RADIUS * .5f, 1.4f)
        assertFalse(hidden.detailVisible)
        assertEquals(0, hidden.visibleTriangles)
        assertEquals(mesh.triangleCount, hidden.triangleCount)
        assertEquals(1, hidden.drawCalls)
        assertArrayEquals(mesh.positions, hidden.positions, 0f)
        assertArrayEquals(mesh.normals, hidden.normals, 0f)
        assertArrayEquals(mesh.uvs, hidden.uvs, 0f)
        assertEquals(EarthDetailSubmission.TEXTURE_MAPS, hidden.textureMaps)
        assertTrue(hidden.blend.all { it == 1f })
        val returned = session.view(center, 160f, 1.2f)
        assertTrue(returned.detailVisible)
        assertTrue(returned.refreshed)
        assertTrue(returned.triangleCount < mesh.triangleCount)
        assertSubset(returned)
        assertCovers(returned, center, returned.horizonRadians)
        assertEquals(hidden.textureMaps, returned.textureMaps)
        assertInteriorEdgesMatch(returned)
    }

    private fun explore(control: EarthControl): EarthViewSample {
        val exploration = EarthExploration()
        exploration.apply(control, seat)
        val sample = EarthDetailFrames.sample(exploration.worldFrame, exploration.spinAt(), seat.t, exploration.zoom)
        assertEquals(exploration.distance, sample.distance, .001f)
        return sample
    }

    private fun zoomed(factor: Float): EarthViewSample {
        val exploration = EarthExploration()
        exploration.zoomBy(factor, seat)
        return EarthDetailFrames.sample(exploration.worldFrame, exploration.spinAt(), seat.t, exploration.zoom)
    }

    private fun submission(sample: EarthViewSample) =
        EarthDetailSession(mesh).view(sample.center, sample.distance, sample.zoom)

    private fun assertDirection(expected: FloatArray, actual: FloatArray) {
        assertEquals(expected[0], actual[0], 1e-3f)
        assertEquals(expected[1], actual[1], 1e-3f)
        assertEquals(expected[2], actual[2], 1e-3f)
    }

    private fun assertSubset(patch: EarthDetailSubmission) {
        val index = sourceIndex()
        for (vertex in 0 until patch.positions.size / 3) {
            val key = key(
                patch.positions[vertex * 3], patch.positions[vertex * 3 + 1], patch.positions[vertex * 3 + 2],
                patch.uvs[vertex * 2], patch.uvs[vertex * 2 + 1],
            )
            val source = index[key]
            assertTrue("Submitted vertex $vertex is not an authored vertex", source != null)
            assertEquals(mesh.normals[source!! * 3], patch.normals[vertex * 3], 0f)
            assertEquals(mesh.normals[source * 3 + 1], patch.normals[vertex * 3 + 1], 0f)
            assertEquals(mesh.normals[source * 3 + 2], patch.normals[vertex * 3 + 2], 0f)
        }
    }

    private fun assertCovers(patch: EarthDetailSubmission, center: FloatArray, horizon: Float) {
        val have = patchKeys(patch)
        var covered = 0
        for (vertex in 0 until mesh.vertexCount) {
            if (sphericalAngle(mesh.direction(vertex), center) > horizon + 1e-4f) continue
            covered++
            val key = key(
                mesh.positions[vertex * 3], mesh.positions[vertex * 3 + 1], mesh.positions[vertex * 3 + 2],
                mesh.uvs[vertex * 2], mesh.uvs[vertex * 2 + 1],
            )
            assertTrue("Horizon vertex $vertex was not submitted", have.contains(key))
        }
        assertTrue(covered > 0)
    }

    private fun assertBlend(patch: EarthDetailSubmission) {
        var feathered = 0
        for (vertex in 0 until patch.blend.size) {
            val angle = sphericalAngle(
                floatArrayOf(patch.positions[vertex * 3], patch.positions[vertex * 3 + 1], patch.positions[vertex * 3 + 2]).asUnitDirection(),
                patch.center,
            )
            val weight = patch.blend[vertex]
            if (angle < patch.horizonRadians - 1e-3f) assertEquals(1f, weight, 0f)
            if (angle > patch.horizonRadians + 1e-3f && angle < patch.outerRadians - 1e-3f) {
                assertTrue(weight > 0f && weight < 1f)
                feathered++
            }
        }
        assertTrue("The margin ring must carry a partial blend", feathered > 0)
    }

    private fun assertInteriorEdgesMatch(patch: EarthDetailSubmission) {
        val limit = Math.toRadians(8.0).toFloat()
        assertEquals(maxInteriorEdge(mesh.positions, mesh.indices, patch.center, limit),
            maxInteriorEdge(patch.positions, patch.indices, patch.center, limit), 0f)
    }

    private fun containsDirection(patch: EarthDetailSubmission, direction: FloatArray): Boolean {
        val unit = direction.asUnitDirection()
        for (vertex in 0 until patch.positions.size / 3) {
            val angle = sphericalAngle(
                floatArrayOf(patch.positions[vertex * 3], patch.positions[vertex * 3 + 1], patch.positions[vertex * 3 + 2]).asUnitDirection(),
                unit,
            )
            if (angle < 1e-3f) return true
        }
        return false
    }

    private fun assertSeamVerticesIncluded() {
        val (left, right) = seamPair()
        val center = floatArrayOf(
            mesh.positions[left * 3] + mesh.positions[right * 3],
            mesh.positions[left * 3 + 1] + mesh.positions[right * 3 + 1],
            mesh.positions[left * 3 + 2] + mesh.positions[right * 3 + 2],
        ).asUnitDirection()
        val patch = EarthDetailSession(mesh).view(center, 160f, 1.25f)
        val have = patchKeys(patch)
        assertTrue(have.contains(vertexKey(left)))
        assertTrue(have.contains(vertexKey(right)))
        assertNotEquals(mesh.uvs[left * 2], mesh.uvs[right * 2])
        assertCovers(patch, center, patch.horizonRadians)
    }

    private fun seamCenter(): FloatArray {
        val (left, right) = seamPair()
        return floatArrayOf(
            mesh.positions[left * 3] + mesh.positions[right * 3],
            mesh.positions[left * 3 + 1] + mesh.positions[right * 3 + 1],
            mesh.positions[left * 3 + 2] + mesh.positions[right * 3 + 2],
        ).asUnitDirection()
    }

    private fun seamPair(): Pair<Int, Int> {
        var best = -1 to -1
        var bestDistance = Float.POSITIVE_INFINITY
        val low = ArrayList<Int>()
        val high = ArrayList<Int>()
        for (vertex in 0 until mesh.vertexCount) {
            if (kotlin.math.abs(mesh.positions[vertex * 3 + 1]) > .05f) continue
            val u = mesh.uvs[vertex * 2]
            if (u < .02f) low.add(vertex)
            if (u > .98f) high.add(vertex)
        }
        for (a in low) for (b in high) {
            val dx = mesh.positions[a * 3] - mesh.positions[b * 3]
            val dy = mesh.positions[a * 3 + 1] - mesh.positions[b * 3 + 1]
            val dz = mesh.positions[a * 3 + 2] - mesh.positions[b * 3 + 2]
            val distance = dx * dx + dy * dy + dz * dz
            if (distance < bestDistance) {
                bestDistance = distance
                best = a to b
            }
        }
        assertTrue("UV seam vertices were not adjacent in space ($bestDistance)", bestDistance < .002f)
        return best
    }

    private fun overlapInterior(first: EarthDetailSubmission, firstCenter: FloatArray, second: EarthDetailSubmission, secondCenter: FloatArray): Int {
        val secondKeys = patchKeys(second)
        var count = 0
        val horizon = minOf(first.horizonRadians, second.horizonRadians)
        for (vertex in 0 until mesh.vertexCount) {
            val direction = mesh.direction(vertex)
            if (sphericalAngle(direction, firstCenter) > horizon - .02f) continue
            if (sphericalAngle(direction, secondCenter) > horizon - .02f) continue
            if (!secondKeys.contains(vertexKey(vertex))) continue
            count++
            assertEquals(1f, submittedBlend(first, vertexKey(vertex)), 0f)
            assertEquals(1f, submittedBlend(second, vertexKey(vertex)), 0f)
        }
        return count
    }

    private fun maxInteriorEdge(positions: FloatArray, indices: IntArray, center: FloatArray, limit: Float): Float {
        var widest = 0f
        fun inside(vertex: Int): Boolean {
            val direction = floatArrayOf(positions[vertex * 3], positions[vertex * 3 + 1], positions[vertex * 3 + 2]).asUnitDirection()
            return sphericalAngle(direction, center) <= limit
        }
        fun edge(a: Int, b: Int): Float {
            val ax = positions[a * 3]; val ay = positions[a * 3 + 1]; val az = positions[a * 3 + 2]
            val bx = positions[b * 3]; val by = positions[b * 3 + 1]; val bz = positions[b * 3 + 2]
            return sphericalAngle(floatArrayOf(ax, ay, az).asUnitDirection(), floatArrayOf(bx, by, bz).asUnitDirection())
        }
        for (triangle in indices.indices step 3) {
            val a = indices[triangle]; val b = indices[triangle + 1]; val c = indices[triangle + 2]
            if (!inside(a) || !inside(b) || !inside(c)) continue
            widest = maxOf(widest, edge(a, b), edge(b, c), edge(c, a))
        }
        assertTrue(widest > 0f)
        return widest
    }

    private fun sourceIndex(): HashMap<Long, Int> {
        val index = HashMap<Long, Int>(mesh.vertexCount * 2)
        for (vertex in 0 until mesh.vertexCount) index[vertexKey(vertex)] = vertex
        return index
    }

    private fun patchKeys(patch: EarthDetailSubmission): HashSet<Long> {
        val keys = HashSet<Long>(patch.positions.size)
        for (vertex in 0 until patch.positions.size / 3) {
            keys.add(key(
                patch.positions[vertex * 3], patch.positions[vertex * 3 + 1], patch.positions[vertex * 3 + 2],
                patch.uvs[vertex * 2], patch.uvs[vertex * 2 + 1],
            ))
        }
        return keys
    }

    private fun submittedBlend(patch: EarthDetailSubmission, key: Long): Float {
        for (vertex in 0 until patch.positions.size / 3) {
            val candidate = key(
                patch.positions[vertex * 3], patch.positions[vertex * 3 + 1], patch.positions[vertex * 3 + 2],
                patch.uvs[vertex * 2], patch.uvs[vertex * 2 + 1],
            )
            if (candidate == key) return patch.blend[vertex]
        }
        error("Missing submitted vertex")
    }

    private fun vertexKey(vertex: Int) = key(
        mesh.positions[vertex * 3], mesh.positions[vertex * 3 + 1], mesh.positions[vertex * 3 + 2],
        mesh.uvs[vertex * 2], mesh.uvs[vertex * 2 + 1],
    )

    private fun key(x: Float, y: Float, z: Float, u: Float, v: Float): Long {
        var value = 17L
        for (bits in intArrayOf(x.toRawBits(), y.toRawBits(), z.toRawBits(), u.toRawBits(), v.toRawBits())) {
            value = value * 31 + bits
        }
        return value
    }

    private fun yaw(center: FloatArray, radians: Float): FloatArray {
        val c = cos(radians); val s = sin(radians)
        return floatArrayOf(center[0] * c + center[2] * s, center[1], -center[0] * s + center[2] * c).asUnitDirection()
    }

    private fun fixture(): ByteArray {
        EarthDetailSubmissionTest::class.java.classLoader!!.getResourceAsStream("earth_dynamic_surface_mesh.bin")?.use {
            return it.readBytes()
        }
        val candidates = listOf(
            File("src/test/resources/earth_dynamic_surface_mesh.bin"),
            File("app/src/test/resources/earth_dynamic_surface_mesh.bin"),
        )
        return candidates.first { it.isFile }.readBytes()
    }
}
