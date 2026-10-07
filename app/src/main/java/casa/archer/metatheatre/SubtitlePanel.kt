package casa.archer.metatheatre

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.text.Cue
import androidx.media3.ui.SubtitleView

/** Native Media3 subtitle renderer for placement on the player's transparent mesh. */
@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun SubtitlePanel(player: ScreenPlayer, modifier: Modifier = Modifier) {
    val cues by player.subtitleCues.collectAsState()
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = ::createSubtitleView,
        update = { updateSubtitleView(it, cues) },
    )
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun createSubtitleView(context: Context): SubtitleView = SubtitleView(context)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun updateSubtitleView(view: SubtitleView, cues: List<Cue>) {
    view.setCues(cues)
}
