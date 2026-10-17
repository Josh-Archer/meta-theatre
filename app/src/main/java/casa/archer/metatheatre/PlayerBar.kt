package casa.archer.metatheatre

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFFF4F1EA)
private val Muted = Color(0xFFD5DCE8)
private val Accent = Color(0xFFDFC58F)
private val Zone = Color(0xFF253043)
private val Outline = Color(0xFF3A465A)

/** Ray input uses measured layout bounds for both controllers and hands. */
@Composable
fun PlayerBar(model: TheatreModel, player: ScreenPlayer) {
    val open by model.playerControlsOpen.collectAsState()
    val hidden by model.screenHidden.collectAsState()
    Box(Modifier.fillMaxSize()) {
        PlayerContent(model, player)
        PanelReticle(model, PointerPanel.PLAYER, open && !hidden)
    }
}

@Composable
private fun PlayerContent(model: TheatreModel, player: ScreenPlayer) {
    val open by model.playerControlsOpen.collectAsState()
    val alpha by animateFloatAsState(
        targetValue = if (open) 1f else 0f,
        animationSpec = tween(PlayerAutoHide.SLIDE_MS.toInt()),
        label = "playerControlsFade",
    )
    if (!open && alpha == 0f) return
    val transport by player.transport.collectAsState()
    val tracks by player.tracks.collectAsState()
    val quietCinema by model.quietCinemaEnabled.collectAsState()
    val progress = (transport.positionMs.toFloat() / transport.durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
    val seekPreview by model.seekPreview.collectAsState()
    val targets = model.playerHitTargets
    DisposableEffect(Unit) { onDispose { targets.clear() } }
    Column(
        modifier = Modifier.fillMaxSize()
            .graphicsLayer { this.alpha = alpha }
            .clip(RoundedCornerShape(36.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF1B2433), Color(0xFF111722))))
            .border(1.dp, Outline, RoundedCornerShape(36.dp))
            .padding(30.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().weight(0.13f), verticalArrangement = Arrangement.Center) {
            Text("NOW PLAYING", color = Accent, fontSize = 26.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(14.dp))
            Text(transport.title.ifBlank { "Theatre" }, color = Ink, fontSize = 38.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (transport.subtitle.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(transport.subtitle, color = Muted, fontSize = 28.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        RailDivider()
        Column(modifier = Modifier.fillMaxWidth().weight(0.21f), verticalArrangement = Arrangement.Center) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(clock(transport.positionMs), color = Ink, fontSize = 28.sp)
                Text(clock(transport.durationMs), color = Muted, fontSize = 28.sp)
            }
            SeekTrack(seekPreview ?: progress, targets, enabled = transport.durationMs > 0,
                onSeek = model::seekToFraction)
            RailButton(if (transport.playWhenReady) "Pause" else "Play", targets, PlayerControl.TOGGLE, highlighted = true, height = 82) {
                model.playerControl(PlayerControl.TOGGLE)
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallKey("−10s", targets, PlayerControl.BACK) { model.playerControl(PlayerControl.BACK) }
                SmallKey("+10s", targets, PlayerControl.FORWARD) { model.playerControl(PlayerControl.FORWARD) }
                SmallKey("+30s", targets, PlayerControl.FORWARD_MORE) { model.playerControl(PlayerControl.FORWARD_MORE) }
            }
        }
        RailDivider()
        Column(modifier = Modifier.fillMaxWidth().weight(0.14f), verticalArrangement = Arrangement.Center) {
            RailLabel("SCREEN SIZE")
            Spacer(Modifier.height(14.dp))
            RailButton("＋  Larger", targets, PlayerControl.LARGER, highlighted = true, height = 82) { model.playerControl(PlayerControl.LARGER) }
            Spacer(Modifier.height(12.dp))
            RailButton("−  Smaller", targets, PlayerControl.SMALLER, height = 70) { model.playerControl(PlayerControl.SMALLER) }
        }
        RailDivider()
        Column(modifier = Modifier.fillMaxWidth().weight(0.34f), verticalArrangement = Arrangement.Center) {
            RailLabel("VIDEO QUALITY")
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((quality, control) in listOf(
                    PlaybackQuality.ORIGINAL to PlayerControl.ORIGINAL,
                    PlaybackQuality.UHD to PlayerControl.UHD,
                    PlaybackQuality.HD to PlayerControl.HD,
                )) {
                    SmallKey(quality.label, targets, control, selected = transport.quality == quality) { model.playerControl(control) }
                }
            }
            Spacer(Modifier.height(12.dp))
            ResolutionRow("Playing", transport.videoWidth, transport.videoHeight)
            ResolutionRow("Source", transport.sourceWidth, transport.sourceHeight)
            if (transport.videoColor.isNotBlank()) {
                Text("Input: ${transport.videoColor}", color = Ink, fontSize = 26.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            }
            val notice = movieQualityNotice(transport.quality, transport.sourceWidth, transport.sourceHeight,
                transport.videoWidth, transport.videoHeight)
            Text(transport.detail.ifBlank { notice.ifBlank { transport.playbackMode } },
                color = if (notice.isNotBlank()) Accent else Muted, fontSize = 24.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(12.dp))
            val audio = tracks.audio.firstOrNull { it.id == tracks.selectedAudioId }?.label ?: "Default"
            val subtitle = tracks.subtitles.firstOrNull { it.id == tracks.selectedSubtitleId }?.label ?: "Off"
            TrackKey("Audio: $audio", targets, PlayerControl.AUDIO) { model.playerControl(PlayerControl.AUDIO) }
            Spacer(Modifier.height(8.dp))
            TrackKey("Subtitles: $subtitle", targets, PlayerControl.SUBTITLES) { model.playerControl(PlayerControl.SUBTITLES) }
        }
        RailDivider()
        Column(modifier = Modifier.fillMaxWidth().weight(0.18f), verticalArrangement = Arrangement.Center) {
            RailButton("Explore Earth", targets, PlayerControl.EARTH, highlighted = true, height = 82) { model.playerControl(PlayerControl.EARTH) }
            Spacer(Modifier.height(10.dp))
            RailButton("Recently watched", targets, PlayerControl.LIBRARY, height = 74) { model.playerControl(PlayerControl.LIBRARY) }
            Spacer(Modifier.height(10.dp))
            TrackKey("Quiet cinema: ${if (quietCinema) "On" else "Off"}", targets, PlayerControl.QUIET) {
                model.playerControl(PlayerControl.QUIET)
            }
            Text("Hold one stop palm up to pause", color = Muted, fontSize = 24.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp))
        }
    }
}

@Composable
private fun TrackKey(label: String, targets: PlayerHitTargets, control: PlayerControl, onClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(14.dp))
            .background(Zone)
            .playerTarget(targets, PlayerTarget.Button(control))
            .semantics { role = Role.Button; onClick { onClick(); true } },
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(label, color = Ink, fontSize = 26.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 14.dp))
    }
}

@Composable
private fun RailLabel(label: String) {
    Text(label, color = Muted, fontSize = 26.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun RailDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Outline.copy(alpha = 0.7f)))
}

@Composable
private fun ResolutionRow(label: String, width: Int, height: Int) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 26.sp)
        Text(movieResolutionLabel(width, height).ifBlank { "—" }, color = Ink, fontSize = 26.sp)
    }
}

@Composable
private fun RailButton(label: String, targets: PlayerHitTargets, control: PlayerControl, highlighted: Boolean = false, height: Int, onClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(22.dp))
            .background(if (highlighted) Accent else Zone)
            .playerTarget(targets, PlayerTarget.Button(control))
            .semantics { role = Role.Button; onClick { onClick(); true } },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (highlighted) Color(0xFF211A0E) else Ink, fontSize = 32.sp,
            fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun RowScope.SmallKey(label: String, targets: PlayerHitTargets, control: PlayerControl, selected: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier.weight(1f).height(70.dp).clip(RoundedCornerShape(18.dp))
            .background(if (selected) Accent.copy(alpha = 0.16f) else Zone)
            .border(1.dp, if (selected) Accent else Outline, RoundedCornerShape(18.dp))
            .playerTarget(targets, PlayerTarget.Button(control))
            .semantics { role = Role.Button; onClick { onClick(); true } },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) Accent else Ink, fontSize = 28.sp, fontWeight = FontWeight.Medium)
    }
}

private fun Modifier.playerTarget(targets: PlayerHitTargets, target: PlayerTarget): Modifier =
    onGloballyPositioned { coordinates ->
        val root = coordinates.findRootCoordinates().size
        val rect = coordinates.boundsInRoot()
        if (root.width > 0 && root.height > 0) targets.put(target, PlayerHitRect(
            rect.left / root.width, rect.top / root.height, rect.right / root.width, rect.bottom / root.height,
        ))
    }

@Composable
private fun SeekTrack(progress: Float, targets: PlayerHitTargets, enabled: Boolean, onSeek: (Float) -> Unit) {
    Canvas(Modifier.fillMaxWidth().height(72.dp)
        .playerTarget(targets, PlayerTarget.Seek)
        .semantics {
            progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
            setProgress { if (enabled) { onSeek(it); true } else false }
        }) {
        val centerY = size.height / 2f
        val x = progress.coerceIn(0f, 1f) * size.width
        drawLine(Outline, Offset(0f, centerY), Offset(size.width, centerY), 6.dp.toPx(), StrokeCap.Round)
        drawLine(Accent, Offset(0f, centerY), Offset(x, centerY), 6.dp.toPx(), StrokeCap.Round)
        drawCircle(if (enabled) Accent else Muted, 12.dp.toPx(), Offset(x, centerY))
    }
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
