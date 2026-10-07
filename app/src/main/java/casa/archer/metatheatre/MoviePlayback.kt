package casa.archer.metatheatre

import java.net.URLEncoder
import java.util.UUID
import java.net.URLDecoder

enum class PlaybackQuality(val label: String) {
    ORIGINAL("Original"), UHD("4K"), HD("1080p");

    internal val startingRoute: PlaybackRoute
        get() = when (this) {
            ORIGINAL -> PlaybackRoute.DIRECT
            UHD -> PlaybackRoute.PLEX_UHD
            HD -> PlaybackRoute.PLEX_HD
        }
}

internal enum class MovieQuality(val width: Int, val height: Int, val bitrateKbps: Int) {
    UHD(3840, 2160, 80_000),
    HD(1920, 1080, 20_000),
}

internal enum class PlaybackRoute(val label: String) {
    DIRECT("Original"),
    PLEX_UHD("Plex · up to 4K"),
    PLEX_HD("Plex · up to 1080p"),
    ;

    fun next(): PlaybackRoute? = when (this) {
        DIRECT -> PLEX_UHD
        PLEX_UHD -> PLEX_HD
        PLEX_HD -> null
    }
}

internal data class PlexMediaVersion(val index: Int, val width: Int, val height: Int, val bitrate: Int)

/** Prefer a native UHD copy over an earlier HD/optimized copy of the same movie. */
internal fun preferredMovieVersion(versions: List<PlexMediaVersion>): PlexMediaVersion? {
    val withinUhd = versions.filter { it.width <= MovieQuality.UHD.width && it.height <= MovieQuality.UHD.height }
    return withinUhd.ifEmpty { versions }.maxWithOrNull(
        compareBy<PlexMediaVersion> { it.width.toLong() * it.height }
            .thenBy { it.bitrate }
            .thenBy { -it.index },
    )
}

/** Build authenticated URLs without exposing them in logs or transport/UI state. */
internal fun plexMovieHlsUrl(
    server: PlexServer,
    ratingKey: String,
    clientId: String,
    mediaIndex: Int,
    quality: MovieQuality,
    session: String = UUID.randomUUID().toString().replace("-", ""),
): String {
    val query = linkedMapOf(
        "path" to "/library/metadata/$ratingKey",
        "mediaIndex" to mediaIndex.toString(),
        "partIndex" to "0",
        "protocol" to "hls",
        "context" to "streaming",
        "directPlay" to "0",
        "directStream" to if (quality == MovieQuality.UHD) "1" else "0",
        "fastSeek" to "1",
        "videoCodec" to "h264",
        "audioCodec" to "aac",
        "maxVideoBitrate" to quality.bitrateKbps.toString(),
        "videoBitrate" to quality.bitrateKbps.toString(),
        "videoResolution" to "${quality.width}x${quality.height}",
        "videoQuality" to "99",
        "subtitles" to "none",
        "session" to session,
        "X-Plex-Client-Identifier" to clientId,
        "X-Plex-Product" to "Meta Theatre",
        "X-Plex-Platform" to "Android",
        // Replace generic mobile profile dimensions so Plex can actually return UHD.
        "X-Plex-Client-Profile-Extra" to listOf(
            "add-limitation(scope=videoCodec&scopeName=*&type=upperBound&name=video.width&value=${quality.width}&replace=true)",
            "add-limitation(scope=videoCodec&scopeName=*&type=upperBound&name=video.height&value=${quality.height}&replace=true)",
        ).joinToString("+"),
        "X-Plex-Token" to server.token,
    )
    val encoded = query.entries.joinToString("&") { (key, value) ->
        "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
    }
    return server.uri.trimEnd('/') + "/video/:/transcode/universal/start.m3u8?$encoded"
}

/** Replace Plex's default stream choices while retaining the selected quality request. */
internal fun plexStreamSelectionUrl(
    url: String,
    audioStreamId: String? = null,
    subtitleStreamId: String? = null,
): String {
    val question = url.indexOf('?')
    if (question < 0) return url
    val base = url.substring(0, question)
    val parameters = url.substring(question + 1).split('&').filter(String::isNotBlank)
        .filterNot { parameter ->
            val name = parameter.substringBefore('=').let { URLDecoder.decode(it, "UTF-8") }
            name.equals("audioStreamID", ignoreCase = true) ||
                name.equals("subtitleStreamID", ignoreCase = true) ||
                name.equals("subtitles", ignoreCase = true) ||
                name.equals("session", ignoreCase = true)
        }.toMutableList()
    if (audioStreamId != null) parameters.add("audioStreamID=${URLEncoder.encode(audioStreamId, "UTF-8")}")
    if (subtitleStreamId != null) {
        parameters.add("subtitleStreamID=${URLEncoder.encode(subtitleStreamId, "UTF-8")}")
        parameters.add("subtitles=burn")
    } else {
        parameters.add("subtitles=none")
    }
    parameters.add("session=${UUID.randomUUID().toString().replace("-", "")}")
    return "$base?${parameters.joinToString("&")}"
}

internal fun movieResolutionLabel(width: Int, height: Int): String {
    if (width <= 0 || height <= 0) return ""
    return if (width >= 3840 && height >= 1600) "4K · ${width}×${height}" else "${width}×${height}"
}

/** Explain a quality ceiling using metadata and the video actually delivered by Plex. */
internal fun movieQualityNotice(
    quality: PlaybackQuality,
    sourceWidth: Int,
    sourceHeight: Int,
    videoWidth: Int,
    videoHeight: Int,
): String = when {
    quality == PlaybackQuality.UHD && sourceWidth in 1 until 3840 ->
        "4K needs a 4K source file"
    quality == PlaybackQuality.UHD && sourceWidth >= 3840 && videoWidth in 1 until 3840 ->
        "Plex is delivering below 4K; try Original or check server limits"
    sourceWidth > 0 && sourceHeight > 0 && videoWidth > 0 && videoHeight > 0 &&
        (videoWidth < sourceWidth || videoHeight < sourceHeight) ->
        "Plex is delivering a smaller video"
    else -> ""
}
