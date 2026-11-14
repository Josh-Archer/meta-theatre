package casa.archer.metatheatre

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import metavrx.uiset.compose.Surface
import metavrx.uiset.compose.SurfaceColors
import metavrx.uiset.compose.button.ButtonStyle
import metavrx.uiset.compose.button.LabelButton
import metavrx.uiset.compose.slider.Slider
import metavrx.uiset.compose.slider.SliderSize
import metavrx.uiset.compose.theme.UiSetTheme
import metavrx.uiset.compose.theme.darkColorScheme
import metavrx.uiset.compose.theme.BrushSpec

/** Meta VR UI Set controls, attached to the movie with one shared ray input path. */
@Composable
fun PlayerBar(model: TheatreModel, player: ScreenPlayer) {
    val open by model.playerControlsOpen.collectAsState()
    val hidden by model.screenHidden.collectAsState()
    UiSetTheme(colorScheme = darkColorScheme()) {
        Box(Modifier.fillMaxSize().ownedRayInput()) {
            PlayerContent(model, player)
            PanelReticle(model, PointerPanel.PLAYER, open && !hidden)
        }
    }
}

@Composable
private fun PlayerContent(model: TheatreModel, player: ScreenPlayer) {
    val open by model.playerControlsOpen.collectAsState()
    val expanded by model.playerSettingsOpen.collectAsState()
    val alpha by animateFloatAsState(if (open) 1f else 0f,
        tween(PlayerAutoHide.SLIDE_MS.toInt()), label = "playerControlsFade")
    val targets = model.playerHitTargets
    DisposableEffect(Unit) { onDispose { targets.clear() } }
    if (!open && alpha == 0f) return
    val transport by player.transport.collectAsState()
    val tracks by player.tracks.collectAsState()
    val preview by model.seekPreview.collectAsState()
    val progress = preview ?: (transport.positionMs.toFloat() / transport.durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
    val audio = tracks.audio.firstOrNull { it.id == tracks.selectedAudioId }?.label ?: "Default audio"
    val subtitle = tracks.subtitles.firstOrNull { it.id == tracks.selectedSubtitleId }?.label ?: "Off"
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Surface(modifier = Modifier.fillMaxWidth()
            .height((if (expanded) PlayerPlacement.HEIGHT_DP else PlayerPlacement.COMPACT_DP).dp)
            .graphicsLayer { this.alpha = alpha },
            colors = SurfaceColors(
                BrushSpec.VerticalGradient(Color(0x99232A33), Color(0xB312171F)),
                UiSetTheme.colorScheme.surface.content),
            border = BorderStroke(1.dp, Color.White.copy(alpha = .20f))) {
            Column {
                if (expanded) PlayerSettings(model, transport)
                Column(Modifier.fillMaxWidth().height(PlayerPlacement.COMPACT_DP.dp).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(transport.title.ifBlank { "Theatre" }, style = UiSetTheme.typography.title.copy(fontSize = 30.sp),
                        color = UiSetTheme.colorScheme.surface.content.primary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(36.dp))
                    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        PlayerText(clock(if (preview != null) (progress * transport.durationMs).toLong() else transport.positionMs))
                        Box(Modifier.weight(1f)) {
                            MeasuredTarget(targets, PlayerTarget.Seek) { modifier ->
                                Slider(value = progress, onValueChange = model::seekToFraction,
                                    contentDescription = "Playback position", size = SliderSize.Small,
                                    enabled = transport.durationMs > 0, modifier = modifier.fillMaxWidth())
                            }
                        }
                        PlayerText(clock(transport.durationMs))
                    }
                    Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PlayerKey(if (transport.playWhenReady) "Pause" else "Play", PlayerControl.TOGGLE, model, Modifier.weight(1.2f), primary = true)
                        PlayerKey("−10s", PlayerControl.BACK, model, Modifier.weight(.8f))
                        PlayerKey("+10s", PlayerControl.FORWARD, model, Modifier.weight(.8f))
                        PlayerKey("Audio", PlayerControl.AUDIO, model, Modifier.weight(1.2f))
                        PlayerKey("Subtitles", PlayerControl.SUBTITLES, model, Modifier.weight(1.4f))
                        PlayerKey(if (expanded) "Done" else "Settings", PlayerControl.SETTINGS, model, Modifier.weight(1.3f))
                        PlayerKey("Library", PlayerControl.LIBRARY, model, Modifier.weight(1.3f))
                    }
                    Text(transport.detail.ifBlank { "$audio · Subtitles: $subtitle" },
                        style = UiSetTheme.typography.bodySmall.copy(fontSize = 22.sp),
                        color = if (transport.canRetry) UiSetTheme.colorScheme.negative.content else UiSetTheme.colorScheme.surface.content.secondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(28.dp))
                }
            }
        }
    }
}

@Composable
private fun PlayerSettings(model: TheatreModel, transport: Transport) {
    val quiet by model.quietCinemaEnabled.collectAsState()
    Column(Modifier.fillMaxWidth().height((PlayerPlacement.HEIGHT_DP - PlayerPlacement.COMPACT_DP).dp)
        .padding(20.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PlayerText("Screen", Modifier.weight(.8f))
            PlayerKey("Smaller", PlayerControl.SMALLER, model, Modifier.weight(1f))
            PlayerKey("Larger", PlayerControl.LARGER, model, Modifier.weight(1f))
            PlayerKey("Skip +30s", PlayerControl.FORWARD_MORE, model, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PlayerText("Quality", Modifier.weight(.8f))
            for ((quality, control) in listOf(PlaybackQuality.ORIGINAL to PlayerControl.ORIGINAL,
                PlaybackQuality.UHD to PlayerControl.UHD, PlaybackQuality.HD to PlayerControl.HD)) {
                PlayerKey(quality.label, control, model, Modifier.weight(1f), primary = transport.quality == quality)
            }
        }
        val notice = movieQualityNotice(transport.quality, transport.sourceWidth, transport.sourceHeight, transport.videoWidth, transport.videoHeight)
        Column {
            PlayerText("Playing ${movieResolutionLabel(transport.videoWidth, transport.videoHeight).ifBlank { "—" }} · Source ${movieResolutionLabel(transport.sourceWidth, transport.sourceHeight).ifBlank { "—" }}")
            Text(notice.ifBlank { "Input: ${transport.videoColor.ifBlank { "—" }} · Display: SDR / Rec.709" },
                style = UiSetTheme.typography.bodySmall.copy(fontSize = 22.sp),
                color = UiSetTheme.colorScheme.surface.content.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PlayerKey("Explore Earth", PlayerControl.EARTH, model, Modifier.weight(1f))
            PlayerKey("Quiet cinema: ${if (quiet) "On" else "Off"}", PlayerControl.QUIET, model, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PlayerText("Lower hands before stop-palm pause", Modifier.weight(1f).align(Alignment.CenterVertically))
            PlayerKey("Reconnect", PlayerControl.RECONNECT, model, Modifier.weight(1f))
        }
    }
}

@Composable
private fun PlayerText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = UiSetTheme.typography.body.copy(fontSize = 24.sp),
        color = UiSetTheme.colorScheme.surface.content.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun PlayerKey(label: String, control: PlayerControl, model: TheatreModel, modifier: Modifier,
                      primary: Boolean = false) {
    val target = PlayerTarget.Button(control)
    val cursor by model.panelCursor.collectAsState()
    val pressedTarget by model.playerPressedTarget.collectAsState()
    val hovered = cursor?.let { it.panel == PointerPanel.PLAYER && model.playerHitTargets.at(it.x, it.y) == target } == true
    val interactions = remember { MutableInteractionSource() }
    DisposableEffect(hovered) {
        val enter = HoverInteraction.Enter()
        if (hovered) interactions.tryEmit(enter)
        onDispose { if (hovered) interactions.tryEmit(HoverInteraction.Exit(enter)) }
    }
    DisposableEffect(pressedTarget == target) {
        val press = PressInteraction.Press(Offset.Zero)
        if (pressedTarget == target) interactions.tryEmit(press)
        onDispose { if (pressedTarget == target) interactions.tryEmit(PressInteraction.Cancel(press)) }
    }
    Box(modifier.fillMaxHeight()) {
        MeasuredTarget(model.playerHitTargets, target) { measured ->
            LabelButton(label = label, onClick = { model.playerControl(control) },
                modifier = measured.fillMaxSize(), expanded = true,
                dimensions = UiSetTheme.dimensions.buttons.copy(horizontalPadding = 16.dp),
                style = if (primary) ButtonStyle.Primary else ButtonStyle.Secondary,
                labelTextStyle = UiSetTheme.typography.label.copy(fontSize = 24.sp), interactionSource = interactions)
        }
    }
}

@Composable
private fun MeasuredTarget(targets: PlayerHitTargets, target: PlayerTarget, enabled: Boolean = true,
                           content: @Composable (Modifier) -> Unit) {
    DisposableEffect(target, enabled) { onDispose { if (enabled) targets.remove(target) } }
    // UI Set's small slider thumb travels between half-thumb insets, not the hit area's edges.
    val thumbInset = with(LocalDensity.current) {
        maxOf(UiSetTheme.dimensions.sliders.smallTrackHeight, UiSetTheme.dimensions.sliders.iconSize).toPx() * .5f
    }
    content(if (!enabled) Modifier else Modifier.onGloballyPositioned { coordinates ->
        val root = coordinates.findRootCoordinates().size
        val rect = coordinates.boundsInRoot()
        if (root.width > 0 && root.height > 0) targets.put(target, PlayerHitRect(
            rect.left / root.width, rect.top / root.height, rect.right / root.width, rect.bottom / root.height),
            horizontalInset = if (target == PlayerTarget.Seek) thumbInset / root.width else 0f)
    })
}

internal fun clock(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val total = ms / 1000
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}
