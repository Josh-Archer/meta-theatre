package casa.archer.metatheatre

import android.util.Log
import android.os.SystemClock
import androidx.core.net.toUri
import com.meta.spatial.core.Color4
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Query
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Vector3
import com.meta.spatial.isdk.IsdkPanelDimensions
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.AvatarBody
import com.meta.spatial.toolkit.ControllerType
import com.meta.spatial.toolkit.Material
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Sphere
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible
import kotlin.math.abs

/**
 * Grip moves the screen with the controller. Pushing or pulling the controller,
 * or the thumbstick, changes how far the screen sits. Trigger on the face
 * is reported as a press, because the panel's own click path does not fire.
 */
class ScreenOverlays(
    val video: Entity,
    val player: Entity,
    val videoWidth: Float,
    val videoHeight: Float,
    val subtitles: Entity? = null,
)

internal class ScreenGripSystem(
    private val screen: Entity,
    private val onPress: (xNorm: Float, yNorm: Float) -> Unit,
    private val onScroll: (Int) -> Unit,
    private val onPlayerVisibility: (Boolean) -> Unit,
    private val playerTargets: PlayerHitTargets,
    private val onPlayerControl: (PlayerControl) -> Unit,
    private val onSeek: (Long) -> Unit,
    private val onSeekPreview: (Float?) -> Unit,
    private val onSeekFraction: (Float) -> Unit,
    private val onSizeSaved: (ScreenSize) -> Unit,
    private val readOpenHands: () -> List<OpenPalm>,
    private val onScreenHiddenChange: (Boolean) -> Unit,
    private val handsNavigating: () -> Boolean,
    private val hasSubtitleCues: () -> Boolean = { false },
) : SystemBase() {
    var watching: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            revealMotion.reset()
            handGestures.reset()
            openGestures.reset()
            stickSeek.clear()
            railSelections.clear()
            onSeekPreview(null)
            autoHide.setWatching(value && !screenHidden, SystemClock.uptimeMillis())
        }
    var screenHidden: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            handGestures.reset()
            railSelections.clear()
            grab = null
            screenMotion = null
            onSeekPreview(null)
            autoHide.setWatching(watching && !value, SystemClock.uptimeMillis())
            screen.setComponent(Visible(!watching && !value))
            syncOverlays(force = true)
        }
    var overlays: ScreenOverlays? = null
    private val autoHide = PlayerAutoHide()
    private val revealMotion = RailRevealMotion()
    private val handGestures = MovieHandGestures()
    private val openGestures = OpenHandGestures()
    private data class ScreenMotion(val start: Pose, val end: Vector3, val started: Long)
    private var screenMotion: ScreenMotion? = null
    private val stickSeek = mutableMapOf<Long, MovieStickSeek>()
    private val downState = mutableMapOf<Long, Boolean>()
    private val railSelections = mutableMapOf<Long, RailSelection>()
    private var lastInputSummary = ""
    private var lastResizeMs = 0L
    private var lastResizeLogMs = 0L
    private var wasHandResizing = false
    private var playerOpen = false
    private var playerFraction = 0f
    private var slideStartFraction = 0f
    private var slideStartedMs = 0L
    private var grab: Grab? = null
    private var lastOverlays: ScreenOverlays? = null
    private var lastOverlayPose: Pose? = null
    private var lastOverlayWidth = 0f
    private var lastOverlayHeight = 0f
    private var lastWatching = false
    private var lastScreenHidden = false
    private var lastSubtitlesVisible = false
    private var lastPlayerFraction = -1f
    private var observedSize: ScreenSize? = null
    private var savedSize: ScreenSize? = null
    private var sizeChangedMs = 0L
    private var cursorVisible = false
    private val cursor = Entity.create(
        Mesh("mesh://sphere".toUri(), hittable = MeshCollision.NoCollision),
        Sphere(0.035f),
        Material().apply {
            baseColor = Color4(red = 0.84f, green = 0.71f, blue = 0.43f, alpha = 1f)
            unlit = true
        },
        Transform(Pose(Vector3(0f, -2f, 0f))),
        Visible(false),
    )

    override fun execute() {
        try {
            step()
        } finally {
            updatePlayerVisibility()
            syncOverlays()
            val size = screenSize()
            val now = SystemClock.uptimeMillis()
            if (size != observedSize) { observedSize = size; sizeChangedMs = now }
            if (now - sizeChangedMs >= 750L) saveScreenSize()
        }
    }

    fun restoreSize(size: ScreenSize) {
        applySize(size)
        savedSize = size
        observedSize = size
        Log.i(TAG, "screen size restored width=${size.width} height=${size.height}")
    }

    fun saveScreenSize() {
        val size = screenSize()
        if (size == savedSize) return
        savedSize = size
        onSizeSaved(size)
        Log.i(TAG, "screen size saved width=${size.width} height=${size.height}")
    }

    fun playerInteraction() = autoHide.interact(SystemClock.uptimeMillis())

    private fun updatePlayerVisibility() {
        val now = SystemClock.uptimeMillis()
        autoHide.tick(now)
        if (playerOpen != autoHide.visible) {
            playerOpen = autoHide.visible
            slideStartFraction = playerFraction
            slideStartedMs = now
            onPlayerVisibility(playerOpen)
            Log.i(TAG, if (playerOpen) "player controls revealed" else "player controls hidden")
        }
        val progress = ((now - slideStartedMs).toFloat() / PlayerAutoHide.SLIDE_MS).coerceIn(0f, 1f)
        val eased = progress * progress * (3f - 2f * progress)
        val target = if (playerOpen) 1f else 0f
        playerFraction = slideStartFraction + (target - slideStartFraction) * eased
    }

    fun resize(factor: Float) {
        applySize(screenSize().resized(factor))
    }

    private fun applySize(size: ScreenSize) {
        screenMotion = null
        val current = screenSize()
        if (abs(size.width - current.width) < 0.005f) return
        val base = screen.getComponent<IsdkPanelDimensions>().dimensions
        val pose = screen.getComponent<Transform>().transform
        // Keep the lower edge reachable as a larger screen grows upward.
        val center = pose.t.plus(pose.up().times((size.height - current.height) * 0.5f))
        screen.setComponent(Transform(Pose(center, pose.q.copy())))
        // ISDK rewrites dimensions from PanelShapeConfig. Scale is the persistent size input.
        screen.setComponent(Scale(Vector3(size.width / base.x, size.height / base.y, 1f)))
        syncOverlays(force = true)
    }

    fun syncOverlays(force: Boolean = false) {
        val overlays = overlays ?: return
        val pose = runCatching { screen.getComponent<Transform>().transform }.getOrNull() ?: return
        val size = screenSize()
        val width = size.width
        val height = size.height
        val geometryChanged = force || lastOverlays !== overlays || lastOverlayPose != pose ||
            lastOverlayWidth != width || lastOverlayHeight != height || lastWatching != watching || lastScreenHidden != screenHidden
        val subtitlesVisible = watching && !screenHidden && hasSubtitleCues()
        if (!geometryChanged && lastPlayerFraction == playerFraction && lastSubtitlesVisible == subtitlesVisible) return
        val forward = pose.forward()
        val up = pose.up()
        val show = watching && !screenHidden
        val videoShift = if (show) Stage.VIDEO_FORWARD else -Stage.HIDDEN_BACK
        // OpenXR quad layers face local -Z in the SDK API; UI meshes face local +Z.
        val videoRotation = pose.q * Quaternion(pitch = 0f, yaw = 180f, roll = 0f)
        if (geometryChanged) {
            val videoPosition = if (screenHidden) Vector3(0f, -1000f, 0f) else pose.t.plus(forward.times(videoShift))
            overlays.video.setComponent(Transform(Pose(videoPosition, videoRotation)))
            overlays.video.setComponent(Visible(show))
            overlays.video.setComponent(scaleTo(width, height, overlays.videoWidth, overlays.videoHeight))
            overlays.subtitles?.setComponent(Transform(Pose(
                pose.t.plus(forward.times(Stage.PLAYER_FORWARD)), pose.q,
            )))
            overlays.subtitles?.setComponent(scaleTo(width, height, overlays.videoWidth, overlays.videoHeight))
        }
        if (geometryChanged || lastSubtitlesVisible != subtitlesVisible) {
            overlays.subtitles?.setComponent(Visible(subtitlesVisible))
        }
        val barShift = if (show) Stage.PLAYER_FORWARD else -Stage.HIDDEN_BACK
        val rail = SideRailPlacement.beside(ScreenSize(width, height))
        // The viewer's right is opposite the library panel's local +X.
        val right = pose.right().times(-1f)
        val railCenter = pose.t.plus(right.times(rail.right)).plus(up.times(rail.up))
        val barCenter = railCenter.plus(right.times((1f - playerFraction) * Stage.PLAYER_SLIDE))
            .plus(forward.times(barShift))
        overlays.player.setComponent(Transform(Pose(barCenter, pose.q)))
        overlays.player.setComponent(Visible(show && playerFraction > 0.001f))
        lastOverlays = overlays
        // SDK poses and vectors are mutable; retain independent values for comparison.
        lastOverlayPose = Pose(pose.t.copy(), pose.q.copy())
        lastOverlayWidth = width
        lastOverlayHeight = height
        lastWatching = watching
        lastScreenHidden = screenHidden
        lastSubtitlesVisible = subtitlesVisible
        lastPlayerFraction = playerFraction
    }

    private fun step() {
        val now = SystemClock.uptimeMillis()
        val navigating = handsNavigating()
        if (navigating) {
            handGestures.reset()
            openGestures.reset()
            screenMotion = null
            if (wasHandResizing) saveScreenSize()
            wasHandResizing = false
        }
        advanceScreenMove(now)
        val inputs = Query.where { has(Controller.id) }.eval().mapNotNull { entity ->
            if (!entity.isLocal()) return@mapNotNull null
            val controller = runCatching { entity.getComponent<Controller>() }.getOrNull() ?: return@mapNotNull null
            if (!controller.isActive || controller.type == ControllerType.EYES) return@mapNotNull null
            // The SDK also suspends ISDK hand interaction while navigating. Match that here.
            if (navigating && controller.type == ControllerType.HAND) return@mapNotNull null
            val pose = runCatching { entity.getComponent<Transform>().transform }.getOrNull() ?: return@mapNotNull null
            TrackedInput(entity, controller, pose)
        }.toList()
        val ids = inputs.map { it.entity.id }.toSet()
        val summary = "HAND=${inputs.count { it.controller.type == ControllerType.HAND }} CONTROLLER=${inputs.count { it.controller.type == ControllerType.CONTROLLER }}"
        if (summary != lastInputSummary) {
            lastInputSummary = summary
            Log.i(TAG, "input devices active $summary")
        }
        for (id in railSelections.keys.toList()) if (id !in ids) {
            val selection = railSelections.remove(id)
            autoHide.select(id, false, now)
            if (selection?.target == PlayerTarget.Seek) onSeekPreview(null)
        }
        downState.keys.retainAll(ids)
        stickSeek.keys.retainAll(ids)
        revealMotion.retain(ids)

        var hovered: Aim? = null
        var libraryScroll = 0
        val handAims = mutableMapOf<Long, Aim>()
        val hands = mutableListOf<HandInput>()
        for (input in inputs) {
            val id = input.entity.id
            val controller = input.controller
            val pose = input.pose
            val isHand = controller.type == ControllerType.HAND
            // In Spatial SDK 0.14, index pinches are A/X, not the trigger bits.
            val down = if (isHand) controller.isDown(PINCH_R) || controller.isDown(PINCH_L)
                else controller.isDown(TRIGGER_L) || controller.isDown(TRIGGER_R)
            val previousDown = downState.put(id, down)
            val pressed = previousDown == false && down
            val released = previousDown == true && !down
            if (screenHidden) {
                if (!isHand && pressed) onScreenHiddenChange(false)
                continue
            }
            if (isHand && (pressed || released)) Log.i(TAG, "hand pinch ${if (down) "down" else "up"}")

            if (watching) {
                val reveal = aimRail(pose, reveal = true)
                val inReveal = reveal?.takeIf { it.onFace }
                if (revealMotion.update(id, inReveal?.xNorm?.times(Stage.PLAYER_WIDTH + REVEAL_PAD),
                        inReveal?.yNorm?.times(Stage.PLAYER_HEIGHT + REVEAL_PAD))) autoHide.interact(now)
                val rail = aimRail(pose, reveal = false)
                val onRail = rail?.onFace == true && playerOpen && playerFraction > 0.75f
                val target = if (onRail) playerTargets.at(rail!!.xNorm, rail.yNorm) else null
                if (pressed && target != null) {
                    if (target != PlayerTarget.Seek || railSelections.values.none { it.target == PlayerTarget.Seek }) {
                        railSelections[id] = RailSelection(target)
                        autoHide.select(id, true, now)
                        Log.i(TAG, "rail select ${controller.type.name}")
                    }
                }
                val selection = railSelections[id]
                if (selection?.target == PlayerTarget.Seek && rail != null) {
                    playerTargets.seekFraction(rail.xNorm)?.let {
                        selection.fraction = it
                        onSeekPreview(it)
                    }
                }
                if (released && selection != null) {
                    railSelections.remove(id)
                    autoHide.select(id, false, now)
                    when (val selected = selection.target) {
                        PlayerTarget.Seek -> {
                            selection.fraction?.let(onSeekFraction)
                            onSeekPreview(null)
                        }
                        is PlayerTarget.Button -> if (target == selected) onPlayerControl(selected.control)
                    }
                }
                if (onRail && (hovered == null || rail!!.distance < hovered.distance)) hovered = rail
            }

            val aimed = aim(pose)
            if (aimed != null && (!watching || aimed.onFace) && (hovered == null || aimed.distance < hovered.distance)) hovered = aimed
            if (isHand) {
                if (watching) {
                    if (aimed?.onFace == true) handAims[id] = aimed
                    val screenPose = screen.getComponent<Transform>().transform
                    hands.add(HandInput(id, down, pose.t.dot(screenPose.right().times(-1f)),
                        pose.t.dot(screenPose.up()), pose.t.dot(screenPose.forward()),
                        onVideo = aimed?.onFace == true && railSelections[id] == null))
                } else if (pressed && aimed?.onFace == true) {
                    Log.i(TAG, "library hand press")
                    onPress(aimed.xNorm, aimed.yNorm)
                }
                continue
            }
            val held = grab
            if (held?.driver == input.entity && gripping(controller)) {
                move(held, controller, pose)
                continue
            }
            if (held?.driver == input.entity) grab = null
            if (aimed != null && justGripped(controller)) {
                grab = Grab(input.entity, Pose(pose.t.copy(), pose.q.copy()),
                    screen.getComponent<Transform>().transform.let { Pose(it.t.copy(), it.q.copy()) })
                Log.i(TAG, "grip move started")
            } else if (aimed?.onFace == true && pressed) {
                onPress(aimed.xNorm, aimed.yNorm)
            }
            if (watching && !gripping(controller)) {
                val seek = stickSeek.getOrPut(id) { MovieStickSeek() }.update(horizontalAxis(controller), now)
                if (seek != 0) {
                    Log.i(TAG, "thumbstick seek $seek")
                    onSeek(seek * 10_000L)
                }
            } else {
                stickSeek.remove(id)
                if (!watching && libraryScroll == 0) libraryScroll = when {
                    justStickLeft(controller) -> -1
                    justStickRight(controller) -> 1
                    else -> 0
                }
            }
        }
        if (grab?.driver?.id !in ids) grab = null
        if (watching && !screenHidden) {
            val size = screenSize()
            val actions = handGestures.update(hands, size, now)
            if (actions.resizing != wasHandResizing) {
                wasHandResizing = actions.resizing
                if (!actions.resizing) saveScreenSize()
                Log.i(TAG, "hand resize ${if (actions.resizing) "started" else "ended"} width=${size.width}")
            }
            actions.size?.let {
                if (now - lastResizeMs >= 33 && abs(it.width - size.width) >= 0.01f) {
                    lastResizeMs = now
                    applySize(it)
                    autoHide.interact(now)
                    if (now - lastResizeLogMs >= 400L) {
                        lastResizeLogMs = now
                        Log.i(TAG, "hand resize applied width=${screenSize().width}")
                    }
                }
            }
            for (direction in actions.seekDirections) {
                Log.i(TAG, "hand swipe seek $direction")
                onSeek(direction * 10_000L)
            }
            for (id in actions.taps) handAims[id]?.let { onPress(it.xNorm, it.yNorm) }
        } else {
            handGestures.reset()
            wasHandResizing = false
            if (libraryScroll != 0) onScroll(libraryScroll)
        }
        processOpenHands(inputs, now)
        placeCursor(if (screenHidden) null else hovered)
    }

    private fun processOpenHands(inputs: List<TrackedInput>, now: Long) {
        val handInputs = inputs.filter { it.controller.type == ControllerType.HAND }
        val pinching = handInputs.any { it.controller.isDown(PINCH_R) || it.controller.isDown(PINCH_L) }
        val gripping = inputs.any { it.controller.type == ControllerType.CONTROLLER && gripping(it.controller) }
        if (pinching || gripping) screenMotion = null
        val body = Query.where { has(AvatarBody.id) }.eval().firstNotNullOfOrNull { entity ->
            if (!entity.isLocal()) null else runCatching { entity.getComponent<AvatarBody>() }.getOrNull()?.takeIf { it.isPlayerControlled }
        }
        val used = mutableSetOf<Long>()
        val pose = screen.getComponent<Transform>().transform
        val right = pose.right().times(-1f)
        val up = pose.up()
        val forward = pose.forward()
        fun project(point: HandPoint): HandPoint {
            val v = Vector3(point.x, point.y, point.z)
            return HandPoint(v.dot(right), v.dot(up), v.dot(forward))
        }
        val palms = if (handInputs.isEmpty()) emptyList() else readOpenHands().mapNotNull { palm ->
            val entity = if (palm.side == 0) body?.leftHand else body?.rightHand
            val input = handInputs.firstOrNull { it.entity == entity && it.entity.id !in used }
                ?: handInputs.filter { it.entity.id !in used }.minByOrNull {
                    val delta = it.pose.t.minus(Vector3(palm.position.x, palm.position.y, palm.position.z))
                    delta.length()
                }?.takeIf { it.pose.t.minus(Vector3(palm.position.x, palm.position.y, palm.position.z)).length() < 0.35f }
                ?: return@mapNotNull null
            used.add(input.entity.id)
            OpenHandInput(palm.side, project(palm.position), project(palm.normal), aim(input.pose)?.onFace == true)
        }
        val actions = openGestures.update(palms, true, pinching || gripping, now, screenHidden)
        if (actions.close) {
            screenMotion = null
            Log.i(TAG, "open hands hide screen")
            onScreenHiddenChange(true)
        } else if (actions.restore) {
            Log.i(TAG, "open hands restore screen")
            onScreenHiddenChange(false)
        } else actions.move?.let { direction ->
            val delta = when (direction) {
                ScreenDirection.LEFT -> right.times(-0.35f)
                ScreenDirection.RIGHT -> right.times(0.35f)
                ScreenDirection.UP -> up.times(0.35f)
                ScreenDirection.DOWN -> up.times(-0.35f)
            }
            screenMotion = ScreenMotion(Pose(pose.t.copy(), pose.q.copy()), clampDistance(pose.t.plus(delta)), now)
            autoHide.interact(now)
            Log.i(TAG, "open hand screen move ${direction.name}")
        }
    }

    private fun advanceScreenMove(now: Long) {
        val motion = screenMotion ?: return
        val progress = ((now - motion.started) / 180f).coerceIn(0f, 1f)
        val eased = progress * progress * (3f - 2f * progress)
        val position = motion.start.t.plus(motion.end.minus(motion.start.t).times(eased))
        screen.setComponent(Transform(Pose(position, motion.start.q)))
        if (progress >= 1f) screenMotion = null
    }

    private class TrackedInput(val entity: Entity, val controller: Controller, val pose: Pose)
    private class RailSelection(val target: PlayerTarget, var fraction: Float? = null)

    private fun aimRail(inputPose: Pose, reveal: Boolean): Aim? {
        val pose = screen.getComponent<Transform>().transform
        val rail = SideRailPlacement.beside(screenSize())
        val right = pose.right().times(-1f)
        val shift = if (reveal) Stage.PLAYER_SLIDE * 0.5f else (1f - playerFraction) * Stage.PLAYER_SLIDE
        val center = pose.t.plus(right.times(rail.right + shift)).plus(pose.up().times(rail.up))
            .plus(pose.forward().times(Stage.PLAYER_FORWARD))
        val pad = if (reveal) REVEAL_PAD else 0f
        return aimAt(inputPose, Pose(center, pose.q), Stage.PLAYER_WIDTH + pad, Stage.PLAYER_HEIGHT + pad, 0f)
    }

    private fun scaleTo(width: Float, height: Float, baseWidth: Float, baseHeight: Float): Scale {
        val x = if (baseWidth > 0.05f) width / baseWidth else 1f
        val y = if (baseHeight > 0.05f) height / baseHeight else 1f
        return Scale(Vector3(x, y, 1f))
    }

    private fun placeCursor(aim: Aim?) {
        val visible = aim != null
        if (visible != cursorVisible) {
            cursor.setComponent(Visible(visible))
            cursorVisible = visible
        }
        if (aim == null) {
            return
        }
        cursor.setComponent(Transform(Pose(aim.point.plus(aim.normal.times(0.12f)))))
    }

    private fun move(grab: Grab, controller: Controller, pose: Pose) {
        val delta = pose.t.minus(grab.startController.t)
        val forward = pose.forward()
        val along = delta.dot(forward)
        val lateral = delta.minus(forward.times(along))
        grab.stickMeters += stickAxis(controller) * STICK_STEP
        val shifted = grab.startScreen.t
            .plus(lateral.times(LATERAL_GAIN))
            .plus(forward.times(along * DEPTH_GAIN + grab.stickMeters))
        val rotation = pose.times(grab.startController.inverse()).times(grab.startScreen).q
        screen.setComponent(Transform(Pose(clampDistance(shifted), rotation)))
    }

    private fun aim(controllerPose: Pose): Aim? {
        val size = screenSize()
        return aimAt(controllerPose, screen.getComponent<Transform>().transform, size.width, size.height, AIM_PAD)
    }

    fun screenSize(): ScreenSize {
        val dims = runCatching { screen.getComponent<IsdkPanelDimensions>().dimensions }.getOrNull()
        val base = ScreenSize(dims?.x?.takeIf { it > 0.2f } ?: EarthOrbit.screenWidth,
            dims?.y?.takeIf { it > 0.2f } ?: EarthOrbit.screenHeight)
        val scale = runCatching { screen.getComponent<Scale>().scale }.getOrNull()
        return base.scaled(scale?.x ?: 1f, scale?.y ?: 1f)
    }

    private fun aimAt(controllerPose: Pose, screenPose: Pose, width: Float, height: Float, pad: Float): Aim? {
        val origin = controllerPose.t
        val direction = controllerPose.forward()
        val normal = screenPose.forward()
        val denom = direction.dot(normal)
        if (abs(denom) < 1e-4f) return null
        val t = screenPose.t.minus(origin).dot(normal) / denom
        if (t < 0.05f || t > 30f) return null
        val hit = origin.plus(direction.times(t))
        val delta = hit.minus(screenPose.t)
        val across = delta.dot(screenPose.right())
        val up = delta.dot(screenPose.up())
        val halfW = width * 0.5f
        val halfH = height * 0.5f
        if (abs(across) > halfW + pad || abs(up) > halfH + pad) return null
        // Yaw 180 flips local +X to the viewer's left, and the picture reads normally,
        // so the view's horizontal axis is the opposite of pose.right().
        return Aim(
            onFace = abs(across) <= halfW && abs(up) <= halfH,
            xNorm = 0.5f - across / width,
            yNorm = 0.5f - up / height,
            distance = t,
            point = hit,
            normal = normal,
        )
    }

    private fun clampDistance(position: Vector3): Vector3 {
        val eye = Vector3(0f, 1.2f, 0f)
        val offset = position.minus(eye)
        val dist = offset.length()
        if (dist < 0.001f) return position
        val clamped = dist.coerceIn(MIN_DISTANCE, MAX_DISTANCE)
        return eye.plus(offset.times(clamped / dist))
    }

    private class Grab(
        val driver: Entity,
        val startController: Pose,
        val startScreen: Pose,
        var stickMeters: Float = 0f,
    )

    private class Aim(
        val onFace: Boolean,
        val xNorm: Float,
        val yNorm: Float,
        val distance: Float,
        val point: Vector3,
        val normal: Vector3,
    )

    private companion object {
        const val TAG = "Theatre"
        const val AIM_PAD = 0.4f
        const val REVEAL_PAD = 0.4f
        const val LATERAL_GAIN = 1.5f
        const val DEPTH_GAIN = 8f
        const val STICK_STEP = 0.025f
        const val MIN_DISTANCE = 0.85f
        const val MAX_DISTANCE = 12f

        // Spatial SDK 0.14 ButtonBits. The getters are module-internal.
        const val PINCH_R = 1
        const val PINCH_L = 4
        const val TRIGGER_L = 16
        const val TRIGGER_R = 32
        const val SQUEEZE_L = 64
        const val SQUEEZE_R = 128
        const val THUMB_LL = 256
        const val THUMB_LR = 512
        const val THUMB_RL = 1024
        const val THUMB_RR = 2048
        const val THUMB_LU = 4096
        const val THUMB_LD = 8192
        const val THUMB_RU = 16384
        const val THUMB_RD = 32768

        fun gripping(controller: Controller): Boolean {
            return controller.isDown(SQUEEZE_L) || controller.isDown(SQUEEZE_R)
        }

        fun justGripped(controller: Controller): Boolean {
            return controller.isPressed(SQUEEZE_L) || controller.isPressed(SQUEEZE_R)
        }

        fun horizontalAxis(controller: Controller): Int {
            val left = controller.isDown(THUMB_LL) || controller.isDown(THUMB_RL)
            val right = controller.isDown(THUMB_LR) || controller.isDown(THUMB_RR)
            return when {
                left && !right -> -1
                right && !left -> 1
                else -> 0
            }
        }

        fun justStickLeft(controller: Controller): Boolean {
            return controller.isPressed(THUMB_LL) || controller.isPressed(THUMB_RL)
        }

        fun justStickRight(controller: Controller): Boolean {
            return controller.isPressed(THUMB_LR) || controller.isPressed(THUMB_RR)
        }

        fun stickAxis(controller: Controller): Float {
            val away = controller.isDown(THUMB_LD) || controller.isDown(THUMB_RD)
            val closer = controller.isDown(THUMB_LU) || controller.isDown(THUMB_RU)
            return when {
                away && !closer -> 1f
                closer && !away -> -1f
                else -> 0f
            }
        }
    }
}
