package casa.archer.metatheatre

import com.meta.spatial.runtime.StereoMode

/**
 * Stereoscopic format detection, ported from questkit's `xr-video::format`
 * (DeskQuest#220) and quest-jellyfin-3d's `quest-jellyfin-player::format`
 * (PR #38). Precedence, applied independently of projection: an MV-HEVC
 * stream/filename label wins, then Jellyfin's `Video3DFormat`, then filename
 * packing tags, then mono/flat. Detection never opens the file and does not
 * imply the decoder can play the result.
 */
enum class StereoFormat {
    MONO, HALF_SIDE_BY_SIDE, FULL_SIDE_BY_SIDE, HALF_OVER_UNDER, FULL_OVER_UNDER,
    MVC, MV_HEVC, UNSPECIFIED_3D,
}

/** Which half of a packed frame holds the left eye. */
enum class EyeOrder { LEFT_RIGHT, RIGHT_LEFT, TOP_BOTTOM, BOTTOM_TOP }

enum class StereoProjection { FLAT, EQUIRECT_180, EQUIRECT_360, FISHEYE }

enum class DetectionSource { JELLYFIN, FILENAME, CONTAINER, DEFAULT }

data class DetectedStereo(
    val format: StereoFormat = StereoFormat.MONO,
    val eyeOrder: EyeOrder = EyeOrder.LEFT_RIGHT,
    val projection: StereoProjection = StereoProjection.FLAT,
    val source: DetectionSource = DetectionSource.DEFAULT,
) {
    val isStereo: Boolean get() = format != StereoFormat.MONO
    val label: String
        get() = when (format) {
            StereoFormat.MONO -> ""
            StereoFormat.HALF_SIDE_BY_SIDE -> "3D HSBS"
            StereoFormat.FULL_SIDE_BY_SIDE -> "3D FSBS"
            StereoFormat.HALF_OVER_UNDER -> "3D HOU"
            StereoFormat.FULL_OVER_UNDER -> "3D FOU"
            StereoFormat.MVC -> "3D MVC"
            StereoFormat.MV_HEVC -> "3D MV-HEVC"
            StereoFormat.UNSPECIFIED_3D -> "3D"
        }

    companion object {
        val MONO = DetectedStereo()
    }
}

/** Map every Jellyfin `Video3DFormat` spelling; unknown values return null. */
fun jellyfinStereoFormat(value: String?): StereoFormat? =
    when (value?.trim()?.lowercase()) {
        "halfsidebyside" -> StereoFormat.HALF_SIDE_BY_SIDE
        "fullsidebyside" -> StereoFormat.FULL_SIDE_BY_SIDE
        "fulloverunder", "fulltopandbottom" -> StereoFormat.FULL_OVER_UNDER
        "halfoverunder", "halftopandbottom" -> StereoFormat.HALF_OVER_UNDER
        "mvc" -> StereoFormat.MVC
        else -> null
    }

/**
 * Detect packing, eye order, and projection for one item. [streamLabels] are
 * the codec/profile strings of the item's video streams, used only for the
 * MV-HEVC check (an ordinary `hevc` label never matches).
 */
fun detectStereo(
    video3dFormat: String?,
    path: String?,
    streamLabels: List<String> = emptyList(),
): DetectedStereo {
    val filename = filenameHints(path)
    val explicitMultiview = streamLabels.any(::isMvHevcLabel)
    val filenameMultiview = path != null && isMvHevcFilename(path)
    val (format, source) = when {
        explicitMultiview -> StereoFormat.MV_HEVC to DetectionSource.CONTAINER
        filenameMultiview -> StereoFormat.MV_HEVC to DetectionSource.FILENAME
        else -> {
            val fromJellyfin = jellyfinStereoFormat(video3dFormat)
            when {
                fromJellyfin != null -> fromJellyfin to DetectionSource.JELLYFIN
                filename.stereo != null -> filename.stereo to DetectionSource.FILENAME
                else -> StereoFormat.MONO to DetectionSource.DEFAULT
            }
        }
    }
    val fromName = path?.let(::eyeOrderFromFilename)
    val eyeOrder = when (format) {
        StereoFormat.HALF_OVER_UNDER, StereoFormat.FULL_OVER_UNDER ->
            if (fromName == EyeOrder.BOTTOM_TOP) EyeOrder.BOTTOM_TOP else EyeOrder.TOP_BOTTOM
        StereoFormat.HALF_SIDE_BY_SIDE, StereoFormat.FULL_SIDE_BY_SIDE ->
            if (fromName == EyeOrder.RIGHT_LEFT) EyeOrder.RIGHT_LEFT else EyeOrder.LEFT_RIGHT
        else -> EyeOrder.LEFT_RIGHT
    }
    return DetectedStereo(format, eyeOrder, filename.projection ?: StereoProjection.FLAT, source)
}

/** Route a detected format to the video panel's stereo mode, if it has one. */
fun stereoModeFor(format: StereoFormat): StereoMode = when (format) {
    StereoFormat.HALF_SIDE_BY_SIDE, StereoFormat.FULL_SIDE_BY_SIDE -> StereoMode.LeftRight
    StereoFormat.HALF_OVER_UNDER, StereoFormat.FULL_OVER_UNDER -> StereoMode.UpDown
    // Mono, MVC, MV-HEVC, and unspecified 3D have no packed panel mode here.
    else -> StereoMode.None
}

private data class FilenameHints(val stereo: StereoFormat?, val projection: StereoProjection?)

private fun filenameHints(path: String?): FilenameHints {
    if (path == null) return FilenameHints(null, null)
    val basename = path.split('/', '\\').lastOrNull().orEmpty()
    val stem = basename.substringBeforeLast('.', basename)
    val lower = stem.lowercase()
    var explicit: StereoFormat? = null
    var generic: StereoFormat? = null
    var is3d = false
    var projection: StereoProjection? = null
    var fisheye = false
    val tokenPattern = Regex("[a-z0-9]+")
    for (match in tokenPattern.findAll(lower)) {
        val token = match.value
        val underscore = match.range.first > 0 && lower[match.range.first - 1] == '_'
        if (explicit == null) {
            explicit = when (token) {
                "hsbs" -> StereoFormat.HALF_SIDE_BY_SIDE
                "fsbs" -> StereoFormat.FULL_SIDE_BY_SIDE
                "hou", "htab" -> StereoFormat.HALF_OVER_UNDER
                "fou", "ftab" -> StereoFormat.FULL_OVER_UNDER
                "mvc" -> StereoFormat.MVC
                "mvhevc" -> StereoFormat.MV_HEVC
                else -> null
            }
        }
        if (generic == null) {
            generic = when {
                token == "sbs" -> StereoFormat.HALF_SIDE_BY_SIDE
                token == "ou" || token == "tab" -> StereoFormat.HALF_OVER_UNDER
                token == "lr" && underscore -> StereoFormat.HALF_SIDE_BY_SIDE
                token == "tb" && underscore -> StereoFormat.HALF_OVER_UNDER
                else -> null
            }
        }
        if (token == "3d") is3d = true
        if (token == "fisheye") fisheye = true
        if (projection == null) {
            projection = when (token) {
                "180" -> StereoProjection.EQUIRECT_180
                "360" -> StereoProjection.EQUIRECT_360
                else -> null
            }
        }
    }
    val stereo = explicit ?: generic ?: if (is3d) StereoFormat.UNSPECIFIED_3D else null
    return FilenameHints(stereo, if (fisheye) StereoProjection.FISHEYE else projection)
}

private fun isMvHevcLabel(value: String): Boolean {
    val normalized = value.filter { it.isLetterOrDigit() }.lowercase()
    return normalized in setOf("mvhevc", "spatialmvhevc", "multiviewhevc", "multiviewmain", "multiviewmain10")
}

private fun isMvHevcFilename(path: String): Boolean {
    val basename = path.split('/', '\\').lastOrNull().orEmpty()
    val stem = basename.substringBeforeLast('.', basename)
    val tokens = stem.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
    if (tokens.any(::isMvHevcLabel)) return true
    return tokens.zipWithNext().any { (a, b) ->
        a.equals("mv", ignoreCase = true) && b.equals("hevc", ignoreCase = true)
    }
}

/** `RL`/`BT` (and `LR`/`TB`) as whole tokens of the basename. */
private fun eyeOrderFromFilename(path: String): EyeOrder? {
    val basename = path.split('/', '\\').lastOrNull().orEmpty()
    val stem = basename.substringBeforeLast('.', basename)
    return stem.split(Regex("[^A-Za-z0-9]+")).firstNotNullOfOrNull { token ->
        when (token.lowercase()) {
            "rl" -> EyeOrder.RIGHT_LEFT
            "lr" -> EyeOrder.LEFT_RIGHT
            "bt" -> EyeOrder.BOTTOM_TOP
            "tb" -> EyeOrder.TOP_BOTTOM
            else -> null
        }
    }
}
