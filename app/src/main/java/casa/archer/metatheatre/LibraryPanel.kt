package casa.archer.metatheatre

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Ink = Color(0xFFF4F1EA)
private val Muted = Color(0xFFB7C0CC)
private val Panel = Color(0xFF12161E)
private val Card = Color(0xFF1C2430)
private val Accent = Color(0xFFD7B56D)
private val Zone = Color(0xFF2A3342)

@Composable
fun LibraryPanel(model: TheatreModel, player: ScreenPlayer) {
    val browse by model.browse.collectAsState()
    val loading by model.loading.collectAsState()
    val error by model.error.collectAsState()
    val canGoBack by model.canGoBack.collectAsState()
    val page by model.page.collectAsState()
    val query by model.librarySearchQuery.collectAsState()
    val filter by model.librarySearchFilter.collectAsState()
    val keyboardOpen by model.librarySearchKeyboardOpen.collectAsState()
    val confirmSignOut by model.signOutConfirmation.collectAsState()
    val movieReady by remember(player) {
        player.transport.map { it.title.isNotBlank() }.distinctUntilChanged()
    }.collectAsState(initial = false)
    val tiles = remember(browse) { shelfTiles(browse).orEmpty() }
    val window = PosterGrid.window(tiles.size, page)

    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, onPrimary = Color(0xFF1A1408))) {
        if (confirmSignOut) {
            SignOutPrompt(model)
            return@MaterialTheme
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Panel)
                .border(4.dp, Accent)
                .clipToBounds(),
        ) {
            ShelfHeader(browse, canGoBack, loading, error, movieReady, model)
            if (searchAvailable(browse)) {
                LibrarySearchBar(browse, query, filter, tiles.size, model)
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (browse) {
                    Browse.SignedOut -> SignedOut(model)
                    is Browse.Linking -> Linking((browse as Browse.Linking).code)
                    else -> {
                        if (tiles.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                val filtered = query.isNotBlank() || filter != LibrarySearchFilter.ALL
                                Text(
                                    when {
                                        filtered -> "No titles match. Clear search or filters to see the full shelf."
                                        browse is Browse.Home -> "Choose Libraries to find a movie. Your recently watched videos will appear here."
                                        else -> "Nothing in this library."
                                    },
                                    color = Muted,
                                    fontSize = 28.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(40.dp),
                                )
                            }
                        } else {
                            PosterWheel(tiles, window)
                        }
                    }
                }
                if (keyboardOpen && searchAvailable(browse)) {
                    LibrarySearchKeyboard(model)
                }
            }
            ShelfFooter(browse, tiles.size, window)
        }
    }
}

@Composable
private fun SignOutPrompt(model: TheatreModel) {
    Box(Modifier.fillMaxSize().background(Panel).border(4.dp, Accent)) {
        Column(Modifier.fillMaxWidth().offset(y = 300.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Sign out of Plex?", color = Ink, fontSize = 36.sp)
            Text("You will need to link your account again.", color = Muted, fontSize = 24.sp)
        }
        Row(Modifier.fillMaxWidth().offset(y = (PosterGrid.SIGN_OUT_CONFIRM_TOP * PosterGrid.PANEL_DP).dp)
            .height(((PosterGrid.SIGN_OUT_CONFIRM_BOTTOM - PosterGrid.SIGN_OUT_CONFIRM_TOP) * PosterGrid.PANEL_DP).dp)) {
            ZoneButton("Keep signed in", Modifier.fillMaxHeight().weight(1f)) { model.cancelSignOut() }
            ZoneButton("Sign out", Modifier.fillMaxHeight().weight(1f)) { model.confirmSignOut() }
        }
    }
}

private fun searchAvailable(browse: Browse): Boolean = when (browse) {
    is Browse.Home, is Browse.Servers, is Browse.Sections, is Browse.Items -> true
    else -> false
}

@Composable
private fun LibrarySearchBar(
    browse: Browse,
    query: String,
    filter: LibrarySearchFilter,
    resultCount: Int,
    model: TheatreModel,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(PosterGrid.SEARCH_DP.dp).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(PosterGrid.SEARCH_QUERY_END).fillMaxHeight()
                .clip(RoundedCornerShape(12.dp)).background(Card).padding(horizontal = 14.dp, vertical = 4.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
            BasicTextField(
                value = query,
                onValueChange = model::setLibrarySearchQuery,
                readOnly = true,
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = Ink, fontSize = 21.sp),
                decorationBox = { inner ->
                    Box {
                        if (query.isBlank()) Text("Search titles", color = Muted, fontSize = 21.sp)
                        inner()
                    }
                },
            )
            Text("$resultCount results", color = Muted, fontSize = 13.sp, maxLines = 1)
        }
        SearchControl(
            label = "Clear",
            modifier = Modifier.weight(PosterGrid.SEARCH_CLEAR_END - PosterGrid.SEARCH_QUERY_END),
            selected = false,
        ) { model.clearLibrarySearch() }
        LibrarySearchFilter.entries.forEach { entry ->
            val enabled = when (entry) {
                LibrarySearchFilter.ALL -> true
                LibrarySearchFilter.MOVIES, LibrarySearchFilter.TV -> browse is Browse.Home || browse is Browse.Sections || browse is Browse.Items
                LibrarySearchFilter.CONTINUE -> browse is Browse.Home || browse is Browse.Items
            }
            SearchControl(
                label = entry.label,
                modifier = Modifier.weight(PosterGrid.SEARCH_FILTER_WIDTH),
                selected = filter == entry,
                enabled = enabled,
            ) { if (enabled) model.setLibrarySearchFilter(entry) }
        }
    }
}

@Composable
private fun SearchControl(
    label: String,
    modifier: Modifier,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier.fillMaxHeight().padding(horizontal = 3.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp)).background(when { selected -> Accent; enabled -> Zone; else -> Panel })
            .semantics { if (enabled) { role = Role.Button; onClick { onClick(); true } } },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) Color(0xFF1A1408) else if (enabled) Ink else Muted, fontSize = 17.sp, maxLines = 1)
    }
}

@Composable
private fun LibrarySearchKeyboard(model: TheatreModel) {
    Box(Modifier.fillMaxSize().background(Color(0xD9000000))) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .offset(y = (PosterGrid.KEYBOARD_START * PosterGrid.PANEL_DP - PosterGrid.HEADER_DP - PosterGrid.SEARCH_DP).dp)
                .height((PosterGrid.KEYBOARD_ROW_HEIGHT * PosterGrid.PANEL_DP * PosterGrid.LIBRARY_KEYBOARD_ROWS.size).dp)
                .padding(horizontal = 0.dp),
        ) {
            PosterGrid.LIBRARY_KEYBOARD_ROWS.forEach { KeyboardRow(it, model) }
        }
    }
}

@Composable
private fun ColumnScope.KeyboardRow(row: LibraryKeyboardRow, model: TheatreModel) {
    Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
        SpacerWidth(row.start)
            row.keys.forEach { key ->
            val label = when (key) {
                is LibraryKeyboardKey.Character -> key.value.toString()
                LibraryKeyboardKey.Backspace -> "⌫"
                LibraryKeyboardKey.Space -> "Space"
                LibraryKeyboardKey.Done -> "Done"
            }
            KeyboardKey(label, Modifier.weight(row.width / row.keys.size)) {
                when (key) {
                    is LibraryKeyboardKey.Character -> model.librarySearchPressForKey(key.value)
                    LibraryKeyboardKey.Backspace -> model.librarySearchBackspace()
                    LibraryKeyboardKey.Space -> model.librarySearchSpace()
                    LibraryKeyboardKey.Done -> model.librarySearchDone()
                }
            }
        }
        SpacerWidth(1f - row.start - row.width)
    }
}

@Composable
private fun RowScope.SpacerWidth(weight: Float) {
    if (weight > 0f) Box(Modifier.weight(weight))
}

@Composable
private fun KeyboardKey(label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier.fillMaxHeight().padding(horizontal = 3.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp)).background(if (label == "Done") Accent else Zone)
            .semantics { role = Role.Button; onClick { onClick(); true } },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Ink, fontSize = if (label.length == 1) 24.sp else 17.sp) }
}

@Composable
private fun ShelfHeader(
    browse: Browse,
    canGoBack: Boolean,
    loading: Boolean,
    error: String?,
    movieReady: Boolean,
    model: TheatreModel,
) {
    val action = when (browse) {
        Browse.SignedOut -> ""
        is Browse.Linking -> "Cancel"
        else -> "Sign out"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(PosterGrid.HEADER_DP.dp)
            .clipToBounds(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZoneButton(
            label = if (canGoBack) "Back" else "",
            modifier = Modifier.fillMaxHeight().weight(PosterGrid.BACK_X),
        ) { if (canGoBack) model.back() }
        Column(
            modifier = Modifier
                .weight(PosterGrid.PLAYER_X - PosterGrid.BACK_X)
                .padding(horizontal = 16.dp),
        ) {
            Text("Theatre", color = Accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                titleFor(browse),
                color = Ink,
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            when {
                error != null -> Text(
                    error,
                    color = Color(0xFFFFB4A8),
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                loading -> Text("Loading…", color = Muted, fontSize = 16.sp)
            }
        }
        ZoneButton(
            label = if (browse is Browse.Home) "Libraries" else if (movieReady && browse !is Browse.SignedOut) "Player" else "",
            modifier = Modifier.fillMaxHeight().weight(PosterGrid.SIGN_OUT_X - PosterGrid.PLAYER_X),
        ) { if (browse is Browse.Home) model.openLibraries() else model.showPlayer() }
        ZoneButton(
            label = action,
            modifier = Modifier.fillMaxHeight().weight(1f - PosterGrid.SIGN_OUT_X),
        ) { if (action.isNotBlank()) model.requestSignOut() }
    }
}

@Composable
private fun PosterWheel(tiles: List<ShelfTile>, window: ShelfWindow) {
    Row(modifier = Modifier.fillMaxSize()) {
        repeat(PosterGrid.COLUMNS) { column ->
            val offset = column - window.pad
            val index = window.start + offset
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                if (offset in 0 until window.shown) {
                    PosterCard(tiles[index], featured = column == PosterGrid.COLUMNS / 2)
                }
            }
        }
    }
}

@Composable
private fun PosterCard(tile: ShelfTile, featured: Boolean) {
    val inset = if (featured) 8.dp else 18.dp
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = inset, vertical = 12.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Card)
                .then(
                    if (featured) Modifier.border(3.dp, Accent, RoundedCornerShape(18.dp)) else Modifier,
                ),
            contentAlignment = Alignment.Center,
        ) {
            PosterArt(tile.poster, Modifier.fillMaxSize())
            if (tile.poster.isBlank()) {
                Text(
                    tile.title,
                    color = Ink,
                    fontSize = if (featured) 28.sp else 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(12.dp),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            tile.title,
            color = Ink,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (tile.subtitle.isNotBlank()) {
            Text(tile.subtitle, color = Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PosterArt(url: String, modifier: Modifier) {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(Posters.cached(url)) }
    LaunchedEffect(url) {
        if (bitmap == null && url.isNotBlank()) bitmap = Posters.load(url)
    }
    val image = bitmap ?: return
    Image(
        bitmap = image,
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Crop,
    )
}

@Composable
private fun ShelfFooter(browse: Browse, total: Int, window: ShelfWindow) {
    val maxStart = PosterGrid.maxStart(total)
    val label = when {
        browse is Browse.SignedOut -> "Point and pinch, or use the trigger"
        browse is Browse.Linking -> "Cancel is the top right"
        total <= 0 -> ""
        total <= PosterGrid.COLUMNS -> "Point and pinch, or use the trigger"
        else -> "${window.start + 1}–${window.start + window.shown} of $total"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(PosterGrid.FOOTER_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZoneButton(
            label = if (window.start > 0) "Previous" else "",
            modifier = Modifier.fillMaxHeight().weight(PosterGrid.PREV_X),
        ) {}
        Box(
            modifier = Modifier.weight(PosterGrid.NEXT_X - PosterGrid.PREV_X),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = Muted, fontSize = 18.sp, maxLines = 1)
        }
        ZoneButton(
            label = if (window.start < maxStart) "Next" else "",
            modifier = Modifier.fillMaxHeight().weight(1f - PosterGrid.NEXT_X),
        ) {}
    }
}

@Composable
private fun SignedOut(model: TheatreModel) {
    Column(
        modifier = Modifier.fillMaxSize().padding(36.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Point at the screen and pinch or pull the trigger to link Plex.",
            color = Muted,
            fontSize = 24.sp,
        )
        Button(
            onClick = { model.link() },
            modifier = Modifier.fillMaxWidth().height(96.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent),
        ) {
            Text("Link Plex", fontSize = 28.sp, color = Color(0xFF1A1408))
        }
    }
}

@Composable
private fun Linking(code: String) {
    Column(modifier = Modifier.fillMaxSize().padding(36.dp)) {
        Text("On your phone, open plex.tv/link and enter this code.", color = Muted, fontSize = 24.sp)
        Text(
            code,
            color = Ink,
            fontSize = 72.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 8.sp,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun ZoneButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .padding(6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (label.isBlank()) Color.Transparent else Zone)
            .semantics {
                if (label.isNotBlank()) { role = Role.Button; onClick { onClick(); true } }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (label.isNotBlank()) {
            Text(label, color = Ink, fontSize = 20.sp, maxLines = 1)
        }
    }
}

private fun titleFor(browse: Browse): String {
    return when (browse) {
        is Browse.Home -> "Recently watched"
        Browse.SignedOut -> "Plex"
        is Browse.Linking -> "Link Plex"
        is Browse.Servers -> "Servers"
        is Browse.Sections -> browse.server.name.ifBlank { "Libraries" }
        is Browse.Items -> browse.title
    }
}
