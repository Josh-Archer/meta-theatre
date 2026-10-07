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
open your hand before using them again. Controller sticks still browse and seek.

The implementation uses Spatial SDK 0.14's `HandMicrogestureLocomotionSystem`.
See [hand locomotion validation](art/validation/HAND_LOCOMOTION.md) for verification
and the remaining checks on a Quest.

## Plex

On the library panel, choose Link Plex. The headset shows a code. On your phone, open https://plex.tv/link and enter it. Pick a server if you have more than one, then a library, a show, a season, and an episode.

The search row searches the current shelf. Point at **Search titles** to open the
on-panel keyboard, enter a title, then choose **Done**. Movie, TV, and Continue
filters narrow the results; **Clear** restores the full shelf. Large libraries
load all pages instead of stopping at 1,000 titles.

The headset plays the file directly. If the container or codec is rejected, it asks your Plex server to transcode to H.264 and tries again. The headset has to reach the server, on your LAN or through Plex remote access.

The movie panel supports UHD 4K (3840 × 2160). When Plex has multiple copies of a
movie, the app prefers the highest-resolution copy up to UHD, then the higher
bitrate. Original-file playback keeps the source video quality. If it fails, the
app requests a Plex HLS stream up to 4K at 80 Mbps; if that also fails, it tries
a 1080p compatibility transcode at 20 Mbps. The player bar shows the actual decoded
resolution and labels the 1080p fallback. Lower-resolution sources remain lower
resolution. See [UHD playback validation](art/validation/UHD_PLAYBACK.md) for checks
and the remaining headset validation.

The player rail has **Audio** and **Subtitles** buttons that cycle available
tracks; subtitles include an **Off** choice. Original playback uses supported
tracks exposed by the file decoder. Plex streams use the server's stream IDs;
selected subtitles are burned into the stream by Plex. Decoder subtitles use a
transparent overlay that follows the movie screen when it moves or resizes.

**Quiet cinema** is an optional player-rail setting, initially off. While a movie
is open, it dims the cabin lighting and hides and pauses flybys. Closing the
movie restores normal lighting and resumes the flyby clock. The setting is
saved on the headset.

The account token stays in the app's private storage on the headset. Sign out clears it.

## Automated checks

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew testDebugUnitTest assembleDebug lintDebug
```

The local integration tests use Robolectric, Compose, and mock Plex HTTP servers
to cover pagination, canceled navigation, search, playback intent, track
selection, subtitle rendering, and quiet-mode settings. These checks do not
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
