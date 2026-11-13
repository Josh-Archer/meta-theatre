package casa.archer.metatheatre

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.content.pm.ApplicationInfo
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
import com.meta.spatial.toolkit.Visible
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
    private data class DetailPart(val tile: EarthDetailTile, val entity: Entity, var visible: Boolean = false)
    private val detailParts = mutableListOf<DetailPart>()
    private var detailRegion: EarthDetailRegion? = null
    internal fun surfaceEntity(): Entity? = if (activity.intent.getBooleanExtra("metatheatre.test_detail_registration", false)) entities.getOrNull(3) else entities.firstOrNull()
    private var worldFrame = Pose()
    private var skyFrame = Pose()
    private var spin = 0f
    private var cloudMaterial: SceneMaterial? = null
    private var surfaceMaterial: SceneMaterial? = null
    private var originalCloudTexture: SceneTexture? = null
    private var weatherTexture: SceneTexture? = null
    private val weatherSources = WeatherCloudCache { WeatherCloudMap.load(activity.assets) }
    private var weatherPresentation = 0
    private var weatherAppearance: List<WeatherReading>? = null
    internal val retainedWeatherSourceBytes get() = weatherSources.retainedBytes
    internal val weatherSourceLoads get() = weatherSources.loadCount
    internal var weatherUploads = 0
        private set
    private var clearEarth = false
    private var detailTexture: SceneTexture? = null
    private var detailNormalTexture: SceneTexture? = null
    private var detailMaterial: SceneMaterial? = null
    private var renderScale = 1f
    private fun pose() = EarthExploration.renderPose(worldFrame, spin)
    internal fun target(): VisitorTarget? = if (entities.size < 3) null else
        VisitorTarget("earth_dynamic_surface.glb", pose(), renderScale, RADIUS * EarthExploration.SOLID_RADIUS)

    fun setExploration(frame: Pose, spinDegrees: Float, lightingFrame: Pose, scale: Float = 1f) {
        renderScale = scale
        val moved = skyFrame != lightingFrame
        worldFrame = Pose(frame.t.copy(), frame.q.copy())
        skyFrame = Pose(lightingFrame.t.copy(), lightingFrame.q.copy())
        spin = spinDegrees
        entities.forEachIndexed { index, entity ->
            entity.setComponent(Transform(pose()))
            val radius = RADIUS * renderScale * if (index == 3) 1.00002f else 1f
            entity.setComponent(Scale(Vector3(radius, radius, radius)))
        }
        // Hidden partitions receive their current pose only when made visible.
        detailParts.forEach { if (it.visible) placeDetail(it.entity) }
        if (moved) update()
    }

    private fun placeDetail(entity: Entity) {
        val radius = RADIUS * renderScale * 1.00002f
        entity.setComponent(Transform(pose()))
        entity.setComponent(Scale(Vector3(radius, radius, radius)))
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
        val registration = activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
            activity.intent.getBooleanExtra("metatheatre.test_detail_registration", false)
        val detailFiles = withContext(Dispatchers.IO) {
            fun cachedAsset(name: String) = File(activity.cacheDir, name).apply {
                parentFile?.mkdirs()
                activity.assets.open(name).use { input -> outputStream().use { input.copyTo(it) } }
            }
            if (registration) {
                listOf(null to cachedAsset("earth_dynamic_surface_detail.glb"))
            } else {
                activity.assets.open("earth/detail/manifest.json").bufferedReader().use {
                    EarthDetailTiles.parse(it.readText())
                }.map { tile -> tile to cachedAsset(tile.asset) }
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
        val detail = material("earth_detail", listOf("dayMap", "nightMap", "normalMap", "oceanMap", "cloudMap"), transparent = true)
        detailMaterial = detail
        for ((binding, file) in listOf("dayMap" to "day.jpg", "nightMap" to "night.jpg", "normalMap" to "normal.jpg", "oceanMap" to "ocean.jpg", "cloudMap" to "clouds.png")) {
            detail.setTexture(binding, gpuMaps.getValue(file))
        }
        detail.setRenderOrder(5)
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
                Mesh(creator.toUri(), hittable = if (layer == "surface" &&
                    activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
                    activity.intent.getBooleanExtra("metatheatre.test_earth_registration", false) &&
                    !activity.intent.getBooleanExtra("metatheatre.test_detail_registration", false))
                    MeshCollision.LineTest else MeshCollision.NoCollision),
                Scale(Vector3(RADIUS * renderScale, RADIUS * renderScale, RADIUS * renderScale)),
                Transform(pose()),
            ))
        }
        // Each partition keeps the exact original positions, normals and UVs.
        // Create hidden entities once so region changes do not rebuild geometry.
        detailFiles.forEachIndexed { index, (tile, file) ->
            val creator = "mesh://earth_detail_$index"
            activity.registerMeshCreator(creator) { _: Entity ->
                SceneMesh.loadLocalFile(file.absolutePath, listOf(detail))
            }
            creators.add(creator)
            val detailRadius = RADIUS * renderScale * 1.00002f
            val entity = Entity.create(Mesh(creator.toUri(), hittable = if (registration)
                MeshCollision.LineTest else MeshCollision.NoCollision),
                Scale(Vector3(detailRadius, detailRadius, detailRadius)), Transform(pose()), Visible(registration))
            if (tile == null) entities.add(entity) else detailParts.add(DetailPart(tile, entity))
        }
        setClearEarth(clearEarth)
        Log.i("Earth", "Dynamic Earth ready; local zone=${ZoneId.systemDefault()}, centered at equator/100W")
    }

    fun setClearEarth(clear: Boolean) {
        clearEarth = clear
        entities.getOrNull(1)?.setComponent(Visible(!clear))
        materials.forEach { it.setAttribute("cloudVisibility", Vector4(if (clear) 0f else 1f, 0f, 0f, 0f)) }
    }

    /** Only the small regional overlay changes; global maps are never decoded or rebuilt here. */
    internal fun setDetail(region: EarthDetailRegion, bitmap: Bitmap) {
        val material = detailMaterial ?: return
        val next = SceneTexture(bitmap, SamplerConfig(addressModeU = AddressMode.CLAMP_TO_EDGE, addressModeV = AddressMode.CLAMP_TO_EDGE))
        material.setTexture("dayMap", next)
        material.setAttribute("detailRegion", Vector4(region.west.toFloat(), region.north.toFloat(), region.span.toFloat(), region.span.toFloat()))
        entities.getOrNull(3)?.setComponent(Visible(true))
        if (detailRegion != region) {
            detailParts.forEach { part ->
                val visible = part.tile.intersects(region)
                if (visible != part.visible) {
                    if (visible) placeDetail(part.entity)
                    part.entity.setComponent(Visible(visible))
                    part.visible = visible
                }
            }
            detailRegion = region
            val visible = detailParts.filter { it.visible }
            if (visible.isNotEmpty()) Log.i("Earth", "Detail region: ${visible.size} parts, ${visible.sumOf { it.tile.triangles }} triangles")
        }
        val previous = detailTexture
        detailTexture = next
        previous?.destroy()
    }

    internal fun setTerrain(bounds: EarthTerrain, bitmap: Bitmap) {
        val material = detailMaterial ?: return
        val next = SceneTexture(bitmap, SamplerConfig(addressModeU = AddressMode.CLAMP_TO_EDGE, addressModeV = AddressMode.CLAMP_TO_EDGE))
        material.setTexture("normalMap", next)
        material.setAttribute("terrainRegion", Vector4(bounds.west.toFloat(), bounds.north.toFloat(), bounds.width.toFloat(), bounds.height.toFloat()))
        val previous = detailNormalTexture
        detailNormalTexture = next
        previous?.destroy()
    }

    internal suspend fun setWeather(snapshot: WeatherSnapshot?) {
        val cloud = cloudMaterial ?: return
        val appearance = snapshot?.appearance()
        // Model timestamps can advance without changing any rendered cloud input.
        if (snapshot != null && appearance == weatherAppearance) return
        // Null restores the authored cloud texture. Regional fallback uses that path
        // and keeps the CPU source arrays. Disable bumps [weatherPresentation] so an
        // in-flight rebuild cannot upload after the user turned weather off.
        val presentation = weatherPresentation
        var bitmap: Bitmap? = null
        val next = try {
            if (snapshot != null) withContext(Dispatchers.Default) {
                // Retain ownership before returning across the cancellable dispatcher boundary.
                bitmap = weatherSources.build(snapshot)
            }
            if (cloudMaterial == null || presentation != weatherPresentation ||
                (snapshot != null && bitmap == null)) return
            bitmap?.let { SceneTexture(it, SamplerConfig(addressModeU = AddressMode.REPEAT, addressModeV = AddressMode.CLAMP_TO_EDGE)) }
        } finally { bitmap?.recycle() }
        val cloudMap = next ?: checkNotNull(originalCloudTexture)
        cloud.setTexture("cloudMap", cloudMap)
        surfaceMaterial?.setTexture("cloudMap", cloudMap)
        detailMaterial?.setTexture("cloudMap", cloudMap)
        val style = Vector4(if (snapshot == null) 0f else 1f, 0f, 0f, 0f)
        cloud.setAttribute("cloudStyle", style)
        surfaceMaterial?.setAttribute("cloudStyle", style)
        detailMaterial?.setAttribute("cloudStyle", style)
        val previous = weatherTexture
        weatherTexture = next
        weatherAppearance = appearance
        if (next != null) weatherUploads += 1
        previous?.destroy()
    }

    private fun material(shader: String, maps: List<String>, transparent: Boolean = false): SceneMaterial {
        val attributes = arrayOf(
            SceneMaterialAttribute("sunPosition", SceneMaterialDataType.Vector4),
            SceneMaterialAttribute("cloudStyle", SceneMaterialDataType.Vector4),
            SceneMaterialAttribute("cloudVisibility", SceneMaterialDataType.Vector4),
            SceneMaterialAttribute("detailRegion", SceneMaterialDataType.Vector4),
            SceneMaterialAttribute("terrainRegion", SceneMaterialDataType.Vector4),
            *maps.map { SceneMaterialAttribute(it, SceneMaterialDataType.Texture2D) }.toTypedArray(),
        )
        return SceneMaterial.custom(shader, attributes, if (transparent) AlphaMode.TRANSLUCENT else AlphaMode.OPAQUE).apply {
            setAttribute("cloudStyle", Vector4(0f, 0f, 0f, 0f))
            setAttribute("cloudVisibility", Vector4(if (clearEarth) 0f else 1f, 0f, 0f, 0f))
            setAttribute("detailRegion", Vector4(0f, 0f, 0f, 0f))
            setAttribute("terrainRegion", Vector4(0f, 0f, 0f, 0f))
            setDepthTest(DepthTest.LESS_OR_EQUAL)
            setDepthWrite(if (transparent) DepthWrite.DISABLE else DepthWrite.ENABLE)
            if (transparent) {
                setBlendMode(BlendMode.TRANSLUCENT)
                setDepthWrite(DepthWrite.DISABLE)
            }
        }.also { materials.add(it) }
    }

    fun update(instant: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault(), worldSun: Vector3? = null) {
        val position = SolarSystem.shaderSunPosition(worldSun ?: skyFrame * SolarSystem.sunPosition(instant, zone))
        materials.forEach { it.setAttribute("sunPosition", position) }
    }

    fun unregister() {
        creators.forEach { activity.unregisterMeshCreator(it) }
        creators.clear()
    }

    /** Drop the 56 MiB morphology and refuse another decode until weather is enabled again. */
    internal fun releaseWeatherSources() {
        weatherPresentation += 1
        val retained = weatherSources.retainsSources
        weatherSources.releaseForDisable()
        if (retained) Log.i("Weather", "Released inactive weather source buffers")
    }

    /** Drop the morphology under memory pressure. The next live rebuild may decode it again. */
    internal fun evictWeatherSources() {
        val retained = weatherSources.retainsSources
        weatherSources.evictForMemoryPressure()
        if (retained) Log.i("Weather", "Released weather source buffers under memory pressure")
    }

    internal fun allowWeatherSources() {
        weatherSources.allowReload()
    }

    /** Called after Spatial SDK shuts down its scene and cached meshes. */
    fun release() {
        weatherPresentation += 1
        cloudMaterial = null
        surfaceMaterial = null
        weatherTexture?.destroy(); weatherTexture = null
        weatherAppearance = null
        weatherSources.releaseForDisable()
        detailTexture?.destroy(); detailTexture = null
        detailNormalTexture?.destroy(); detailNormalTexture = null
        detailMaterial = null
        detailParts.clear()
        detailRegion = null
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
