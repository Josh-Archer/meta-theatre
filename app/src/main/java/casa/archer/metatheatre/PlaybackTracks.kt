package casa.archer.metatheatre

import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.TrackGroup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

internal enum class PlaybackTrackType { AUDIO, SUBTITLE }

internal fun PlexPlaybackStream.toPlaybackTrack(type: PlaybackTrackType): PlaybackTrack {
    val kind = if (type == PlaybackTrackType.AUDIO) "audio" else "subtitle"
    val details = listOf(title, language, codec).filter(String::isNotBlank).distinct()
    val fallback = if (type == PlaybackTrackType.AUDIO) "Audio" else "Subtitle"
    return PlaybackTrack(
        id = "plex-$kind:$id",
        label = "Plex · " + details.joinToString(" · ").ifBlank { fallback },
        language = languageCode.ifBlank { language },
        selectedInitially = selected,
        plexStreamId = id,
    )
}

/** A selectable audio or subtitle stream. A Plex ID selects the stream on the server. */
data class PlaybackTrack(
    val id: String,
    val label: String,
    val language: String = "",
    internal val selectedInitially: Boolean = false,
    internal val plexStreamId: String? = null,
    internal val mediaTrackGroup: TrackGroup? = null,
    internal val mediaTrackIndex: Int = -1,
)

data class PlaybackTracks(
    val audio: List<PlaybackTrack> = emptyList(),
    val subtitles: List<PlaybackTrack> = listOf(PlaybackTrack(SUBTITLES_OFF_ID, "Off")),
    val selectedAudioId: String? = null,
    val selectedSubtitleId: String? = SUBTITLES_OFF_ID,
) {
    companion object {
        const val SUBTITLES_OFF_ID = "subtitle:off"
    }
}

/** Keeps user-facing choices in sync with Plex metadata and the prepared Media3 item. */
internal class PlaybackTrackCatalog {
    private val mutableTracks = MutableStateFlow(PlaybackTracks())
    val tracks: StateFlow<PlaybackTracks> = mutableTracks

    private var plexAudio: List<PlaybackTrack> = emptyList()
    private var plexSubtitles: List<PlaybackTrack> = emptyList()
    private var nativeAudio: List<PlaybackTrack> = emptyList()
    private var nativeSubtitles: List<PlaybackTrack> = emptyList()
    private var nativeSelectedAudioId: String? = null
    private var nativeSelectedSubtitleId: String? = null
    private var userSelectedAudioId: String? = null
    private var userSelectedSubtitleId: String? = null

    fun reset(
        audio: List<PlaybackTrack> = emptyList(),
        subtitles: List<PlaybackTrack> = emptyList(),
        preserveSelection: Boolean = false,
    ) {
        val previous = mutableTracks.value
        val selectedAudio = if (preserveSelection) userSelectedAudioId ?: previous.selectedAudioId else null
        val selectedSubtitle = if (preserveSelection) userSelectedSubtitleId ?: previous.selectedSubtitleId else null
        plexAudio = audio
        plexSubtitles = subtitles
        nativeAudio = emptyList()
        nativeSubtitles = emptyList()
        nativeSelectedAudioId = null
        nativeSelectedSubtitleId = null
        if (!preserveSelection) {
            userSelectedAudioId = null
            userSelectedSubtitleId = null
        }
        mutableTracks.value = PlaybackTracks(
            selectedAudioId = selectedAudio?.takeIf { id -> audio.any { it.id == id } }
                ?: audio.firstOrNull { it.selectedInitially }?.id,
            selectedSubtitleId = selectedSubtitle?.takeIf { id ->
                id == PlaybackTracks.SUBTITLES_OFF_ID || subtitles.any { it.id == id }
            } ?: subtitles.firstOrNull { it.selectedInitially }?.id
                ?: PlaybackTracks.SUBTITLES_OFF_ID,
        )
        publish()
    }

    fun update(tracks: Tracks) {
        nativeAudio = buildList {
            tracks.groups.forEachIndexed { groupIndex, group ->
                if (group.type != C.TRACK_TYPE_AUDIO) return@forEachIndexed
                for (trackIndex in 0 until group.length) {
                    if (!group.isTrackSupported(trackIndex)) continue
                    val format = group.getTrackFormat(trackIndex)
                    add(
                        PlaybackTrack(
                            id = "media-audio:$groupIndex:$trackIndex",
                            label = trackLabel(format.label, format.language, format.codecs, "Audio"),
                            language = format.language.orEmpty(),
                            mediaTrackGroup = group.mediaTrackGroup,
                            mediaTrackIndex = trackIndex,
                        ),
                    )
                }
            }
        }
        nativeSelectedAudioId = nativeAudio.firstOrNull { option ->
            tracks.groups.any { group ->
                group.type == C.TRACK_TYPE_AUDIO && group.mediaTrackGroup == option.mediaTrackGroup &&
                    (0 until group.length).any { index -> group.isTrackSelected(index) && option.mediaTrackIndex == index }
            }
        }?.id
        nativeSubtitles = buildList {
            tracks.groups.forEachIndexed { groupIndex, group ->
                if (group.type != C.TRACK_TYPE_TEXT) return@forEachIndexed
                for (trackIndex in 0 until group.length) {
                    if (!group.isTrackSupported(trackIndex)) continue
                    val format = group.getTrackFormat(trackIndex)
                    add(
                        PlaybackTrack(
                            id = "media-subtitle:$groupIndex:$trackIndex",
                            label = trackLabel(format.label, format.language, format.sampleMimeType, "Subtitle"),
                            language = format.language.orEmpty(),
                            mediaTrackGroup = group.mediaTrackGroup,
                            mediaTrackIndex = trackIndex,
                        ),
                    )
                }
            }
        }
        nativeSelectedSubtitleId = nativeSubtitles.firstOrNull { option ->
            tracks.groups.any { group ->
                group.type == C.TRACK_TYPE_TEXT && group.mediaTrackGroup == option.mediaTrackGroup &&
                    (0 until group.length).any { index -> group.isTrackSelected(index) && option.mediaTrackIndex == index }
            }
        }?.id
        publish()
    }

    fun select(type: PlaybackTrackType, id: String?) {
        val current = mutableTracks.value
        mutableTracks.value = when (type) {
            PlaybackTrackType.AUDIO -> current.copy(selectedAudioId = id).also { userSelectedAudioId = id }
            PlaybackTrackType.SUBTITLE -> current.copy(selectedSubtitleId = id ?: PlaybackTracks.SUBTITLES_OFF_ID)
                .also { userSelectedSubtitleId = id ?: PlaybackTracks.SUBTITLES_OFF_ID }
        }
    }

    private fun publish() {
        val previous = mutableTracks.value
        val audio = (nativeAudio + plexAudio).distinctBy { it.id }
        val subtitles = listOf(PlaybackTrack(PlaybackTracks.SUBTITLES_OFF_ID, "Off")) +
            (nativeSubtitles + plexSubtitles).distinctBy { it.id }
        mutableTracks.value = PlaybackTracks(
            audio = audio,
            subtitles = subtitles,
            selectedAudioId = userSelectedAudioId?.takeIf { id -> audio.any { it.id == id } }
                ?: nativeSelectedAudioId
                ?: previous.selectedAudioId?.takeIf { id -> audio.any { it.id == id } }
                ?: plexAudio.firstOrNull { it.selectedInitially }?.id,
            selectedSubtitleId = userSelectedSubtitleId?.takeIf { id -> subtitles.any { it.id == id } }
                ?: nativeSelectedSubtitleId
                ?: previous.selectedSubtitleId?.takeIf { id -> subtitles.any { it.id == id } }
                ?: plexSubtitles.firstOrNull { it.selectedInitially }?.id
                ?: PlaybackTracks.SUBTITLES_OFF_ID,
        )
    }

    private companion object {
        fun languageLabel(language: String?): String = language
            ?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }
            ?.let { Locale.forLanguageTag(it).getDisplayName(Locale.getDefault()) }
            .orEmpty()

        fun trackLabel(label: String?, language: String?, detail: String?, fallback: String): String =
            listOf(label?.takeIf { it.isNotBlank() }, languageLabel(language), detail?.takeIf { it.isNotBlank() })
                .distinct().filterNotNull().joinToString(" · ").ifBlank { fallback }
    }
}
