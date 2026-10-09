package casa.archer.metatheatre

import android.content.Context
import android.view.Surface
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.text.Cue
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import java.util.Locale

data class Transport(
    val title: String = "",
    val subtitle: String = "",
    val detail: String = "",
    val playing: Boolean = false,
    val playWhenReady: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val playbackMode: String = "",
    val quality: PlaybackQuality = PlaybackQuality.ORIGINAL,
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
)

/**
 * Original playback uses the source file. Quality controls request a Plex stream
 * without changing the physical screen size, position, or playback intent.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ScreenPlayer internal constructor(
    context: Context,
    private val playerFactory: (Context, DefaultTrackSelector, DefaultDataSource.Factory) -> ExoPlayer =
        { appContext, selector, sourceFactory ->
            ExoPlayer.Builder(appContext)
                .setTrackSelector(selector)
                .setMediaSourceFactory(DefaultMediaSourceFactory(appContext).setDataSourceFactory(sourceFactory))
                .build()
        },
) {
    private val dataSourceFactory = DefaultHttpDataSource.Factory()
        .setUserAgent("MetaTheatre/1.0")
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(20_000)
        .setReadTimeoutMs(20_000)

    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var active: Playable? = null
    private var route = PlaybackRoute.DIRECT
    private var pending: Playable? = null
    private var pendingPositionMs = 0L
    private var starting = false
    internal var onProgress: ((Long, Long, Boolean) -> Unit)? = null
    private var subtitle: String = ""
    private var resumeAfterSuspension = false
    private var menuOpen = false
    private var screenHidden = false
    private var quality = PlaybackQuality.ORIGINAL
    private val trackCatalog = PlaybackTrackCatalog()
    private var pendingPlayWhenReady = true

    val transport = kotlinx.coroutines.flow.MutableStateFlow(Transport())
    val tracks: kotlinx.coroutines.flow.StateFlow<PlaybackTracks> = trackCatalog.tracks
    val subtitleCues = kotlinx.coroutines.flow.MutableStateFlow<List<Cue>>(emptyList())

    private val appContext = context.applicationContext

    fun attach(surface: Surface) {
        val existing = player
        if (existing != null) {
            existing.setVideoSurface(surface)
            existing.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            return
        }
        val selector = DefaultTrackSelector(appContext)
        trackSelector = selector
        updateTrackQuality()
        val created = playerFactory(appContext, selector, DefaultDataSource.Factory(appContext, dataSourceFactory))
            .apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    true,
                )
                setVideoSurface(surface)
                setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                addListener(listener)
            }
        player = created
        pending?.let {
            pending = null
            start(created, it, streamUrlForCurrentChoices(it), pendingPositionMs, pendingPlayWhenReady && !menuOpen && !screenHidden)
        }
    }

    fun play(item: Playable, positionMs: Long = 0L) {
        pendingPositionMs = positionMs.coerceAtLeast(0L)
        active = item
        subtitle = item.subtitle
        route = quality.startingRoute
        updateTrackChoices(item)
        subtitleCues.value = emptyList()
        pendingPlayWhenReady = !menuOpen && !screenHidden
        if (menuOpen || screenHidden) resumeAfterSuspension = true
        val current = player
        if (current == null) {
            pending = item
            transport.value = Transport(
                title = item.title, subtitle = item.subtitle, detail = "Opening the screen", positionMs = pendingPositionMs,
                quality = quality, sourceWidth = item.sourceWidth, sourceHeight = item.sourceHeight,
                playWhenReady = pendingPlayWhenReady,
            )
            return
        }
        updateTrackQuality()
        start(current, item, streamUrlForCurrentChoices(item), pendingPositionMs, playWhenReady = pendingPlayWhenReady)
    }

    fun setQuality(value: PlaybackQuality) {
        // Re-selecting Original also retries it after a compatibility fallback.
        if (value == quality && route == value.startingRoute) return
        quality = value
        route = value.startingRoute
        active?.let { updateTrackChoices(it, preserveSelection = true) }
        updateTrackQuality()
        Log.i(TAG, "quality requested ${value.label}")
        val item = active
        val current = player
        if (item == null || current == null) {
            transport.value = transport.value.copy(quality = value)
            return
        }
        val position = current.currentPosition.coerceAtLeast(0L)
        val resume = current.playWhenReady
        start(current, item, streamUrlForCurrentChoices(item), position, resume)
    }

    private fun updateTrackQuality() {
        val selector = trackSelector ?: return
        val size = if (quality == PlaybackQuality.HD) MovieQuality.HD else MovieQuality.UHD
        selector.setParameters(
            selector.buildUponParameters()
                .setMaxVideoSize(size.width, size.height)
                // The compositor surface is UHD, independent of Android's display size.
                .setViewportSize(size.width, size.height, true)
                .setForceHighestSupportedBitrate(true),
        )
    }

    private fun streamUrl(item: Playable): String = when (route) {
        PlaybackRoute.DIRECT -> item.directUrl
        PlaybackRoute.PLEX_UHD -> item.hlsUrl
        PlaybackRoute.PLEX_HD -> item.hdHlsUrl
    }

    fun seekToFraction(fraction: Float) {
        val current = player ?: return
        val duration = current.duration
        if (duration <= 0L) return
        current.seekTo((duration * fraction.coerceIn(0f, 1f)).toLong())
        publish()
    }

    fun toggle() {
        val current = player ?: return
        if (menuOpen || screenHidden) {
            resumeAfterSuspension = !resumeAfterSuspension
            return
        }
        if (current.playWhenReady) current.pause() else current.play()
        pendingPlayWhenReady = current.playWhenReady
        publish()
    }

    fun cycleAudio() = cycleTrack(PlaybackTrackType.AUDIO)

    fun cycleSubtitles() = cycleTrack(PlaybackTrackType.SUBTITLE)

    fun selectAudioTrack(id: String) = selectTrack(PlaybackTrackType.AUDIO, id)

    fun selectSubtitleTrack(id: String) = selectTrack(PlaybackTrackType.SUBTITLE, id)

    private fun cycleTrack(type: PlaybackTrackType) {
        val state = tracks.value
        val choices = when (type) {
            PlaybackTrackType.AUDIO -> state.audio
            PlaybackTrackType.SUBTITLE -> state.subtitles
        }
        if (choices.isEmpty()) return
        val selectedId = when (type) {
            PlaybackTrackType.AUDIO -> state.selectedAudioId
            PlaybackTrackType.SUBTITLE -> state.selectedSubtitleId
        }
        val next = (choices.indexOfFirst { it.id == selectedId } + 1).mod(choices.size)
        selectTrack(type, choices[next].id)
    }

    private fun selectTrack(type: PlaybackTrackType, id: String) {
        val choices = when (type) {
            PlaybackTrackType.AUDIO -> tracks.value.audio
            PlaybackTrackType.SUBTITLE -> tracks.value.subtitles
        }
        val choice = choices.firstOrNull { it.id == id } ?: return
        trackCatalog.select(type, id)
        if (choice.plexStreamId != null ||
            (choice.id == PlaybackTracks.SUBTITLES_OFF_ID && route != PlaybackRoute.DIRECT)
        ) {
            selectPlexStream(type, choice)
            return
        }
        val current = player ?: return
        val trackType = if (type == PlaybackTrackType.AUDIO) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT
        val selector = trackSelector ?: return
        val builder = selector.parameters.buildUpon().clearOverridesOfType(trackType)
        if (type == PlaybackTrackType.SUBTITLE && choice.mediaTrackGroup == null) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            builder.setTrackTypeDisabled(trackType, false)
            val group = choice.mediaTrackGroup ?: return
            builder.addOverride(TrackSelectionOverride(group, listOf(choice.mediaTrackIndex)))
        }
        selector.setParameters(builder.build())
        if (type == PlaybackTrackType.SUBTITLE) subtitleCues.value = emptyList()
        publish()
    }

    private fun selectPlexStream(type: PlaybackTrackType, choice: PlaybackTrack) {
        val item = active ?: return
        val current = player ?: return
        val position = current.currentPosition.coerceAtLeast(0L)
        val resume = current.playWhenReady
        val selected = tracks.value
        val audioId = (if (type == PlaybackTrackType.AUDIO) choice.plexStreamId else selected.selectedAudioId)
            ?.let { id -> item.audioStreams.firstOrNull { it.id == id.removePrefix("plex-audio:") }?.id }
        val subtitleId = if (type == PlaybackTrackType.SUBTITLE) choice.plexStreamId else
            selected.selectedSubtitleId?.let { id -> item.subtitleStreams.firstOrNull { it.id == id.removePrefix("plex-subtitle:") }?.id }
        start(current, item, plexStreamSelectionUrl(streamUrl(item), audioId, subtitleId), position, resume)
    }

    private fun updateTrackChoices(item: Playable, preserveSelection: Boolean = false) {
        val plexTracksEnabled = route != PlaybackRoute.DIRECT
        val oldTracks = trackCatalog.tracks.value
        val oldAudio = oldTracks.audio.firstOrNull { it.id == oldTracks.selectedAudioId }
        val oldSubtitle = oldTracks.subtitles.firstOrNull { it.id == oldTracks.selectedSubtitleId }
        val mappedAudio = if (plexTracksEnabled && oldAudio?.id?.startsWith("media-audio:") == true) {
            item.audioStreams.firstOrNull { it.matchesLanguage(oldAudio.language) }
        } else null
        val mappedSubtitle = if (plexTracksEnabled && oldSubtitle?.id?.startsWith("media-subtitle:") == true) {
            item.subtitleStreams.firstOrNull { it.matchesLanguage(oldSubtitle.language) }
        } else null
        trackCatalog.reset(
            if (plexTracksEnabled) item.audioStreams.map { it.toPlaybackTrack(PlaybackTrackType.AUDIO) } else emptyList(),
            if (plexTracksEnabled) item.subtitleStreams.map { it.toPlaybackTrack(PlaybackTrackType.SUBTITLE) } else emptyList(),
            preserveSelection = preserveSelection,
        )
        mappedAudio?.let { trackCatalog.select(PlaybackTrackType.AUDIO, "plex-audio:${it.id}") }
        mappedSubtitle?.let { trackCatalog.select(PlaybackTrackType.SUBTITLE, "plex-subtitle:${it.id}") }
    }

    private fun streamUrlForCurrentChoices(item: Playable): String {
        if (route == PlaybackRoute.DIRECT) return streamUrl(item)
        val state = tracks.value
        val audioId = state.audio.firstOrNull { it.id == state.selectedAudioId }?.plexStreamId
        val subtitleId = state.subtitles.firstOrNull { it.id == state.selectedSubtitleId }?.plexStreamId
        return plexStreamSelectionUrl(streamUrl(item), audioId, subtitleId)
    }

    private fun PlexPlaybackStream.matchesLanguage(label: String): Boolean {
        if (label.isBlank()) return false
        val localeLanguage = Locale.forLanguageTag(languageCode).language
        val labelLanguage = Locale.forLanguageTag(label).language
        return (localeLanguage.isNotBlank() && localeLanguage == labelLanguage) ||
            language.equals(label, ignoreCase = true) || languageCode.equals(label, ignoreCase = true)
    }

    fun seekBy(deltaMs: Long) {
        val current = player ?: return
        val duration = current.duration
        val target = (current.currentPosition + deltaMs).coerceAtLeast(0L)
        current.seekTo(if (duration > 0) target.coerceAtMost(duration) else target)
        publish()
    }

    fun onMenuOpened() {
        updateSuspension(menu = true, hidden = screenHidden)
    }

    fun onMenuClosed() {
        updateSuspension(menu = false, hidden = screenHidden)
    }

    fun setScreenHidden(hidden: Boolean) {
        updateSuspension(menu = menuOpen, hidden = hidden)
    }

    private fun updateSuspension(menu: Boolean, hidden: Boolean) {
        val wasSuspended = menuOpen || screenHidden
        val suspended = menu || hidden
        if (!wasSuspended && suspended) resumeAfterSuspension = player?.playWhenReady ?: pendingPlayWhenReady
        menuOpen = menu
        screenHidden = hidden
        if (suspended) {
            pendingPlayWhenReady = false
            player?.pause()
        } else if (wasSuspended) {
            if (resumeAfterSuspension) {
                pendingPlayWhenReady = true
                player?.play()
            }
            resumeAfterSuspension = false
        }
        publish()
    }

    fun publish() {
        val current = player
        val shown = transport.value
        transport.value = shown.copy(
            title = active?.title ?: shown.title,
            subtitle = subtitle.ifBlank { shown.subtitle },
            playing = current?.isPlaying == true,
            playWhenReady = current?.playWhenReady ?: pendingPlayWhenReady,
            positionMs = if (starting) shown.positionMs else current?.currentPosition ?: shown.positionMs,
            durationMs = if (starting) shown.durationMs else current?.duration?.takeIf { it > 0L } ?: shown.durationMs,
        )
        if (!starting && active != null && current != null &&
            (current.playbackState == Player.STATE_READY || current.playbackState == Player.STATE_ENDED)) {
            onProgress?.invoke(transport.value.positionMs, transport.value.durationMs, current.playbackState == Player.STATE_ENDED)
        }
    }

    fun close() {
        player?.pause()
        active = null
        pending = null
        pendingPositionMs = 0
        pendingPlayWhenReady = false
        subtitle = ""
        resumeAfterSuspension = false
        menuOpen = false
        screenHidden = false
        player?.stop()
        player?.clearMediaItems()
        trackCatalog.reset()
        subtitleCues.value = emptyList()
        transport.value = Transport(quality = quality)
    }

    fun release() {
        player?.release()
        player = null
        trackSelector = null
        subtitleCues.value = emptyList()
        trackCatalog.reset()
    }

    private fun start(
        current: ExoPlayer,
        item: Playable,
        url: String,
        positionMs: Long = 0L,
        playWhenReady: Boolean = true,
    ) {
        subtitleCues.value = emptyList()
        trackSelector?.let { selector ->
            selector.setParameters(selector.parameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .build())
        }
        dataSourceFactory.setDefaultRequestProperties(item.headers)
        transport.value = Transport(
            title = item.title,
            subtitle = subtitle,
            detail = "Buffering",
            positionMs = positionMs,
            playbackMode = route.label,
            quality = quality,
            sourceWidth = item.sourceWidth,
            sourceHeight = item.sourceHeight,
            playWhenReady = playWhenReady,
        )
        starting = true
        try {
            current.setMediaItem(MediaItem.fromUri(url), positionMs)
            current.prepare()
            current.playWhenReady = playWhenReady
        } finally { starting = false }
    }

    private val listener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            trackCatalog.update(tracks)
        }

        override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
            subtitleCues.value = if (active != null &&
                tracks.value.selectedSubtitleId != PlaybackTracks.SUBTITLES_OFF_ID
            ) cueGroup.cues else emptyList()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                transport.value = transport.value.copy(detail = "")
            }
            publish()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            Log.i(TAG, "video size ${videoSize.width}x${videoSize.height}")
            transport.value = transport.value.copy(videoWidth = videoSize.width, videoHeight = videoSize.height)
        }

        override fun onRenderedFirstFrame() {
            Log.i(TAG, "video frame rendered")
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            publish()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            pendingPlayWhenReady = playWhenReady
            publish()
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.i(TAG, "playback error ${error.errorCodeName}")
            val item = active
            val current = player
            val next = route.next()
            if (item != null && current != null && next != null) {
                val position = current.currentPosition.coerceAtLeast(0L)
                val resume = current.playWhenReady
                route = next
                updateTrackChoices(item, preserveSelection = true)
                val url = streamUrlForCurrentChoices(item)
                Log.i(TAG, "retry ${route.label}")
                start(current, item, url, position, resume)
                return
            }
            transport.value = transport.value.copy(detail = "Playback failed (${error.errorCodeName})")
            publish()
        }
    }

    private companion object {
        const val TAG = "Theatre"
    }
}
