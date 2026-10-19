# Current-location view, orbital zoom, and real-time rotation

Validated 2026-10-18.

## Controls and behavior

Explore Earth → Location, zoom & rotation provides:

- Use my location (initially off) and Above me. The app requests only Android's
  foreground `ACCESS_COARSE_LOCATION` permission. Quest's network/Wi-Fi provider
  supplies one approximate fix per app session or explicit request. The location
  target compensates for the asset's 100W meridian, manual spin, and real-time
  spin, so the requested geographic region is below the observer. The remembered
  setting can center the next app session after permission has been granted.
- Closer to Earth, Farther from Earth, Reset zoom. Earth's distance changes;
  the camera/view origin, screen pose/size, and mesh scale do not. At the home
  seat the distance multiplier ranges from 0.75 to about 1.284. The nearest view
  reserves the Roadster's entire flyby sphere plus a margin. Planet exhibits and
  the distant ISS move outside Earth's expanded silhouette without reducing
  their scales. Additional seat displacement can lower the zoom cap.
- Real-time rotation (initially on). An eastward turn takes 86,164.0905 seconds.
  Elapsed `Instant` time, rather than accumulated frame deltas, preserves the
  rate across headset sleep and variable frame rates. The observer stays fixed,
  so the viewed region slowly drifts; Above me recenters it. Disabling rotation
  freezes the globe and returns to the earlier artistic local-time Sun cycle.

In real-time mode, NOAA's approximate solar declination and equation of time
determine the subsolar point from UTC/date, independently of the headset timezone.
Earth, the visible Sun, planets, Moon, and scene/chair lighting share that same
world-space Sun. This remains an artistic planet exhibit, not a live solar-system
ephemeris. Viewing presets now include zoom; older preset JSON defaults to 1×.

## Location handling

There is no app GPS, IP-geolocation request, background subscription, coordinate
logging, or automatic location disk cache. The provider fix must have finite
in-range coordinates, usable accuracy, and an elapsed timestamp within thirty
seconds (allowing ten seconds of future clock tolerance). As requested, there is
no last-known fallback. A request waits for fresh foreground network updates for
up to 45 seconds. Disabled location, missing permissions, old fixes and provider
failures produce guidance without moving to a guessed location. Disabling the
setting, backgrounding the app, or changing the orbital region/spin cancels the
lookup. Zooming preserves it. Late callbacks cannot move the view.

Only the on/off preference is saved automatically. Explicitly saved viewing
presets can retain a geographic view in private app storage. When live weather
is also enabled, its existing viewed-region request can include the user's
region; the settings menu explains this. Debug video/weather/navigation fixtures
skip both Plex account restoration and real device-location lookup.

## Initial control-release evidence

- `testDebugUnitTest assembleDebug lintDebug` passed: 195 tests, zero test
  failures, zero lint errors, 43 lint warnings. Twelve tests were added across
  location permission/provider/cancellation behavior, rendered menu ray targets,
  geographic targeting, polar precision, zoom/visitor clearance, UTC lighting,
  sidereal timing, and preset compatibility.
- The rendered Compose settings panel was visually inspected at 1600×900.
  The test uses synthetic state and performs no location/network lookup.
- All 37 runtime assets byte-match the APK. No Blender source, geometry, texture
  resolution, or video quality was reduced or changed for these controls.
- APK SHA-256:
  `ee9fe959751f4e64830c4c76ca2771aa37ad6750eb3585733c92f1ba168ef098`
- A preserving headset update installed successfully. Plex and theatre-settings
  file hashes matched before/after installation and after diagnostics.
- The native geographic/zoom diagnostic passed with a synthetic region: target
  geography matched, zoom stayed outside the atmosphere, and screen pose/size and
  view origin/yaw remained unchanged. The Earth renderer initialized and the local
  3840×2160 video fixture decoded and rendered its first frame. No fatal exception
  was observed in this run.

The actual system permission prompt, Wi-Fi location fix/accuracy, perceived zoom
comfort and stereo appearance still need an in-headset check. No real geographic
location was obtained or published for validation. The normal app is relaunched
after diagnostics. Raw logs, preference hashes, screenshots and APKs stay local
and ignored by Git.

The follow-up in [EARTH_CLOSE_CLOUDS_LOCATION.md](EARTH_CLOSE_CLOUDS_LOCATION.md)
adds native pointer clicks for Above me, a real provider/centering diagnostic,
visible waiting/cancellation states, and native 8K cloud optical depth.

## Primary references

- [Meta Location API](https://developers.meta.com/vr/documentation/native/android/move-location/):
  foreground approximate access, Wi-Fi positioning, supported manager APIs and
  possible cached fixes.
- [Android LocationManager](https://developer.android.com/reference/android/location/LocationManager):
  cancellable one-shot location retrieval.
- [NASA reference systems](https://science.nasa.gov/learn/basics-of-space-flight/chapter2-1/):
  sidereal versus mean solar day.
- [NOAA solar equations](https://gml.noaa.gov/grad/solcalc/solareqns.PDF):
  fractional year, solar declination, equation of time and UTC solar longitude.
