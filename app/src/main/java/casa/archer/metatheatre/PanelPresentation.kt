package casa.archer.metatheatre

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.AlphaMode
import com.meta.spatial.runtime.PanelConfigOptions

internal enum class PointerPanel { LIBRARY, PLAYER }
internal data class PanelCursor(val panel: PointerPanel, val x: Float, val y: Float)
internal data class PanelBackingState(val pose: Pose, val size: ScreenSize, val visible: Boolean)

internal object PanelPresentation {
    fun configure(options: PanelConfigOptions) {
        // Compose alpha is needed for the movie's empty grab carrier and rounded menu edges.
        // Separate opaque geometry supplies the menu background and writes depth against space.
        options.alphaMode = AlphaMode.TRANSLUCENT
        options.includeGlass = false
        options.unlit = true
    }
    fun backing(pose: Pose, size: ScreenSize, visible: Boolean) = PanelBackingState(
        Pose(pose.t - pose.forward() * .008f, pose.q.copy()), size, visible)
    fun libraryBacking(pose: Pose, size: ScreenSize, watching: Boolean, fraction: Float) = backing(
        pose, ScreenSize(size.width*fraction.coerceAtLeast(.001f),size.height), !watching && fraction>.001f)
    /** Reticles are biased toward the ray origin, independent of a panel's front/back convention. */
    fun cursorPosition(point: Vector3, origin: Vector3) = point + (origin-point).normalize() * .025f
}
