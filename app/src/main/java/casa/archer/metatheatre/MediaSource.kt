package casa.archer.metatheatre

/** Which backend a library, item, or playable came from. */
enum class SourceKind { PLEX, JELLYFIN }

/** One library/section on either source, in shelf terms. */
data class SourceLibrary(
    val id: String,
    val title: String,
    val kind: SourceKind,
    /** Plex-style media kind: "movie" or "show". */
    val mediaKind: String,
    val poster: String = "",
)

/** One browsable entry (movie, series, season, or episode) on either source. */
data class SourceItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val type: String,
    val kind: SourceKind,
    val poster: String = "",
    val resumeMs: Long = 0L,
    val durationMs: Long = 0L,
    val stereo: DetectedStereo = DetectedStereo.MONO,
)

/**
 * The browsing/playback contract shared by Plex and Jellyfin. All calls are
 * blocking and run on an IO dispatcher, matching the existing clients.
 * [playable] returns the [Playable] the existing [ScreenPlayer] consumes.
 */
interface MediaSource {
    val kind: SourceKind
    fun libraries(): List<SourceLibrary>
    fun items(library: SourceLibrary): List<SourceItem>
    fun children(item: SourceItem): List<SourceItem>
    fun resumeItems(): List<SourceItem>
    fun playable(item: SourceItem): Playable
}

/** Adapts the existing [PlexClient] for one server; Plex behavior is unchanged. */
class PlexMediaSource(
    private val plex: PlexClient,
    private val server: PlexServer,
) : MediaSource {
    override val kind: SourceKind = SourceKind.PLEX

    override fun libraries(): List<SourceLibrary> = plex.sections(server).map { section ->
        SourceLibrary(section.key, section.title, SourceKind.PLEX, section.type, section.poster)
    }

    override fun items(library: SourceLibrary): List<SourceItem> {
        val section = PlexSection(library.id, library.title, library.mediaKind, library.poster)
        return plex.library(server, section).map(::toSourceItem)
    }

    override fun children(item: SourceItem): List<SourceItem> =
        plex.children(server, item.id).map(::toSourceItem)

    override fun resumeItems(): List<SourceItem> = plex.recentlyViewed(server).map(::toSourceItem)

    override fun playable(item: SourceItem): Playable =
        plex.playable(server, PlexItem(item.id, item.title, item.subtitle, item.type))

    private fun toSourceItem(item: PlexItem): SourceItem = SourceItem(
        id = item.ratingKey,
        title = item.title,
        subtitle = item.subtitle,
        type = item.type,
        kind = SourceKind.PLEX,
        poster = item.poster,
        resumeMs = item.viewOffsetMs,
        durationMs = item.durationMs,
    )
}

/** Adapts [JellyfinClient]; library, season/episode, and resume data stay server-side. */
class JellyfinMediaSource(private val jellyfin: JellyfinClient) : MediaSource {
    override val kind: SourceKind = SourceKind.JELLYFIN

    override fun libraries(): List<SourceLibrary> = jellyfin.libraryViews().map { library ->
        SourceLibrary(library.id, library.title, SourceKind.JELLYFIN, library.kind, library.poster)
    }

    override fun items(library: SourceLibrary): List<SourceItem> {
        val types = if (library.mediaKind == "show") listOf("Series", "Movie") else listOf("Movie", "Series")
        return jellyfin.items(parentId = library.id, includeItemTypes = types).map(::toSourceItem)
    }

    override fun children(item: SourceItem): List<SourceItem> =
        jellyfin.children(item.id).map(::toSourceItem)

    override fun resumeItems(): List<SourceItem> = jellyfin.resumeItems().map(::toSourceItem)

    override fun playable(item: SourceItem): Playable =
        jellyfin.playable(JellyfinItem(id = item.id, title = item.title, type = item.type))

    private fun toSourceItem(item: JellyfinItem): SourceItem = SourceItem(
        id = item.id,
        title = item.title,
        subtitle = jellyfinItemSubtitle(item),
        type = item.type,
        kind = SourceKind.JELLYFIN,
        poster = item.poster,
        resumeMs = item.resumeMs,
        durationMs = item.durationMs,
        stereo = item.detectedStereo(),
    )
}
