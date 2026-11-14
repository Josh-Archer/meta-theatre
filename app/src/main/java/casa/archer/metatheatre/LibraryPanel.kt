package casa.archer.metatheatre

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
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
import com.meta.spatial.uiset.theme.SpatialTheme
import com.meta.spatial.uiset.theme.LocalColorScheme
import com.meta.spatial.uiset.theme.darkSpatialColorScheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Ink = Color(0xFFF4F1EA)
private val Muted = Color(0xFFB7C0CC)
private val Panel = Color(0xFF12161E)
private val Card = Color(0xFF1C2430)
private val Accent = Color(0xFFD7B56D)
private val Zone = Color(0xFF2A3342)

@Composable
private fun Modifier.horizonPanel(): Modifier = clip(SpatialTheme.shapes.large)
    .background(LocalColorScheme.current.panel)
    .border(1.dp, Color.White.copy(alpha = .22f), SpatialTheme.shapes.large)

@Composable
fun LibraryPanel(model: TheatreModel, player: ScreenPlayer) {
    val libraryOpen by model.libraryOpen.collectAsState()
    val hidden by model.screenHidden.collectAsState()
    SpatialTheme(colorScheme = darkSpatialColorScheme()) {
      Box(Modifier.fillMaxSize()) {
        LibraryContent(model, player)
        PanelReticle(model, PointerPanel.LIBRARY, libraryOpen && !hidden)
      }
    }
}

@Composable
private fun LibraryContent(model: TheatreModel, player: ScreenPlayer) {
    val browse by model.browse.collectAsState()
    val loading by model.loading.collectAsState()
    val error by model.error.collectAsState()
    val canGoBack by model.canGoBack.collectAsState()
    val page by model.page.collectAsState()
    val query by model.librarySearchQuery.collectAsState()
    val filter by model.librarySearchFilter.collectAsState()
    val keyboardOpen by model.librarySearchKeyboardOpen.collectAsState()
    val confirmSignOut by model.signOutConfirmation.collectAsState()
    val earthOpen by model.earthControlsOpen.collectAsState()
    val earthView by model.earthViewLabel.collectAsState()
    val scenePickerOpen by model.scenePickerOpen.collectAsState()
    val weatherOpen by model.weatherSettingsOpen.collectAsState()
    val earthSettings by model.earthSettingsOpen.collectAsState()
    val presetsOpen by model.viewingPresetsOpen.collectAsState()
    val libraryOpen by model.libraryOpen.collectAsState()
    val movieReady by remember(player) {
        player.transport.map { it.title.isNotBlank() }.distinctUntilChanged()
    }.collectAsState(initial = false)
    val tiles = remember(browse) { shelfTiles(browse).orEmpty() }
    val window = PosterGrid.window(tiles.size, page)

    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, onPrimary = Color(0xFF1A1408))) {
        if (!libraryOpen && movieReady) {
            Box(Modifier.fillMaxSize().border(1.dp, Color.White.copy(alpha = .28f), SpatialTheme.shapes.large).semantics {
                contentDescription = "Screen frame: drag edges to move, corners to resize"
            })
            return@MaterialTheme
        }
        if (confirmSignOut) {
            SignOutPrompt(model)
            return@MaterialTheme
        }
        if (browse == Browse.JellyfinSignIn) {
            JellyfinSignInScreen(model)
            return@MaterialTheme
        }
        if (browse is Browse.JellyfinLinking) {
            JellyfinLinkingScreen((browse as Browse.JellyfinLinking).code, model)
            return@MaterialTheme
        }
        if (earthOpen && weatherOpen) {
            WeatherMenu(model)
            return@MaterialTheme
        }
        if (earthOpen && presetsOpen) {
            ViewingPresetsMenu(model)
            return@MaterialTheme
        }
        if (earthOpen && earthSettings) {
            EarthSettingsMenu(model)
            return@MaterialTheme
        }
        if (earthOpen) {
            EarthMenu(model, earthView)
            return@MaterialTheme
        }
        if (scenePickerOpen) {
            ScenePickerMenu(model)
            return@MaterialTheme
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .horizonPanel(),
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
            ShelfFooter(tiles.size, window, model)
        }
    }
}

@Composable
private fun SignOutPrompt(model: TheatreModel) {
    val browse by model.browse.collectAsState()
    val source = if (browse is Browse.JellyfinLibraries || browse is Browse.JellyfinItems) "Jellyfin" else "Plex"
    Box(Modifier.fillMaxSize().horizonPanel()) {
        Column(Modifier.fillMaxWidth().offset(y = 300.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Sign out of $source?", color = Ink, fontSize = 36.sp)
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
    is Browse.Home, is Browse.Servers, is Browse.Sections, is Browse.Items,
    is Browse.JellyfinLibraries, is Browse.JellyfinItems -> true
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
                LibrarySearchFilter.MOVIES, LibrarySearchFilter.TV -> browse is Browse.Home || browse is Browse.Sections || browse is Browse.Items ||
                    browse is Browse.JellyfinLibraries || browse is Browse.JellyfinItems
                LibrarySearchFilter.CONTINUE -> browse is Browse.Home || browse is Browse.Items || browse is Browse.JellyfinItems
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
    val reconnect by model.plexReconnectAvailable.collectAsState()
    val relink by model.plexLinkRequired.collectAsState()
    val action = when (browse) {
        Browse.SignedOut -> ""
        is Browse.Linking -> "Cancel"
        else -> if (relink) "Relink Plex" else "Sign out"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(PosterGrid.HEADER_DP.dp)
            .clipToBounds(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZoneButton(
            label = if (reconnect) "Reconnect" else if (canGoBack) "Back" else "",
            modifier = Modifier.fillMaxHeight().weight(PosterGrid.BACK_X),
        ) { if (reconnect) model.reconnectPlex() else if (canGoBack) model.back() }
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
            label = if (browse is Browse.Home) "Libraries" else if (movieReady) "Player" else "",
            modifier = Modifier.fillMaxHeight().weight(PosterGrid.SIGN_OUT_X - PosterGrid.PLAYER_X),
        ) { if (browse is Browse.Home) model.openLibraries() else model.showPlayer() }
        ZoneButton(
            label = action,
            modifier = Modifier.fillMaxHeight().weight(1f - PosterGrid.SIGN_OUT_X),
        ) { if (relink) model.link() else if (action.isNotBlank()) model.requestSignOut() }
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
private fun ShelfFooter(total: Int, window: ShelfWindow, model: TheatreModel) {
    val maxStart = PosterGrid.maxStart(total)
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
        SceneControl.entries.forEachIndexed { index, control ->
            ZoneButton(control.label, Modifier.fillMaxHeight().weight(SceneControls.widths[index]),
                acceptPointerClick = control == SceneControl.SCENES, highlighted = control == SceneControl.EARTH) {
                model.sceneControlPress(SceneControls.center(control), .96f)
            }
        }
        ZoneButton(
            label = if (window.start < maxStart) "Next" else "",
            modifier = Modifier.fillMaxHeight().weight(1f - PosterGrid.NEXT_X),
        ) {}
    }
}

@Composable
private fun EarthMenu(model: TheatreModel, view: String) {
    Column(Modifier.fillMaxSize().horizonPanel()) {
        Row(Modifier.fillMaxWidth().height((PosterGrid.PANEL_DP * EarthControls.HEADER).dp)) {
            Column(Modifier.fillMaxHeight().weight(.72f), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Text("Explore Earth", color = Ink, fontSize = 36.sp)
                Text(view, color = Accent, fontSize = 24.sp)
            }
            ZoneButton("Weather settings", Modifier.fillMaxHeight().weight(.28f)) {
                model.weatherSettingsOpen.value = true
            }
        }
        EarthControls.rows.forEach { controls ->
            Row(Modifier.fillMaxWidth().height((PosterGrid.PANEL_DP * EarthControls.ROW_HEIGHT).dp)) {
                controls.forEach { control ->
                    ZoneButton(control.label, Modifier.fillMaxHeight().weight(1f)) { model.earthControl(control) }
                }
            }
        }
        ZoneButton("Viewing presets", Modifier.fillMaxWidth().height((PosterGrid.PANEL_DP * .1f).dp), highlighted = true) {
            model.viewingPresetsOpen.value = true
        }
        ZoneButton("Location, zoom & rotation", Modifier.fillMaxSize(), highlighted = true) {
            model.earthSettingsOpen.value = true
        }
    }
}

@Composable
private fun EarthSettingsMenu(model: TheatreModel) {
    val location by model.useCurrentLocation.collectAsState()
    val rotation by model.realEarthRotation.collectAsState()
    val zoom by model.earthZoom.collectAsState()
    val zoomMessage by model.earthZoomMessage.collectAsState()
    val detailMessage by model.earthDetailMessage.collectAsState()
    val message by model.locationMessage.collectAsState()
    Column(Modifier.fillMaxSize().horizonPanel()) {
        Column(Modifier.fillMaxWidth().height((PosterGrid.PANEL_DP * EarthSettingsControls.HEADER).dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("Location, zoom & rotation", color = Ink, fontSize = 36.sp)
            Text("Surface zoom: ${String.format(java.util.Locale.US, "%.1f", zoom)}×", color = Accent, fontSize = 26.sp)
            if (zoomMessage.isNotEmpty()) Text(zoomMessage, color = Muted, fontSize = 24.sp)
        }
        EarthSettingsControls.rows.forEach { row ->
            Row(Modifier.fillMaxWidth().height((PosterGrid.PANEL_DP * EarthSettingsControls.ROW).dp)) {
                row.forEach { control ->
                    val label = when(control) {
                        EarthSetting.LOCATION -> "Use my location: ${if (location) "On" else "Off"}"
                        EarthSetting.ROTATION -> "Real-time rotation: ${if (rotation) "On" else "Off"}"
                        else -> control.label
                    }
                    ZoneButton(label, Modifier.fillMaxHeight().weight(1f),
                        acceptPointerClick = control in listOf(EarthSetting.ABOVE_ME, EarthSetting.LOCATION_SETTINGS, EarthSetting.PRECISE_LOCATION),
                        highlighted = control == EarthSetting.LOCATION && location || control == EarthSetting.ROTATION && rotation) {
                        model.earthSetting(control)
                    }
                }
            }
        }
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(detailMessage, color = Ink, fontSize = 26.sp)
            Text("Satellite texture: EOxCloudless by EOX IT Services GmbH (modified Copernicus Sentinel data 2016 & 2017) · cloudless.eox.at · CC BY 4.0.", color = Muted, fontSize = 24.sp)
            Text(message, color = Ink, fontSize = 26.sp)
            Text("Precise Wi-Fi fix can help when approximate location is throttled. Fresh fixes only.", color = Muted, fontSize = 24.sp)
            Text("Real-time: one turn in about 23h 56m. Your view slowly drifts over the surface; Above me recenters it.", color = Muted, fontSize = 24.sp)
            Text("Mountain shading: Mapzen terrain (AWS Open Data). Viewed regions go to EOX and AWS and are cached locally. Live weather uses Open-Meteo.", color = Muted, fontSize = 24.sp)
        }
    }
}

@Composable
private fun ViewingPresetsMenu(model: TheatreModel) {
    val presets by model.viewingPresets.collectAsState()
    val message by model.presetMessage.collectAsState()
    Column(Modifier.fillMaxSize().horizonPanel()) {
        Column(Modifier.fillMaxWidth().height((PosterGrid.PANEL_DP * PresetControls.HEADER).dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("Viewing presets", color = Ink, fontSize = 36.sp)
            Text(message, color = Accent, fontSize = 24.sp)
        }
        presets.forEachIndexed { slot, preset ->
            Row(Modifier.fillMaxWidth().height((PosterGrid.PANEL_DP * PresetControls.ROW).dp)) {
                Box(Modifier.fillMaxHeight().weight(1f), contentAlignment = Alignment.Center) {
                    Text("View ${slot + 1}\n${if (preset == null) "Empty" else "Saved"}",
                        color = Ink, fontSize = 28.sp, textAlign = TextAlign.Center)
                }
                ZoneButton(if (preset == null) "" else "Restore ${slot + 1}", Modifier.fillMaxHeight().weight(1f)) {
                    model.presetControl(PresetAction.RESTORE, slot)
                }
                ZoneButton("Save ${slot + 1}", Modifier.fillMaxHeight().weight(1f), highlighted = true) {
                    model.presetControl(PresetAction.SAVE, slot)
                }
                ZoneButton(if (preset == null) "" else "Clear ${slot + 1}", Modifier.fillMaxHeight().weight(1f)) {
                    model.presetControl(PresetAction.CLEAR, slot)
                }
            }
        }
        Row(Modifier.fillMaxSize()) {
            ZoneButton("Reset view", Modifier.fillMaxHeight().weight(1f)) { model.presetControl(PresetAction.RESET) }
            ZoneButton("Done", Modifier.fillMaxHeight().weight(1f)) { model.presetControl(PresetAction.DONE) }
        }
    }
}

@Composable
private fun ScenePickerMenu(model: TheatreModel) {
    val flightStatus by model.starshipStatus.collectAsState()
    val currentLocation by model.currentLocation.collectAsState()
    Column(Modifier.fillMaxSize().background(Panel).border(4.dp, Accent)) {
        Column(
            Modifier.fillMaxWidth().height((PosterGrid.PANEL_DP * ScenePickerControls.HEADER).dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Select Environment", color = Ink, fontSize = 36.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Current: ${currentLocation.title}", color = Accent, fontSize = 24.sp)
        }
        Row(
            Modifier.fillMaxWidth().height((PosterGrid.PANEL_DP * ScenePickerControls.ROW_HEIGHT).dp)
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TheatreLocation.entries.forEach { location ->
                val isSelected = location == currentLocation
                Box(
                    modifier = Modifier.fillMaxHeight().weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isSelected) Accent else Zone)
                        .border(if (isSelected) 3.dp else 1.dp, if (isSelected) Ink else Color.Transparent, RoundedCornerShape(16.dp))
                        .clickable(role = Role.Button) { model.selectLocation(location) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        location.title,
                        color = if (isSelected) Color(0xFF1A1408) else Ink,
                        fontSize = 24.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }
        Spacer(Modifier.height((PosterGrid.PANEL_DP * .04f).dp))
        Row(
            Modifier.fillMaxWidth().height((PosterGrid.PANEL_DP * ScenePickerControls.ROW_HEIGHT).dp)
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ZoneButton("Reset to default", Modifier.fillMaxHeight().weight(1f)) {
                model.resetCurrentLocationToDefault()
            }
            ZoneButton("Back to library", Modifier.fillMaxHeight().weight(1f), acceptPointerClick = true) {
                model.closeScenePicker()
            }
        }
        Spacer(Modifier.height((PosterGrid.PANEL_DP * .02f).dp))
        Row(Modifier.fillMaxWidth().height((PosterGrid.PANEL_DP * ScenePickerControls.ROW_HEIGHT).dp)) {
            ZoneButton("Launch / replay Starship", Modifier.fillMaxHeight().weight(.7f), acceptPointerClick = true) { model.starshipFlight(true) }
            ZoneButton("End flight", Modifier.fillMaxHeight().weight(.3f), acceptPointerClick = true) { model.starshipFlight(false) }
        }
        ZoneButton("Show / replay Roadster", Modifier.fillMaxWidth().height((PosterGrid.PANEL_DP * .12f).dp),
            acceptPointerClick = true) { model.replayRoadster() }
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Text(
                "$flightStatus\nStaged flight · Earth view · pauses during Quiet cinema",
                color = Muted,
                fontSize = 22.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun WeatherMenu(model: TheatreModel) {
    val weather by model.weatherUi.collectAsState()
    val clear by model.clearEarth.collectAsState()
    Column(Modifier.fillMaxSize().horizonPanel()) {
        Column(Modifier.fillMaxWidth().height((PosterGrid.PANEL_DP * WeatherControls.HEADER).dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("Earth weather", color = Ink, fontSize = 36.sp)
            Text("Follows the place on Earth you’re viewing", color = Accent, fontSize = 26.sp)
        }
        Row(Modifier.fillMaxWidth().height((PosterGrid.PANEL_DP * WeatherControls.ROW).dp)) {
            WeatherControl.entries.forEach { control ->
                val label = when (control) {
                    WeatherControl.TOGGLE -> "Live weather: ${if (weather.enabled) "On" else "Off"}"
                    WeatherControl.CLEAR -> "Clear Earth: ${if (clear) "On" else "Off"}"
                    else -> control.label
                }
                ZoneButton(label, Modifier.fillMaxHeight().weight(1f), highlighted = control == WeatherControl.TOGGLE && weather.enabled || control == WeatherControl.CLEAR && clear) {
                    model.weatherControl(control)
                }
            }
        }
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(if (clear) "Clear Earth · clouds and cloud shadows hidden" else weather.message, color = if (weather.stale) Accent else Ink, fontSize = 30.sp)
            weather.place?.let { Text(it.label(), color = Ink, fontSize = 28.sp) }
            weather.reading?.let { reading ->
                Text("${reading.pattern.title} · ${reading.cloudCover}% cloud · ${kotlin.math.round(reading.temperature).toInt()}°C", color = Ink, fontSize = 28.sp)
                val time = java.time.format.DateTimeFormatter.ofPattern("MMM d, HH:mm z", java.util.Locale.US)
                    .withZone(java.time.ZoneId.systemDefault()).format(reading.time)
                Text("Model time: $time", color = Muted, fontSize = 26.sp)
            }
            Text("Modeled cloud patterns seen from orbit; not satellite imagery.\nHigh cloud uses modeled layer fractions only. Rain, snow and storms keep their shapes.", color = Muted, fontSize = 26.sp)
            Text("Open-Meteo (open-meteo.com) · CC BY 4.0\nWhen on, viewed coordinates are sent to Open-Meteo. Headset location is optional in Earth settings.", color = Muted, fontSize = 24.sp)
        }
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
        Button(
            onClick = { model.openJellyfinSignIn() },
            modifier = Modifier.fillMaxWidth().height(96.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Zone),
        ) {
            Text("Use Jellyfin", fontSize = 28.sp, color = Ink)
        }
    }
}

@Composable
private fun JellyfinSignInScreen(model: TheatreModel) {
    val form by model.jellyfinForm.collectAsState()
    val error by model.error.collectAsState()
    val loading by model.loading.collectAsState()
    Box(Modifier.fillMaxSize().background(Panel).border(4.dp, Accent)) {
        Column(Modifier.fillMaxWidth().offset(y = 24.dp).padding(horizontal = 36.dp)) {
            Text("Jellyfin", color = Accent, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text("Connect to your server", color = Ink, fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
            when {
                error != null -> Text(error!!, color = Color(0xFFFFB4A8), fontSize = 20.sp, maxLines = 2)
                loading -> Text("Loading…", color = Muted, fontSize = 20.sp)
            }
        }
        JellyfinField.entries.forEachIndexed { index, field ->
            val top = ((JellyfinSignInLayout.FIELD_TOP + index * JellyfinSignInLayout.FIELD_HEIGHT) *
                PosterGrid.PANEL_DP).dp
            val height = (JellyfinSignInLayout.FIELD_HEIGHT * PosterGrid.PANEL_DP).dp
            val selected = form.field == field
            Column(
                modifier = Modifier.fillMaxWidth().offset(y = top).height(height)
                    .padding(horizontal = 36.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) Card else Zone)
                    .border(if (selected) 3.dp else 0.dp, if (selected) Accent else Color.Transparent, RoundedCornerShape(12.dp))
                    .semantics { role = Role.Button; onClick { model.jellyfinSelectField(field); true } }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    when (field) {
                        JellyfinField.SERVER -> "Server URL"
                        JellyfinField.USERNAME -> "Username"
                        JellyfinField.PASSWORD -> "Password"
                    },
                    color = Muted, fontSize = 16.sp,
                )
                val value = form.value(field)
                Text(
                    when {
                        field == JellyfinField.PASSWORD && value.isNotEmpty() -> "•".repeat(value.length.coerceAtMost(32))
                        value.isEmpty() && field == JellyfinField.SERVER -> "http://192.168.1.10:8096"
                        value.isEmpty() -> ""
                        else -> value
                    },
                    color = if (value.isEmpty()) Muted else Ink,
                    fontSize = 26.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth()
                .offset(y = (JellyfinSignInLayout.ACTION_TOP * PosterGrid.PANEL_DP).dp)
                .height(((JellyfinSignInLayout.ACTION_BOTTOM - JellyfinSignInLayout.ACTION_TOP) * PosterGrid.PANEL_DP).dp),
        ) {
            ZoneButton("Sign in", Modifier.fillMaxHeight().weight(1f)) { model.submitJellyfinSignIn() }
            ZoneButton("Quick Connect", Modifier.fillMaxHeight().weight(1f)) { model.startJellyfinQuickConnect() }
        }
        Column(
            modifier = Modifier.fillMaxWidth()
                .offset(y = (PosterGrid.KEYBOARD_START * PosterGrid.PANEL_DP).dp)
                .height((PosterGrid.KEYBOARD_ROW_HEIGHT * PosterGrid.PANEL_DP * PosterGrid.LIBRARY_KEYBOARD_ROWS.size).dp),
        ) {
            PosterGrid.LIBRARY_KEYBOARD_ROWS.forEach { row -> JellyfinKeyboardRow(row, model) }
        }
    }
}

@Composable
private fun ColumnScope.JellyfinKeyboardRow(row: LibraryKeyboardRow, model: TheatreModel) {
    Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
        SpacerWidth(row.start)
        row.keys.forEach { key ->
            val label = when (key) {
                is LibraryKeyboardKey.Character -> key.value.toString()
                LibraryKeyboardKey.Backspace -> "⌫"
                LibraryKeyboardKey.Space -> "Space"
                LibraryKeyboardKey.Done -> "Done"
            }
            KeyboardKey(label, Modifier.weight(row.width / row.keys.size)) { model.jellyfinKey(key) }
        }
        SpacerWidth(1f - row.start - row.width)
    }
}

@Composable
private fun JellyfinLinkingScreen(code: String, model: TheatreModel) {
    Box(Modifier.fillMaxSize().background(Panel).border(4.dp, Accent)) {
        Column(Modifier.fillMaxSize().padding(36.dp)) {
            Text("In Jellyfin, open Quick Connect and enter this code.", color = Muted, fontSize = 24.sp)
            Text(
                code,
                color = Ink,
                fontSize = 72.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 8.sp,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text("Point anywhere and press to cancel.", color = Muted, fontSize = 20.sp)
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
private fun ZoneButton(label: String, modifier: Modifier, highlighted: Boolean = false,
    acceptPointerClick: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .padding(6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (label.isBlank()) Color.Transparent else Zone)
            // Recenter is idempotent and accepts SDK pointer clicks as well as the
            // custom hand ray. Keep toggles on their existing single input path.
            .then(if (acceptPointerClick) Modifier.clickable(enabled = label.isNotBlank(), role = Role.Button, onClick = onClick)
            else Modifier.semantics {
                if (label.isNotBlank()) { role = Role.Button; onClick { onClick(); true } }
            }),
        contentAlignment = Alignment.Center,
    ) {
        if (label.isNotBlank()) {
            Text(label, color = if (highlighted) Accent else Ink, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
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
        Browse.JellyfinSignIn -> "Jellyfin"
        is Browse.JellyfinLinking -> "Quick Connect"
        is Browse.JellyfinLibraries -> "Jellyfin"
        is Browse.JellyfinItems -> browse.title
    }
}
