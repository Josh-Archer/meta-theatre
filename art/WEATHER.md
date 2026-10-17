# Orbital weather

Enable **Explore Earth → Weather settings → Live weather: On**. Weather follows
the geographic point beneath the current observer, including manual globe spin,
pole views and the far side. It is off by default; the Boolean preference stays
on the headset. Off restores the authored offline map immediately.

Open-Meteo's current model endpoint supplies weather codes, cloud coverage,
temperature and wind direction for a 7×7 grid spanning the visible hemisphere.
A view change of at least 8 degrees settles for one second before a request;
requests are spaced by at least five seconds. A focused session refreshes every
15 minutes. Losing focus cancels polling. At most four regions are cached in
memory. No weather responses, viewed coordinates or API URLs are logged or saved
to disk. There is no GPS permission, API key, account token or geocoding lookup.

The readings drive modeled cloud density; this is not satellite imagery or
pixel-accurate cloud placement. Fog, rain, snow and storms select corresponding
cloud shapes; no claim is made about exact lightning strikes or ground snow.
The panel includes model time, offline status and data attribution. An incomplete,
invalid, future-dated or more than 30-minute-old response is rejected. A failed
update retains the last successful map with an offline label; a different region
starts from the original map rather than inheriting the previous region's data.

Provider documentation: <https://open-meteo.com/en/docs>.
Data attribution/license: [Open-Meteo](https://open-meteo.com/),
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
The attribution also ships in `weather_credits.txt` and the weather panel.

## Blender and Unreal sources

- `blender/weather_patterns.blend`: seven editable metaball density patterns,
  seven independent neutral mesh exports, preview camera and lighting. Scene-only
  serialization excludes workstation UI and file-browser history.
- `textures/weather/cloud_patterns.png`: 4096×2048 RGBA density atlas; four columns,
  two rows of 1024×1024 tiles. Tile order is clear, cumulus, overcast, fog,
  rain front, snow clouds, thunderhead and cirrus. The clear tile is empty.
- `scripts/build_weather_patterns.py`: deterministic live-Blender authoring and
  export, retaining editable density volumes before mesh conversion.
- `exports/weather/`: seven GLBs, an FBX staging library, pattern manifest and
  Unreal authoring summary. Export metadata is sanitized without changing numeric
  mesh properties by `scripts/sanitize_weather_metadata.py`.
- `scripts/author_weather_unreal.py`: run in the existing asset sandbox with
  `WEATHER_ROOT` set locally. Imports seven meshes and the atlas into
  `/Game/MetaTheatre/Weather`, then creates a translucent master material and
  eight tile-specific instances. It refuses to replace an existing folder.
  The open level is preserved. Unreal packages stay in the asset sandbox;
  Android consumes the shared atlas through its native renderer.
- `renders/weather/cloud_library.png`: Blender authoring preview, not a headset
  capture. Solid model surfaces show pattern structure; the runtime uses soft
  density from the atlas.

The app generates a cloud map at the original offline map's full resolution on a
background dispatcher. Spherical blending handles poles and the texture seam;
unsampled geography retains the offline map. Texture uploads and scene mutation
stay on the main thread. Replaced GPU textures are destroyed. Surface, city lights,
normal and ocean textures, atmosphere, geometry and shared day/night lighting are
unchanged. Earth remains three draws; weather adds no cloud mesh draws.

Run `scripts/validate_weather_assets.cjs` with `gltf-validator` available on the
Node module path to check the exported GLBs and atlas packaging.

See [verification results](validation/ORBITAL_WEATHER.md) for automated checks,
headset evidence and the remaining visual checks.
