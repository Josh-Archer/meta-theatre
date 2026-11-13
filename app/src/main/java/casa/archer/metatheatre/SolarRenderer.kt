package casa.archer.metatheatre

import android.graphics.BitmapFactory
import androidx.core.net.toUri
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.core.Vector3
import com.meta.spatial.core.Vector4
import com.meta.spatial.runtime.AddressMode
import com.meta.spatial.runtime.AlphaMode
import com.meta.spatial.runtime.BlendMode
import com.meta.spatial.runtime.DepthTest
import com.meta.spatial.runtime.DepthWrite
import com.meta.spatial.runtime.SamplerConfig
import com.meta.spatial.runtime.SceneMaterial
import com.meta.spatial.runtime.SceneMaterialAttribute
import com.meta.spatial.runtime.SceneMaterialDataType
import com.meta.spatial.runtime.SceneMesh
import com.meta.spatial.runtime.SceneTexture
import com.meta.spatial.runtime.SortOrder
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Transform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** Date-based Moon phases, real body-size ratios, and one moving Sun for the entire environment. */
class SolarRenderer(private val activity: TheatreActivity) {
    private val materials = mutableListOf<SceneMaterial>()
    private val textures = mutableListOf<SceneTexture>()
    private val creators = mutableListOf<String>()
    private val litMaterials = mutableListOf<Pair<SolarSystem.Body, SceneMaterial>>()
    private var moonEntity: Entity? = null
    private val sunEntities = mutableListOf<Entity>()
    private var quietCinema = false
    private var lastSunDirection: Vector3? = null
    private var worldFrame = Pose()
    private val fixedBodies = mutableListOf<Pair<SolarSystem.Body, Entity>>()
    private var starsEntity: Entity? = null
    private var earthRadius = EarthRenderer.RADIUS
    private var earthViewCenter = EarthRenderer.CENTER
    private var earthViewer = EarthRenderer.SEAT_EYE
    private fun displayBody(body: SolarSystem.Body) = EarthClearance.body(body, earthViewCenter, earthViewer, earthRadius)
    fun setEarthView(earth: Vector3, viewer: Vector3, radius: Float = EarthRenderer.RADIUS) {
        earthRadius = radius
        earthViewCenter = worldFrame.inverse() * earth
        earthViewer = worldFrame.inverse() * viewer
        fixedBodies.forEach { (body, entity) ->
            val placed = displayBody(body)
            entity.setComponent(Transform(worldFrame * Pose(placed.center, placed.rotation)))
        }
    }

    fun setWorldFrame(frame: Pose) {
        if (worldFrame == frame) return
        worldFrame = Pose(frame.t.copy(), frame.q.copy())
        fixedBodies.forEach { (body, entity) ->
            val placed = displayBody(body)
            entity.setComponent(Transform(worldFrame * Pose(placed.center, placed.rotation)))
        }
        starsEntity?.setComponent(Transform(Pose(Vector3(0f, 0f, 0f), worldFrame.q)))
        update()
    }

    @OptIn(SpatialSDKExperimentalAPI::class)
    suspend fun create() {
        check(SceneMaterial.supportsCustomShaders()) { "Solar environment requires custom shader support" }
        val files = withContext(Dispatchers.IO) {
            listOf("solar_body", "solar_rings", "solar_corona", "solar_stars").associateWith { name ->
                File(activity.cacheDir, "$name.glb").apply {
                    activity.assets.open("$name.glb").use { input -> outputStream().use { input.copyTo(it) } }
                }
            }
        }
        val ringTexture = texture("saturn_rings.png", radial = true)
        val moonNormal = texture("moon_normal.png")
        val stars = material("solar_stars", listOf("colorMap")).apply {
            setTexture("colorMap", texture("stars.jpg"))
            setSortOrder(SortOrder.PREPROCESS)
            setRenderOrder(-100)
            setDepthWrite(DepthWrite.DISABLE)
            setDepthTest(DepthTest.ALWAYS)
        }
        starsEntity = entity("stars", files.getValue("solar_stars"), stars, SolarSystem.STAR_RADIUS,
            Pose(Vector3(0f, 0f, 0f), worldFrame.q))
        for (body in SolarSystem.planets.filter { it.name != "earth" } + SolarSystem.moon) {
            val material = material("solar_surface", listOf("colorMap", "ringMap", "normalMap")).apply {
                setTexture("colorMap", texture("${body.name}.jpg"))
                setTexture("ringMap", ringTexture)
                setTexture("normalMap", moonNormal)
                setAttribute("parameters", Vector4(0f, if (body.name == "saturn") 1f else 0f, SolarSystem.RING_INNER, SolarSystem.RING_OUTER))
                // Artistic, restrained surface profiles; sizes and Sun geometry remain physical.
                setAttribute("surfaceParameters", when (body.name) {
                    "moon" -> Vector4(0.65f, 1f, 1f, 0f)
                    "mercury" -> Vector4(0.45f, 1f, 0f, 0f)
                    "mars" -> Vector4(0.25f, 1f, 0f, 0.025f)
                    "venus" -> Vector4(0f, 0.90f, 0f, 0.08f)
                    "jupiter" -> Vector4(0f, 0.88f, 0f, 0.035f)
                    "saturn" -> Vector4(0f, 0.90f, 0f, 0.055f)
                    else -> Vector4(0f, 0.95f, 0f, 0.065f)
                })
            }
            litMaterials.add(body to material)
            val placed = if (body.name == "moon") body else displayBody(body)
            val globe = entity(body.name, files.getValue("solar_body"), material, body.radius, worldFrame * Pose(placed.center, placed.rotation))
            if (body.name == "moon") moonEntity = globe else fixedBodies.add(body to globe)
        }
        val saturn = SolarSystem.planets.first { it.name == "saturn" }
        val rings = material("solar_rings", listOf("ringMap"), transparent = true).apply {
            setTexture("ringMap", ringTexture)
            setAttribute("parameters", Vector4(0f, 0f, SolarSystem.RING_INNER, SolarSystem.RING_OUTER))
            setRenderOrder(5)
            setDepthTest(DepthTest.LESS_OR_EQUAL)
        }
        litMaterials.add(saturn to rings)
        fixedBodies.add(saturn to entity("rings", files.getValue("solar_rings"), rings, saturn.radius,
            displayBody(saturn).let { worldFrame * Pose(it.center, it.rotation) }))
        val sun = material("solar_surface", listOf("colorMap", "ringMap", "normalMap")).apply {
            setTexture("colorMap", texture("sun.jpg"))
            setTexture("ringMap", ringTexture)
            setTexture("normalMap", moonNormal)
            setAttribute("parameters", Vector4(1f, 0f, 0f, 0f))
        }
        val corona = material("solar_corona", emptyList(), transparent = true).apply { setRenderOrder(30) }
        val sunPosition = worldFrame * SolarSystem.sunPosition(Instant.now(), ZoneId.systemDefault())
        val sunPose = Pose(SolarSystem.sunDisplayPosition(sunPosition))
        val sunRadius = SolarSystem.sunDisplayRadius(sunPosition)
        sunEntities.add(entity("sun", files.getValue("solar_body"), sun, sunRadius, sunPose))
        sunEntities.add(entity("corona", files.getValue("solar_corona"), corona, sunRadius, sunPose))
        update()
    }

    private suspend fun texture(name: String, radial: Boolean = false): SceneTexture {
        val bitmap = withContext(Dispatchers.IO) {
            activity.assets.open("solar/$name").use { checkNotNull(BitmapFactory.decodeStream(it)) { "Cannot decode $name" } }
        }
        try {
            val sampler = SamplerConfig(
                addressModeU = if (radial) AddressMode.CLAMP_TO_EDGE else AddressMode.REPEAT,
                addressModeV = AddressMode.CLAMP_TO_EDGE,
            )
            return SceneTexture(bitmap, sampler).also { textures.add(it) }
        } finally { bitmap.recycle() }
    }

    private fun material(shader: String, maps: List<String>, transparent: Boolean = false): SceneMaterial =
        SceneMaterial.custom(shader, arrayOf(
            SceneMaterialAttribute("sunPosition", SceneMaterialDataType.Vector4),
            SceneMaterialAttribute("parameters", SceneMaterialDataType.Vector4),
            SceneMaterialAttribute("surfaceParameters", SceneMaterialDataType.Vector4),
            *maps.map { SceneMaterialAttribute(it, SceneMaterialDataType.Texture2D) }.toTypedArray(),
        ), if (transparent) AlphaMode.TRANSLUCENT else AlphaMode.OPAQUE).apply {
            setDepthTest(DepthTest.LESS_OR_EQUAL)
            setDepthWrite(if (transparent) DepthWrite.DISABLE else DepthWrite.ENABLE)
            setAttribute("sunPosition", Vector4(0f, 0f, 1f, 0f))
            setAttribute("parameters", Vector4(0f, 0f, 0f, 0f))
            setAttribute("surfaceParameters", Vector4(0f, 1f, 0f, 0f))
            if (transparent) {
                setBlendMode(BlendMode.TRANSLUCENT)
                setDepthWrite(DepthWrite.DISABLE)
            }
        }.also { materials.add(it) }

    private fun entity(name: String, file: File, material: SceneMaterial, radius: Float, pose: Pose): Entity {
        val creator = "mesh://solar_$name"
        activity.registerMeshCreator(creator) { _: Entity -> SceneMesh.loadLocalFile(file.absolutePath, listOf(material)) }
        creators.add(creator)
        return Entity.create(
            Mesh(creator.toUri(), hittable = MeshCollision.NoCollision),
            Scale(Vector3(radius, radius, radius)), Transform(pose),
        )
    }

    fun update(instant: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault(), worldSun: Vector3? = null) {
        val canonicalSun = worldSun?.let { worldFrame.inverse() * it } ?: SolarSystem.sunPosition(instant, zone)
        val position = worldFrame * canonicalSun
        val moon = SolarSystem.moonAt(instant, canonicalSun)
        moonEntity?.setComponent(Transform(worldFrame * Pose(moon.center, moon.rotation)))
        val shaderPosition = SolarSystem.shaderSunPosition(position)
        for ((_, material) in litMaterials) {
            material.setAttribute("sunPosition", shaderPosition)
        }
        val displayPosition = SolarSystem.sunDisplayPosition(position)
        val radius = SolarSystem.sunDisplayRadius(position)
        sunEntities.forEach {
            it.setComponent(Transform(Pose(displayPosition)))
            it.setComponent(Scale(Vector3(radius, radius, radius)))
        }
        val direction = (position - EarthRenderer.SEAT_EYE).normalize()
        lastSunDirection = direction
        applySceneLighting(direction)
    }

    /** Dim SDK PBR scene lighting while preserving the shared Sun direction and custom shaders. */
    fun setQuietCinema(enabled: Boolean) {
        if (quietCinema == enabled) return
        quietCinema = enabled
        lastSunDirection?.let(::applySceneLighting)
    }

    private fun applySceneLighting(direction: Vector3) {
        val levels = QuietCinemaLighting.levels(quietCinema)
        // Stock SDK PBR uses +dot(normal,sunDirection): point toward the same visible Sun.
        // The entire environment shares this one update per second, including the chair.
        activity.scene.setLightingEnvironment(
            ambientColor = Vector3(0.06f * levels.ambient, 0.07f * levels.ambient, 0.09f * levels.ambient),
            sunColor = Vector3(1.4f * levels.sun, 1.32f * levels.sun, 1.15f * levels.sun),
            sunDirection = direction, environmentIntensity = 0.18f * levels.environment,
        )
    }

    fun unregister() {
        creators.forEach { activity.unregisterMeshCreator(it) }
        creators.clear()
    }

    /** Run only after the SDK has destroyed its cached meshes and scene objects. */
    fun release() {
        materials.forEach { it.destroy() }
        textures.forEach { it.destroy() }
        materials.clear(); textures.clear(); litMaterials.clear(); sunEntities.clear(); moonEntity = null
        fixedBodies.clear(); starsEntity = null
    }
}
