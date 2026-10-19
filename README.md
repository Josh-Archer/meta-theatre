# Theatre

A Meta Quest 3 app. You sit in a small ship with Earth outside the right-hand window and a cinema screen on the forward bulkhead. The panel to your left is Plex.

The first location is Earth orbit. Another place (a planet surface, another ship) is a new scene around the same screen and the same player.

## Build

JDK 17 and Android SDK 34 are required. Install Git LFS before cloning, then run
`git lfs pull` to fetch the runtime assets, editable Blender sources, and textures.
Set `sdk.dir` in your local, ignored `local.properties` to your Android SDK path.

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
./gradlew assembleDebug
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`.

## Install on the Quest 3

Turn on developer mode for the headset in the Meta Horizon phone app (headset settings, developer mode). Connect a USB-C cable, put the headset on, and allow USB debugging.

```bash
~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Launch **Theatre** from the app library. Unknown sources has to be allowed for a sideloaded build.

## Move with your hands

Either hand can use Meta's standard thumb microgestures. Hold your hand sideways
with your fingers curled and your thumb beside your index finger:

- Tap your thumb down once to enter movement mode. Six gold pads appear around
  the chair, including a pad beneath it to return to the original viewing spot.
- Aim the teleport arc at a pad and tap your thumb again to move there.
- Swipe your thumb left or right to snap-turn by 45 degrees.
- Swipe your thumb forward or back to take a 0.5 metre step in the direction
  you are looking, or backwards.
- Open your hand to exit movement mode and hide the pads. Tapping with the
  other hand switches the active movement hand.

This moves you around the Earth-orbit scene. Other environments are not yet
available. Teleports require a pad; steps do not have floor or wall collision
in this open-space scene. Hand movie/panel gestures pause during movement mode;
open your hand before using them again. Controller sticks browse and seek while
pointing at a panel; point away from it and flick the stick left or right to turn
your perspective by 45 degrees. Open the library to use the **Turn left** and
**Turn right** buttons along its bottom edge. These turn your view from the chair.

The implementation uses Spatial SDK 0.14's `HandMicrogestureLocomotionSystem`.
See [hand locomotion validation](art/validation/HAND_LOCOMOTION.md) for verification
and the remaining checks on a Quest.

## Explore Earth

Choose the large **Explore Earth** button on the movie's side menu or along the
library's bottom edge. Choose **North Pole**,
**South Pole**, **Equator**, or **Far side** to move around the globe. Earth stays
ahead of you and your chair and screen stay with you. The orbit buttons move
20 degrees around or 15 degrees toward a pole per press. A controller stick aimed
at this menu also moves around the orbit. **Home seat** restores the original
view and globe rotation; **Done** returns to the movie or library you came from.

Point at exposed Earth outside the screen, hold an index/thumb pinch or controller
trigger, and drag sideways to spin it. Release to keep its rotation. The menu's
**Spin left** and **Spin right** buttons turn it 15 degrees per press. Surface,
clouds, and atmosphere rotate together, with the existing shared Sun illuminating
the geography. In real-time mode, UTC and seasonal solar declination determine
day/night over the actual geography, including pole and far-side views. Distant planets keep their clear exhibit slots
as you explore Earth. See [Earth exploration validation](art/validation/EARTH_EXPLORATION.md)
for automated checks and the remaining headset checks.

Choose **Explore Earth → Viewing presets** to save up to three setups. Each slot
remembers screen position and size, virtual chair direction, Earth latitude and
longitude, globe rotation, and Earth zoom. **Restore** places the saved arrangement around
your current seated position, without restoring an old physical location. The
presets stay on the headset across app restarts. **Reset view** returns to the
home Earth view and default screen size. Restores use a snap change rather than
continuous camera travel.

Open **Explore Earth → Location, zoom & rotation** for the new controls:

- **Use my location** is off initially. Enable it and accept the headset's
  approximate-location permission prompt. It uses Quest's Wi-Fi location service
  to place your orbital view over that latitude/longitude, accounting for manual
  and real-time globe rotation. **Above me** obtains another fix and recenters.
  The choice is remembered for the next launch; location is read once per session
  or explicit request, while the app is in the foreground. Turning it off or
  navigating manually cancels pending fixes so a late callback cannot move you.
- **Closer to Earth**, **Farther from Earth**, and **Reset zoom** change Earth's
  distance while preserving the screen, chair/view origin, and world scale.
  The range is 0.75× to approximately 1.28× at the home seat, with room for the
  nearby Roadster's entire flyby. Planet exhibits and the distant ISS remain
  clear of the expanded silhouette. Viewing presets remember zoom too.
- **Real-time rotation** starts on. Earth turns eastward once in approximately
  23h 56m 4s, using elapsed civil time rather than frame counts. It catches up
  after headset sleep. Your view slowly drifts over the surface; **Above me**
  recenters on your location. Turning it off freezes globe rotation and restores
  the earlier artistic local-clock lighting cycle.

The app requests only foreground approximate location access, with no GPS,
background subscription, IP lookup, or coordinate logging. Coordinates remain in
memory unless you explicitly save a viewing preset; presets stay on the headset.
When live weather is also enabled, its existing viewed-region request can include
your current region. Location failures show permission, Wi-Fi/settings, stale-fix,
or timeout guidance instead of moving to a guessed location.

See [Meta's location API](https://developers.meta.com/vr/documentation/native/android/move-location/),
[NASA's sidereal/solar day explanation](https://science.nasa.gov/learn/basics-of-space-flight/chapter2-1/),
and [NOAA's solar equations](https://gml.noaa.gov/grad/solcalc/solareqns.PDF).
Implementation and headset evidence are in the
[location/zoom/rotation checks](art/validation/EARTH_LOCATION_ZOOM_ROTATION.md).

Open **Explore Earth → Weather settings → Live weather: On** to apply current
weather to the hemisphere you are viewing. Earth uses 8K surface/city-light maps
and 4K cloud detail, with cloud relief and shadows. The live cloud field is
continuous rather than made from repeating cloud tiles. See the
[quality checks](art/validation/EARTH_QUALITY.md). It starts off and saves your choice
on the headset. Orbiting or spinning Earth selects a new region after the view
settles; weather refreshes every 15 minutes while the app has focus. **Refresh**
requests an update; **Off** restores the original cloud map.

Clouds carry modeled height and storm/low-cloud profiles. Wind direction
influences frontal detail; cloud shading includes a second self-shadow sample,
soft ground shadows, and forward scattering. Atmospheric haze follows a thin
shell's optical path near the horizon. City lights fade through cloud cover,
and the night-side terrain retains a small ambient contribution. The 8K land
and city textures, 4K cloud textures, and all three runtime meshes are preserved.

Cloud coverage and weather codes come from Open-Meteo. The cloud shapes are
modeled representations of those conditions, not satellite imagery. The weather
panel shows the model time and labels offline data. Only viewed coordinates are
sent to the provider; the app does not request GPS permission or store weather
locations on disk. See [weather authoring and validation](art/WEATHER.md).

## Selectable environments

Open the library and choose **Scenes** along its bottom edge to switch environments:

- **Earth orbit**: Sit floating in open space in the lounge chair with the Earth panorama below and the cinema screen in front.
- **Spaceship cabin**: Sit inside a spaceship cockpit with Earth visible through the starboard window and the cinema screen mounted on the forward bulkhead.

Each environment remembers its own screen size, screen position, and seated view origin. Choosing **Reset to default** restores the initial preset for the current environment. Media playback and selected audio/subtitle tracks remain active and uninterrupted when switching scenes. See [environments validation](art/validation/ENVIRONMENTS.md) for automated checks and Quest hardware verification.

## Plex

On the library panel, choose Link Plex. The headset shows a code. On your phone, open https://plex.tv/link and enter it. Pick a server if you have more than one, then a library, a show, a season, and an episode.

The selector and movie side menu have solid backings so the environment cannot
show through. A white ring with a black outline and gold center marks the hand or
controller ray on their controls. The grab frame remains available during movies.
See [panel visibility checks](art/validation/PANEL_VISIBILITY.md).

The search row searches the current shelf. Point at **Search titles** to open the
on-panel keyboard, enter a title, then choose **Done**. Movie, TV, and Continue
filters narrow the results; **Clear** restores the full shelf. Large libraries
load all pages instead of stopping at 1,000 titles.

The headset plays the file directly. If the container or codec is rejected, it asks your Plex server to transcode to H.264 and tries again. The headset has to reach the server, on your LAN or through Plex remote access.

The movie panel supports UHD 4K (3840 × 2160). When Plex has multiple copies of a
movie, the app prefers the highest-resolution copy up to UHD, then the higher
bitrate. Original-file playback keeps the source video quality. If its container
or codec is incompatible, the
app requests a Plex HLS stream up to 4K at 80 Mbps; if that also fails, it tries
a 1080p compatibility transcode at 20 Mbps. The player bar shows the actual decoded
resolution and labels the 1080p fallback. Lower-resolution sources remain lower
resolution. See [UHD playback validation](art/validation/UHD_PLAYBACK.md) for checks
and the remaining headset validation.

Connection failures retry the current stream up to three times, with increasing
delays, without changing quality. The player retains its last known position and
whether you paused it, including pauses made while reconnecting. **Reconnect**
on the side menu refreshes server connections and access before reopening the
same movie at that bookmark. Opening another movie or closing playback cancels
pending retries. Library connection failures offer **Reconnect**; a rejected
account login offers **Relink Plex**. Neither failure clears the stored sign-in
or watch history automatically.

Movies use an opaque compositor layer with a direct decoder surface. The side
menu's **Input** line reports the selected video's color metadata: Dolby Vision,
HDR10/PQ, HLG, SDR, or unspecified. **Original** keeps the source stream and its
HDR metadata; a Plex transcode can change the color format even at 4K. Decoding
uses the headset's native codecs, with decoder fallback enabled. See
[viewpoint and video validation](art/validation/VIEWPOINT_VIDEO_REPAIR.md) for the
local Dolby Vision/HDR10 decoder checks and their limits.

The separate **Display: SDR · Rec.709** line identifies the app's configured
compositor color space. An Input label of Dolby Vision describes the source;
it does not establish Dolby Vision display output.

The player rail has **Audio** and **Subtitles** buttons that cycle available
tracks; subtitles include an **Off** choice. Original playback uses supported
tracks exposed by the file decoder. Plex streams use the server's stream IDs;
selected subtitles are burned into the stream by Plex. Decoder subtitles use a
transparent overlay that follows the movie screen when it moves or resizes.

**Quiet cinema** is an optional player-rail setting, initially off. While a movie
is open, it dims the cabin lighting and hides and pauses flybys. Closing the
movie restores normal lighting and resumes the flyby clock. The setting is
saved on the headset.

Hold two open palms facing each other in front of the screen, about 40–50 cm
apart. Move both inward to about 20 cm apart to fold the screen closed. You do
not need to make contact or aim both hand rays at it. Start with palms close
together and spread both apart to unfold it, or press a controller trigger.
The screen keeps its size and reappears in front of your current view, including
after turning while it was hidden. A movie pauses while hidden and resumes its previous
playback intent when restored; an already paused movie stays paused. The gesture
also hides the library. Hidden screens do not accept movie or library presses.

Hold one open hand up in front of you, palm facing away from you, for half a
second to pause active media. Lower your hand before repeating. This only pauses;
use **Play** or a screen tap to resume.

Raise both open hands, palms facing away from you, and push forward to move the
screen farther away. Turn both palms toward your face and pull back to bring it
closer. Both hands must move together; no pinch or ray target is required. Pulling
also brings a hidden screen back into your current view. The screen keeps its size
and stops between 1.5 and 6 metres away. The native grab frame and resize corners
remain available during playback: drag an edge to move or a corner to resize.

To resize either the library or movie screen, aim both hands at its face, pinch
with both index fingers and thumbs, then hold both pinches while moving your
hands apart to enlarge it or together to shrink it. The first pinch can wait
for the second. Release to keep the size. A quick single pinch selects on
release; resizing consumes both pinches so it does not select a library item.

The ISS and Roadster make frequent passes across the forward view, above the
default movie screen. Their first passes start 3 and 12 seconds after scene
creation; they repeat every 120 and 150 seconds. Quiet cinema pauses them while
the movie is visible. Hiding the screen restores ambient lighting and flybys.
The modern Roadster has an open roof and visible Starman occupant. Its source, renders and
Blender/Unreal validation are documented in [art/ROADSTER.md](art/ROADSTER.md).

Point at either visitor until the gold pointer appears, hold an index/thumb
pinch or controller trigger, then pull your hand back at least 6 cm. The model
moves closer as a miniature for inspection. Keep holding to move it and rotate
your wrist to turn it. Release slowly to leave it there. To send it back, give
your held hand/controller a quick outward push and release the pinch/trigger
while moving. The model flies back into its normal pass over 1.2 seconds. A
roughly 10–20 cm outward motion is enough; no hard throw is needed. The library's
**Return models** button sends both back with the same animation. Returning
after a pass ends starts another visible pass immediately. Models behind a visible screen do not
capture its presses. Quiet cinema hides inspected models along with flybys and
shows them again when the movie is closed or hidden. See
[visitor return validation](art/validation/VISITOR_RETURN.md) for throw checks.

The account token stays in the app's private storage on the headset. Sign out
requires confirmation and clears it. App updates retain the token; if the token
is already missing, use Link Plex again.

## Automated checks

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew testDebugUnitTest assembleDebug lintDebug
```

The local integration tests use Robolectric, Compose, and mock Plex HTTP servers
to cover pagination, canceled navigation, search, playback intent, track
selection, subtitle rendering, quiet-mode settings, scene-control hit regions,
visitor capture/pull/rotation/release, and native-palm screen folding after a
turn or recenter. Earth integration tests cover polar observer frames, spin
gestures, panel occlusion, and shared lighting. Stop-palm and two-hand depth checks
cover buffering, tracking loss, turned views, and hidden-screen restoration.
Native font rendering checks the larger side-menu labels and their hit targets.
These checks do not
replace playback and hand/controller testing on an awake Quest.

## 3D assets

Machine settings, credentials, raw validation logs, device captures, generated
builds, and local tool environments are excluded from Git. Validation Markdown
and the junior-task result summary are included. The project junior agent is
defined in `.codex/agents/junior.toml` with Luna 6 and high reasoning effort.

The current Quest app loads glTF binary (`.glb`) assets from `app/src/main/assets/`.
Keep editable Blender source files under `art/blender/`; export runtime `.glb`
files into the app assets directory. See [ASSET_WORKFLOW.md](ASSET_WORKFLOW.md)
for the Blender and Codex MCP setup, plus the separate Unreal path.

## Credits

The Earth texture is NASA's Blue Marble (`Land_ocean_ice_2048.jpg`), public domain. The starfield is generated in this project.
