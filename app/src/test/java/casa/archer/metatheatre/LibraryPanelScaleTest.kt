package casa.archer.metatheatre

import com.meta.spatial.core.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LibraryPanelScaleTest {
    @Test fun explicitUnitScaleMatchesTheMissingComponentFallback() {
        val explicit = libraryPanelUnitScale()
        val fallback = resolveLogicalScale(presentation = null, component = null)
        assertEquals(1f, explicit.x, 0f)
        assertEquals(1f, explicit.y, 0f)
        assertEquals(1f, explicit.z, 0f)
        assertEquals(explicit, fallback)
        // Each read is a new vector, so one caller cannot change the next default.
        assertTrue(fallback !== libraryPanelUnitScale())
        assertTrue(fallback !== resolveLogicalScale(presentation = null, component = null))
    }

    @Test fun unitScalePreservesDefaultAndSavedSizeAndRailPlacement() {
        val scale = resolveLogicalScale(presentation = null, component = libraryPanelUnitScale())
        val saved = ScreenSize(5.6f, 5.6f * 9f / 16f)
        val sizes = listOf(
            ScreenSize(EarthOrbit.defaultScreenWidth, EarthOrbit.defaultScreenHeight),
            ScreenSize(SpaceshipCabin.defaultScreenWidth, SpaceshipCabin.defaultScreenHeight),
            saved,
            ScreenSize(ScreenSize.MIN_WIDTH, ScreenSize.MIN_WIDTH * 9f / 16f),
            ScreenSize(ScreenSize.MAX_WIDTH, ScreenSize.MAX_WIDTH * 9f / 16f),
        )
        for (size in sizes) {
            val physical = size.scaled(scale.x, scale.y)
            assertEquals(size, physical)
            assertEquals(SideRailPlacement.beside(size), SideRailPlacement.beside(physical))
        }
    }

    @Test fun presentationAndResizedComponentsStillChangeSizeFromTheUnitStart() {
        val base = ScreenSize(EarthOrbit.defaultScreenWidth, EarthOrbit.defaultScreenHeight)
        val unit = libraryPanelUnitScale()
        val fraction = 0.25f
        val presentation = Vector3(unit.x * fraction, unit.y, unit.z)
        val duringHide = resolveLogicalScale(presentation, unit)
        assertEquals(presentation, duringHide)
        val hidden = base.scaled(duringHide.x, duringHide.y)
        assertEquals(base.width * fraction, hidden.width, 0.00001f)
        assertEquals(base.height, hidden.height, 0.00001f)

        val desired = base.resized(ScreenSize.GROW)
        val resized = Vector3(desired.width / base.width, desired.height / base.height, 1f)
        val physical = base.scaled(resolveLogicalScale(null, resized).x, resolveLogicalScale(null, resized).y)
        assertEquals(desired, physical)
        assertNotEquals(base, physical)
    }

    @Test fun libraryPanelCreationAttachesTheUnitScaleComponent() {
        val source = File(repoRoot(), "app/src/main/java/casa/archer/metatheatre/TheatreActivity.kt").readText()
        val panel = source.substringAfter("val panel = Entity.create(").substringBefore("\n        )")
        assertTrue(panel.contains("Panel(R.id.library_panel"))
        assertTrue(panel.contains("Scale(libraryPanelUnitScale())"))
        val grip = File(repoRoot(), "app/src/main/java/casa/archer/metatheatre/ScreenGrip.kt").readText()
        assertTrue(grip.contains("private fun logicalScale(): Vector3 = resolveLogicalScale("))
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        var remaining = 8
        while (remaining > 0) {
            if (File(dir, "settings.gradle.kts").isFile && File(dir, "app").isDirectory) return dir
            dir = dir.parentFile ?: error("Repo root not found from ${System.getProperty("user.dir")}")
            remaining -= 1
        }
        error("Repo root not found from ${System.getProperty("user.dir")}")
    }
}
