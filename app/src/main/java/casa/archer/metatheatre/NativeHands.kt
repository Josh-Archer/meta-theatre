package casa.archer.metatheatre

import android.os.SystemClock
import android.util.Log
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.Scene

/** Reads only hand joints from the SDK-owned session. Owns no OpenXR instance or session. */
@OptIn(com.meta.spatial.core.SpatialSDKExperimentalAPI::class)
internal class NativeHands(private val scene: Scene) : AutoCloseable {
    private var handle = if (loaded) nativeOpen(scene.getOpenXrInstanceHandle(), scene.getOpenXrSessionHandle(),
        scene.getOpenXrGetInstanceProcAddrHandle()) else 0L
    init { if (handle == 0L) Log.w("TheatreHands", "hand joints unavailable: reader initialization") }
    private val buffer = FloatArray(218)
    private var nextSampleMs = 0L
    private var palms = emptyList<OpenPalm>()
    private var lastStatus = ""

    fun read(): List<OpenPalm> {
        if (handle == 0L) return emptyList()
        val now = SystemClock.uptimeMillis()
        if (now < nextSampleMs) return scenePalms()
        nextSampleMs = now + 16L
        val frame = scene.spatialInterface.getFrameTiming()
        val read = frame.isValid && nativeRead(handle, frame.predictedDisplayTimeNs, buffer)
        if (!read) buffer.fill(0f)
        palms = if (!read) emptyList() else (0..1).mapNotNull { side ->
            val base = side * 105
            if (buffer[base] != 1f) return@mapNotNull null
            val joints = (0..25).map { index ->
                val offset = base + 1 + index * 4
                HandJoint(HandPoint(buffer[offset], buffer[offset + 1], buffer[offset + 2]), buffer[offset + 3].toInt())
            }
            OpenHandPose.read(side, joints)
        }
        val status = "tracked=${(0..1).count { buffer[it * 105] == 1f }} open=${palms.size}"
        if (status != lastStatus) { lastStatus = status; Log.i("TheatreHands", "hand joints $status") }
        return scenePalms()
    }

    private fun scenePalms(): List<OpenPalm> {
        if (palms.isEmpty() || buffer[210] != 1f) return emptyList()
        val head = Pose(Vector3(buffer[211], buffer[212], buffer[213]),
            Quaternion(w = buffer[217], x = buffer[214], y = buffer[215], z = buffer[216]))
        // Use the head as the shared reference, including the SDK's physical recenter offset.
        val trackingToScene = scene.getViewerPose() * head.inverse()
        return palms.map { HandTrackingSpace.toScene(it, trackingToScene) }
    }

    override fun close() { if (handle != 0L) nativeClose(handle); handle = 0L; palms = emptyList() }
    private external fun nativeOpen(instance: Long, session: Long, proc: Long): Long
    private external fun nativeRead(handle: Long, predictedDisplayTime: Long, buffer: FloatArray): Boolean
    private external fun nativeClose(handle: Long)
    companion object {
        private val loaded = runCatching { System.loadLibrary("theatre_hands"); true }.getOrDefault(false)
    }
}
