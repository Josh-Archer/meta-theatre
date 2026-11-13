package casa.archer.metatheatre

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Baked Earth phases and the original weather atlas are art sources.
 * The spaceship cabin still loads hull.glb, so that file stays packaged.
 */
class LegacyAssetPackagingTest {
    @Test
    fun unreferencedLegacyAssetsStayOutOfTheRuntimeTree() {
        val assets = File(repoRoot(), "app/src/main/assets")
        for (relative in EXCLUDED) {
            assertFalse(
                "$relative must not ship from app/src/main/assets",
                File(assets, relative).exists(),
            )
        }
    }

    @Test
    fun artSourcesRetainTheExcludedLegacyBytes() {
        val root = repoRoot()
        for ((relative, sha) in ART_SOURCES) {
            val file = File(root, relative)
            assertTrue("Missing art source $relative", file.isFile && file.length() > 1000L)
            assertEquals("Art source $relative changed", sha, sha256(file))
        }
        for (relative in ART_REFERENCES) {
            val file = File(root, relative)
            assertTrue("Missing editable reference $relative", file.isFile && file.length() > 1000L)
        }
    }

    @Test
    fun requiredDynamicAndCabinAssetsRemain() {
        val assets = File(repoRoot(), "app/src/main/assets")
        for (relative in REQUIRED) {
            val file = File(assets, relative)
            assertTrue("Required runtime asset missing: $relative", file.isFile && file.length() > 1000L)
        }
    }

    @Test
    fun runtimeSourcesHaveNoDirectOrConstructedReferences() {
        val root = repoRoot()
        val starts = listOf(
            File(root, "app/src/main/java"),
            File(root, "app/src/main/cpp"),
            File(root, "app/src/main/res"),
            File(root, "app/src/main/AndroidManifest.xml"),
            File(root, "app/src/shaders"),
            File(root, "app/build.gradle.kts"),
            File(root, "app/proguard-rules.pro"),
        )
        val hits = mutableListOf<String>()
        for (start in starts) {
            val files = if (start.isFile) listOf(start) else start.walkTopDown().filter { it.isFile }.toList()
            for (file in files) {
                if (file.extension.lowercase() in BINARY) continue
                val relative = file.relativeTo(root).path
                if (relative.startsWith("app/src/main/cpp/vendor/")) continue
                val text = runCatching { file.readText() }.getOrNull() ?: continue
                for (token in REFERENCE_TOKENS) {
                    if (text.contains(token)) hits += "$relative contains $token"
                }
            }
        }
        assertEquals(emptyList<String>(), hits)

        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains(".glb"))
        assertFalse(manifest.contains("cloud_patterns"))

        val java = File(root, "app/src/main/java")
        val kotlin = java.walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(kotlin.none { it.readText().contains("assets.list") })
        assertTrue(kotlin.none { it.readText().contains("Class.forName") })

        val dynamicGlb = kotlin.flatMap { file ->
            file.readLines().filter { it.contains(".glb") && (it.contains("\$") || it.contains(" + ")) }
                .map { "${file.name}: ${it.trim()}" }
        }
        for (line in dynamicGlb) {
            assertTrue(
                "Unexpected dynamic glb path: $line",
                line.contains("earth_dynamic_") || line.contains("\$name.glb"),
            )
        }
        val earth = File(java, "casa/archer/metatheatre/EarthRenderer.kt").readText()
        assertTrue(earth.contains("val layers = listOf(\"surface\", \"clouds\", \"atmosphere\")"))
        assertTrue(earth.contains("earth_dynamic_\$layer.glb"))
        val solar = File(java, "casa/archer/metatheatre/SolarRenderer.kt").readText()
        assertTrue(solar.contains("listOf(\"solar_body\", \"solar_rings\", \"solar_corona\", \"solar_stars\")"))
        val lounge = File(java, "casa/archer/metatheatre/Lounge.kt").readText()
        assertTrue(lounge.contains("internal val HULL_URI = \"hull.glb\".toUri()"))
        val weather = File(java, "casa/archer/metatheatre/WeatherCloudMap.kt").readText()
        assertTrue(weather.contains("weather/cloud_structure.png"))
        assertFalse(weather.contains("cloud_patterns"))
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

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        val EXCLUDED = listOf(
            "earth_day.glb",
            "earth_night.glb",
            "earth_twilight.glb",
            "weather/cloud_patterns.png",
        )
        val REFERENCE_TOKENS = listOf(
            "earth_day.glb",
            "earth_night.glb",
            "earth_twilight.glb",
            "earth_day",
            "earth_night",
            "earth_twilight",
            "cloud_patterns",
        )
        val ART_SOURCES = listOf(
            "art/exports/earth/earth_day.glb" to "059087456f3020b284d2a546dc73bab22f329afd4e6aa2181aa970672c5c227b",
            "art/exports/earth/earth_night.glb" to "68607ada9cb5496e84994ded321d92550ebdb95421bf10f8f9f7310507d26935",
            "art/exports/earth/earth_twilight.glb" to "2c105686170ed06441895b82331393a93a1175fec37f0605e6403acbc4855668",
            "art/textures/weather/cloud_patterns.png" to "9a774ee3925829c5bf16e3c080c11eb50196f2f1cbb74eb08cf8621168df5362",
        )
        val ART_REFERENCES = listOf(
            "art/exports/earth/earth_day.fbx",
            "art/exports/earth/earth_night.fbx",
            "art/exports/earth/earth_twilight.fbx",
            "art/blender/earth.blend",
            "art/blender/weather_patterns.blend",
        )
        val REQUIRED = listOf(
            "earth_dynamic_surface.glb",
            "earth_dynamic_clouds.glb",
            "earth_dynamic_atmosphere.glb",
            "earth/day.jpg",
            "earth/night.jpg",
            "earth/normal.jpg",
            "earth/ocean.jpg",
            "earth/clouds.png",
            "weather/cloud_structure.png",
            "hull.glb",
            "lounge_chair.glb",
            "iss.glb",
            "space_roadster.glb",
            "solar_body.glb",
            "solar_rings.glb",
            "solar_corona.glb",
            "solar_stars.glb",
        )
        val BINARY = setOf("glb", "png", "jpg", "jpeg", "tif", "tiff", "exr", "fbx", "so", "bin", "blend")
    }
}
