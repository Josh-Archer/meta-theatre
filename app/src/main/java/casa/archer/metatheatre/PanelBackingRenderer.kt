package casa.archer.metatheatre

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Vector3
import com.meta.spatial.core.Vector4
import com.meta.spatial.runtime.AlphaMode
import com.meta.spatial.runtime.MaterialSidedness
import com.meta.spatial.runtime.SceneMaterial
import com.meta.spatial.runtime.SceneMaterialAttribute
import com.meta.spatial.runtime.SceneMaterialDataType
import com.meta.spatial.runtime.SceneMesh
import com.meta.spatial.uiset.theme.SpatialShapes

/** Opaque depth backing follows the exact UI Set corner token, including after panel resize. */
internal class PanelBackingRenderer(private val activity: TheatreActivity) {
    private val materials = mutableListOf<SceneMaterial>()
    fun create() {
        for ((name, dimensions) in listOf("library" to Size(1600f, 900f), "player" to Size(560f, 1640f))) {
            val radius = SpatialShapes().large.topStart.toPx(dimensions, Density(1f))
            val material = SceneMaterial.custom("panel_backing", arrayOf(
                SceneMaterialAttribute("corner", SceneMaterialDataType.Vector4)), AlphaMode.OPAQUE).apply {
                setAttribute("corner", Vector4(radius / dimensions.width, radius / dimensions.height, 0f, 0f))
                setSidedness(MaterialSidedness.DOUBLE_SIDED)
            }
            materials.add(material)
            activity.registerMeshCreator("mesh://horizon_$name") { _: Entity ->
                SceneMesh.quad(Vector3(-.5f, -.5f, 0f), Vector3(.5f, .5f, 0f), material)
            }
        }
    }
    fun unregister() {
        activity.unregisterMeshCreator("mesh://horizon_library")
        activity.unregisterMeshCreator("mesh://horizon_player")
    }
    fun release() { materials.forEach { it.destroy() }; materials.clear() }
}
