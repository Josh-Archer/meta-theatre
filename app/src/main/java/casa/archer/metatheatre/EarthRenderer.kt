package casa.archer.metatheatre

import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.net.toUri
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.core.Vector3
import com.meta.spatial.core.Vector4
import com.meta.spatial.runtime.AddressMode
import com.meta.spatial.runtime.AlphaMode
import com.meta.spatial.runtime.BlendMode
import com.meta.spatial.runtime.DepthWrite
import com.meta.spatial.runtime.DepthTest
import com.meta.spatial.runtime.SamplerConfig
import com.meta.spatial.runtime.SceneMaterial
import com.meta.spatial.runtime.SceneMaterialAttribute
import com.meta.spatial.runtime.SceneMaterialDataType
import com.meta.spatial.runtime.SceneMesh
import com.meta.spatial.runtime.SceneTexture
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Transform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** Three shared layers. Civil-time lighting needs only one uniform update per second. */
class EarthRenderer(private val activity: TheatreActivity) {
    private val materials = mutableListOf<SceneMaterial>()
    private val textures = mutableListOf<SceneTexture>()
    private val creators = mutableListOf<String>()
    private val entities = mutableListOf<Entity>()
    private var worldFrame = Pose()
    private var skyFrame = Pose()
    private var spin = 0f
    private var cloudMaterial: SceneMaterial? = null
    private var surfaceMaterial: SceneMaterial? = null
    private var originalCloudTexture: SceneTexture? = null
    private var weatherTexture: SceneTexture? = null
    private var weatherMap: WeatherCloudMap? = null
    private fun pose() = EarthExploration.renderPose(worldFrame, spin)
    internal fun target(): VisitorTarget? = if (entities.size != 3) null else
        VisitorTarget("earth_dynamic_surface.glb", pose(), 1f, RADIUS * 1.012f)

    fun setExploration(frame: Pose, spinDegrees: Float, lightingFrame: Pose) {
        val moved = skyFrame != lightingFrame
        worldFrame = Pose(frame.t.copy(), frame.q.copy())
        skyFrame = Pose(lightingFrame.t.copy(), lightingFrame.q.copy())
        spin = spinDegrees
        entities.forEach { it.setComponent(Transform(pose())) }
        if (moved) update()
    }

    @OptIn(SpatialSDKExperimentalAPI::class)
    suspend fun create() {
        check(SceneMaterial.supportsCustomShaders()) { "Earth requires a Spatial SDK backend with custom shader support" }
        val layers = listOf("surface", "clouds", "atmosphere")
        val files = withContext(Dispatchers.IO) {
            layers.associateWith { layer ->
                val name = "earth_dynamic_$layer.glb"
                File(activity.cacheDir, name).apply {
                    activity.assets.open(name).use { input -> outputStream().use { input.copyTo(it) } }
                }
            }
        }
        // Decode off the UI thread; native texture/material upload and scene mutation stay on main.
        val sampler = SamplerConfig(addressModeU = AddressMode.REPEAT, addressModeV = AddressMode.CLAMP_TO_EDGE)
        val gpuMaps = mutableMapOf<String, SceneTexture>()
        // Upload one source at a time, including native 8K day/city maps (128 MiB decoded).
        for (name in listOf("day.jpg", "night.jpg", "normal.jpg", "ocean.jpg", "clouds.png")) {
            val bitmap = withContext(Dispatchers.IO) {
                activity.assets.open("earth/$name").use { input ->
                    checkNotNull(BitmapFactory.decodeStream(input)) { "Cannot decode Earth map $name" }
                }
            }
            try {
                gpuMaps[name] = SceneTexture(bitmap, sampler).also { textures.add(it) }
            } finally {
                bitmap.recycle()
            }
        }
        val surface = material("earth_surface", listOf("dayMap", "nightMap", "normalMap", "oceanMap", "cloudMap"))
        surface.setTexture("dayMap", gpuMaps.getValue("day.jpg"))
        surface.setTexture("nightMap", gpuMaps.getValue("night.jpg"))
        surface.setTexture("normalMap", gpuMaps.getValue("normal.jpg"))
        surface.setTexture("oceanMap", gpuMaps.getValue("ocean.jpg"))
        val clouds = material("earth_clouds", listOf("cloudMap"), transparent = true)
        originalCloudTexture = gpuMaps.getValue("clouds.png")
        surfaceMaterial = surface
        surface.setTexture("cloudMap", originalCloudTexture!!)
        cloudMaterial = clouds
        clouds.setTexture("cloudMap", originalCloudTexture!!)
        val atmosphere = material("earth_atmosphere", emptyList(), transparent = true)
        clouds.setRenderOrder(10)
        atmosphere.setRenderOrder(20)
        val byLayer = mapOf("surface" to surface, "clouds" to clouds, "atmosphere" to atmosphere)
        update()
        layers.forEach { layer ->
            val creator = "mesh://earth_$layer"
            activity.registerMeshCreator(creator) { _: Entity ->
                SceneMesh.loadLocalFile(files.getValue(layer).absolutePath, listOf(byLayer.getValue(layer)))
            }
            creators.add(creator)
            entities.add(Entity.create(
                Mesh(creator.toUri(), hittable = MeshCollision.NoCollision),
                Scale(Vector3(RADIUS, RADIUS, RADIUS)),
                Transform(pose()),
            ))
        }
        Log.i("Earth", "Dynamic Earth ready; local zone=${ZoneId.systemDefault()}, centered at equator/100W")
    }

    internal suspend fun setWeather(snapshot: WeatherSnapshot?) {
        val cloud = cloudMaterial ?: return
        val bitmap = if (snapshot == null) null else withContext(Dispatchers.Default) {
            val map = weatherMap ?: WeatherCloudMap.load(activity.assets).also { weatherMap = it }
            map.build(snapshot)
        }
        val next = try {
            bitmap?.let { SceneTexture(it, SamplerConfig(addressModeU = AddressMode.REPEAT, addressModeV = AddressMode.CLAMP_TO_EDGE)) }
        } finally { bitmap?.recycle() }
        val cloudMap = next ?: checkNotNull(originalCloudTexture)
        cloud.setTexture("cloudMap", cloudMap)
        surfaceMaterial?.setTexture("cloudMap", cloudMap)
        val previous = weatherTexture
        weatherTexture = next
        previous?.destroy()
    }

    private fun material(shader: String, maps: List<String>, transparent: Boolean = false): SceneMaterial {
        val attributes = arrayOf(
            SceneMaterialAttribute("sunPosition", SceneMaterialDataType.Vector4),
            *maps.map { SceneMaterialAttribute(it, SceneMaterialDataType.Texture2D) }.toTypedArray(),
        )
        return SceneMaterial.custom(shader, attributes, if (transparent) AlphaMode.TRANSLUCENT else AlphaMode.OPAQUE).apply {
            setDepthTest(DepthTest.LESS_OR_EQUAL)
            setDepthWrite(if (transparent) DepthWrite.DISABLE else DepthWrite.ENABLE)
            if (transparent) {
                setBlendMode(BlendMode.TRANSLUCENT)
                setDepthWrite(DepthWrite.DISABLE)
            }
        }.also { materials.add(it) }
    }

    fun update(instant: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()) {
        val position = SolarSystem.shaderSunPosition(skyFrame * SolarSystem.sunPosition(instant, zone))
        materials.forEach { it.setAttribute("sunPosition", position) }
    }

    fun unregister() {
        creators.forEach { activity.unregisterMeshCreator(it) }
        creators.clear()
    }

    /** Called after Spatial SDK shuts down its scene and cached meshes. */
    fun release() {
        cloudMaterial = null
        surfaceMaterial = null
        weatherTexture?.destroy(); weatherTexture = null
        weatherMap = null
        materials.forEach { it.destroy() }
        textures.forEach { it.destroy() }
        entities.clear()
        materials.clear()
        textures.clear()
    }

    companion object {
        const val RADIUS = 90f
        val CENTER = Vector3(18f, -35f, 150f)
        val SEAT_EYE = Vector3(0f, 1.6f, 0f)
        fun orientation(): Quaternion = Quaternion.fromDirection(SEAT_EYE - CENTER, Vector3(0f, 1f, 0f))
    }
}
