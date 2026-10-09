package casa.archer.metatheatre

/** Physical spacing for the video and its independent side controls. */
internal object Stage {
    const val PLAYER_WIDTH = 0.56f
    const val PLAYER_HEIGHT = 1.64f
    const val PLAYER_GAP = 0.10f
    const val PLAYER_SLIDE = 0.20f
    const val VIDEO_FORWARD = 0.05f
    const val PLAYER_FORWARD = 0.09f
    const val HIDDEN_BACK = 0.40f
}

internal object PosterGrid {
    const val COLUMNS = 5
    const val HEADER_DP = 108f
    const val SEARCH_DP = 72f
    const val FOOTER_DP = 84f
    const val PANEL_DP = 900f
    const val HEADER_END = HEADER_DP / PANEL_DP
    const val SEARCH_END = (HEADER_DP + SEARCH_DP) / PANEL_DP
    const val FOOTER_START = 1f - FOOTER_DP / PANEL_DP
    const val SEARCH_QUERY_END = 0.44f
    const val SEARCH_CLEAR_END = 0.55f
    const val SEARCH_FILTER_START = SEARCH_CLEAR_END
    const val SEARCH_FILTER_WIDTH = (1f - SEARCH_FILTER_START) / 4f
    const val KEYBOARD_START = 0.52f
    const val KEYBOARD_ROW_HEIGHT = 0.065f
    const val BACK_X = 0.18f
    const val PLAYER_X = 0.62f
    const val SIGN_OUT_X = 0.82f
    const val SIGN_OUT_CONFIRM_TOP = 0.60f
    const val SIGN_OUT_CONFIRM_BOTTOM = 0.78f
    const val PREV_X = 0.28f
    const val NEXT_X = 0.72f

    fun maxStart(total: Int): Int = (total - COLUMNS).coerceAtLeast(0)

    fun window(total: Int, page: Int): ShelfWindow {
        if (total <= 0) return ShelfWindow(0, 0, 0)
        val start = page.coerceIn(0, maxStart(total))
        val shown = (total - start).coerceAtMost(COLUMNS)
        val pad = if (total <= COLUMNS) (COLUMNS - shown) / 2 else 0
        return ShelfWindow(start, shown, pad)
    }

    fun indexAt(x: Float, y: Float, total: Int, page: Int): Int? {
        if (total <= 0 || y < SEARCH_END || y >= FOOTER_START) return null
        val window = window(total, page)
        val column = (x.coerceIn(0f, 0.9999f) * COLUMNS).toInt()
        val offset = column - window.pad
        if (offset < 0 || offset >= window.shown) return null
        return window.start + offset
    }

    fun libraryKeyboardKeyAt(x: Float, y: Float): LibraryKeyboardKey? {
        if (y < KEYBOARD_START || y >= FOOTER_START) return null
        val row = ((y - KEYBOARD_START) / KEYBOARD_ROW_HEIGHT).toInt()
        val rowLayout = LIBRARY_KEYBOARD_ROWS.getOrNull(row) ?: return null
        val relativeX = (x - rowLayout.start) / rowLayout.width
        if (relativeX !in 0f..1f) return null
        val index = (relativeX * rowLayout.keys.size).toInt().coerceAtMost(rowLayout.keys.lastIndex)
        return rowLayout.keys[index]
    }

    val LIBRARY_KEYBOARD_ROWS = listOf(
        LibraryKeyboardRow("1234567890".map { LibraryKeyboardKey.Character(it) }, 0f, 1f),
        LibraryKeyboardRow("qwertyuiop".map { LibraryKeyboardKey.Character(it) }, 0f, 1f),
        LibraryKeyboardRow("asdfghjkl".map { LibraryKeyboardKey.Character(it) }, 0.05f, 0.90f),
        LibraryKeyboardRow(
            "zxcvbnm".map { LibraryKeyboardKey.Character(it) } + LibraryKeyboardKey.Backspace,
            0.12f,
            0.76f,
        ),
        LibraryKeyboardRow(listOf(LibraryKeyboardKey.Space, LibraryKeyboardKey.Done), 0.18f, 0.64f),
    )
}

internal data class LibraryKeyboardRow(val keys: List<LibraryKeyboardKey>, val start: Float, val width: Float)

internal sealed interface LibraryKeyboardKey {
    data class Character(val value: Char) : LibraryKeyboardKey
    data object Backspace : LibraryKeyboardKey
    data object Space : LibraryKeyboardKey
    data object Done : LibraryKeyboardKey
}

internal data class ShelfWindow(val start: Int, val shown: Int, val pad: Int)

internal data class ShelfTile(val title: String, val subtitle: String, val poster: String)

internal fun shelfSize(browse: Browse): Int = when (browse) {
    is Browse.Home -> browse.videos.size
    is Browse.Servers -> browse.servers.size
    is Browse.Sections -> browse.sections.size
    is Browse.Items -> browse.items.size
    else -> 0
}

internal fun shelfTiles(browse: Browse): List<ShelfTile>? {
    return when (browse) {
        is Browse.Home -> browse.videos.map { video ->
            val resume = video.item.viewOffsetMs
            val label = if (resume > 0) "Resume at ${clock(resume)}" else video.item.subtitle
            ShelfTile(video.item.title, label, video.item.poster)
        }
        is Browse.Servers -> browse.servers.map { server ->
            ShelfTile(server.name.ifBlank { "Plex" }, "Server", "")
        }
        is Browse.Sections -> browse.sections.map { section ->
            ShelfTile(
                section.title,
                section.type.replaceFirstChar { it.uppercase() },
                section.poster,
            )
        }
        is Browse.Items -> browse.items.map { item ->
            ShelfTile(item.title, item.subtitle, item.poster)
        }
        else -> null
    }
}
