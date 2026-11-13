package casa.archer.metatheatre

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EarthDetailTilesTest {
    private fun tiles() = RuntimeEnvironment.getApplication().assets
        .open("earth/detail/manifest.json").bufferedReader().use { EarthDetailTiles.parse(it.readText()) }

    @Test fun shippedManifestPreservesEveryTriangleAndBoundsRegionalSubmission() {
        val tiles = tiles()
        assertEquals(128, tiles.size)
        assertEquals(260096, tiles.sumOf { it.triangles })
        for (latitude in listOf(-90.0, -81.0, -67.5, -22.5, 0.0, 33.58, 67.5, 81.0, 90.0)) {
            for (longitude in listOf(-180.0, -179.999, -157.5, -85.08, 0.0, 22.5, 157.5, 179.999, 180.0)) {
                for (zoom in listOf(2f, 20f, 100f)) {
                    val region = EarthDetailRegion.around(WeatherPlace(latitude, longitude), zoom)
                    val visible = tiles.filter { it.intersects(region) }
                    assertTrue("No detail at $region", visible.isNotEmpty())
                    assertTrue("Unexpected draw count at $region", visible.size <= 4)
                    assertTrue("Unexpected vertex budget at $region", visible.sumOf { it.triangles } <= 8192)
                }
            }
        }
    }

    @Test fun wrapSeamSelectsBothEndsWithoutSelectingTheRestOfTheWorld() {
        val tiles = tiles()
        val region = EarthDetailRegion(0.0, 180.0, 4.0)
        val selected = tiles.filter { it.intersects(region) }
        assertTrue(selected.any { it.minU < .05 })
        assertTrue(selected.any { it.maxU > .95 })
        assertFalse(selected.any { it.minU > .1 && it.maxU < .9 })
        assertEquals(selected, tiles.filter { it.intersects(EarthDetailRegion(0.0,-180.0,4.0)) })
    }

    @Test fun geographicEdgesRetainStraddlingTrianglesAndRejectOnlyDisjointTiles() {
        // Bounds deliberately extend beyond the nominal partition cell.
        val crossing = EarthDetailTile("earth/detail/000.glb", -.001, .064, .49, .51, 1)
        assertTrue(crossing.intersects(EarthDetailRegion(0.0,179.9,1.0)))
        assertTrue(crossing.intersects(EarthDetailRegion(0.0,-157.0,1.0)))
        assertFalse(crossing.intersects(EarthDetailRegion(0.0,0.0,18.0)))
        assertFalse(crossing.intersects(EarthDetailRegion(60.0,-179.9,18.0)))
    }

    @Test fun polarRegionsUseTheOriginalPoleGeometryWithoutLatitudeClampingInSelection() {
        val tiles = tiles()
        for (latitude in listOf(-90.0,90.0)) {
            val region = EarthDetailRegion.around(WeatherPlace(latitude,18.0),100f)
            val selected = tiles.filter { it.intersects(region) }
            if (latitude > 0) assertTrue(selected.all { it.minV == 0.0 })
            else assertTrue(selected.all { it.maxV == 1.0 })
        }
    }
}
