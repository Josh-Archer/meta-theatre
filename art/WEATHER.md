# Orbital weather

Enable **Explore Earth → Weather settings → Live weather: On**. Weather follows
the geographic point beneath the current observer, including manual globe spin,
pole views and the far side. It is off by default; the Boolean preference stays
on the headset. Off restores the authored offline map immediately.

Open-Meteo's current model endpoint supplies weather codes, total cloud coverage,
low/mid/high layer fractions, temperature and wind direction for a 7×7 grid
spanning the visible hemisphere.
A view change of at least 8 degrees settles for one second before a request;
requests are spaced by at least five seconds. A focused session refreshes every
15 minutes. Losing focus cancels polling. At most four regions are cached in
memory. No weather responses, viewed coordinates or API URLs are logged or saved
to disk. There is no GPS permission, API key, account token or geocoding lookup.

A continuous 4096×2048 field preserves global cloud morphology and fine optical
depth. Readings are interpolated separately from that detail; there are no repeating
cloud stamps or flat clipped patches under overcast skies.

The readings drive modeled cloud density; this is not satellite imagery or
pixel-accurate cloud placement. Fog, rain, snow and storms select corresponding
cloud shapes; no claim is made about exact lightning strikes or ground snow.
Wispy high cloud is a separate shape and is used only from the layer fractions
described below. A partly cloudy or overcast weather code never selects it by
itself. The weather panel calls the result modeled and says it is not satellite
imagery.

## Cloud-layer fields

Checked against the [Open-Meteo forecast documentation](https://open-meteo.com/en/docs)
and live `current` responses on 2026-11-12. The app keeps calling
`https://api.open-meteo.com/v1/forecast` with no API key and no model override,
so each cell uses Open-Meteo's best-match model. The `current` list is
`weather_code`, `cloud_cover`, `cloud_cover_low`, `cloud_cover_mid`,
`cloud_cover_high`, `temperature_2m`, and `wind_direction_10m`.

| Field | Meaning | Use |
| --- | --- | --- |
| `weather_code` | WMO code | Fog, rain, snow, and thunderstorm shapes. Partly cloudy (`1`, `2`) stays scattered cumulus. Overcast (`3`) stays a billowing deck unless the layer fractions say otherwise. |
| `cloud_cover` | Total cloud cover, 0–100% | How much of the local map is filled. Layer fractions do not raise or lower this. |
| `cloud_cover_low` | Low cloud and fog up to 3 km, 0–100% | Compared with the high layer. A dominant lower deck keeps billowing morphology. |
| `cloud_cover_mid` | Mid cloud from 3 to 8 km, 0–100% | Same comparison. The stronger of low and mid is the lower deck. |
| `cloud_cover_high` | High cloud from 8 km, 0–100% | The only high-cloud signal. Wispy filaments and a higher deck require this field. |
| `temperature_2m`, `wind_direction_10m` | Unchanged | Wind still stretches frontal bands. Temperature is display only. |

These layer values are numerical weather model fields, not a satellite cloud mask.
Open-Meteo does not return a satellite-observed cloud mask on this endpoint.
ICON publishes low, mid, and high cloud as native model output. GFS and HRRR
publish cloud-cover layers in their native set. ECMWF IFS derives
`cloud_cover_low` from relative humidity at 1000, 925, and 850 hPa,
`cloud_cover_mid` from 700 and 500 hPa, and `cloud_cover_high` from 300, 250,
and 200 hPa, and uses native layer fields where the model has them. GEM supplies
only total cloud cover natively; its layers are estimated from pressure-level
humidity and can differ from the total. Best match may select any of those
models. Live requests for New York, London, the equator, the south pole,
southern Africa, East Asia, and Iceland all returned integer percents for all
three layers.

Availability and fallback:

- A normal best-match `current` response includes all three layer integers for
  every requested cell. The forecast API documents them as global current
  variables.
- If a layer key is missing or JSON null, that reading has no verified column.
  High-cloud weight is 0, the label stays on the weather code, and the map uses
  the previous billow, front, storm, and fog path. The rest of the snapshot is
  still accepted.
- If only one or two of the three layers arrive, the column is incomplete and
  is treated the same way. A lone `cloud_cover_high` is not enough to tell high
  cloud from a lower deck.
- If a present layer is not an integer from 0 to 100, the whole snapshot is
  rejected, same as an out-of-range `cloud_cover`. The last good map stays up
  with the existing offline label.
- Fog (`45`, `48`), rain, snow, and thunderstorm codes keep those patterns even
  when `cloud_cover_high` is large. A storm anvil is not relabeled as cirrus.

High-cloud weight is 0 unless the code would otherwise be scattered cloud or
overcast, total cover is at least 15%, all three layers are in range,
`cloud_cover_high` is at least 25%, and it exceeds both lower layers by at
least 20 points. The weight then rises from 0 to 1 over the next 40 points of
margin. The "High cloud" label requires a weight of at least 0.5. That weight
blends the 8K coverage field from cellular billows toward wind-stretched
filaments, thins optical depth, and raises the height stored in the texture's
red channel. Total `cloud_cover` still decides how much of the field is filled.

Map size stays 8192×4096. The morphology control texture stays 4096×2048.
Earth remains three draws, and the cloud shader's texture samples are
unchanged. Representative cases compared in
`WeatherCloudMapIntegrationTest` are generic partly cloudy with no layers,
the same code with a weak high layer, verified dominant high cloud, a low
overcast deck, and a thunderstorm that also reports a high layer.

Headset appearance is not established by those map comparisons or by the
Blender and Unreal authoring previews. See
[high-cloud verification](validation/HIGH_CLOUD.md).
The panel includes model time, offline status and data attribution. An incomplete,
invalid, future-dated or more than 30-minute-old response is rejected. A failed
update retains the last successful map with an offline label; a different region
starts from the original map rather than inheriting the previous region's data.

Provider documentation: <https://open-meteo.com/en/docs>.
Data attribution/license: [Open-Meteo](https://open-meteo.com/),
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
The attribution also ships in `weather_credits.txt` and the weather panel.

## Blender and Unreal sources

The native renderer now uses `blender/weather_continuous.blend` and
`textures/weather/cloud_structure.png`, authored by `scripts/author_cloud_structure.py`.
Its RGB channels hold global ranked morphology, multiscale erosion and warped
fronts, with longitude wrapping and polar convergence. The source cloud morphology
comes from the attributed Solar System Scope map. Model weather controls coverage
and cloud type; source patterns do not establish actual live cloud positions.
The smaller original density atlas and neutral meshes below remain authoring and
Unreal reference assets; the app no longer stamps that atlas onto Earth.

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
stay on the main thread. Replaced GPU textures are destroyed. The surface and cloud shell share that same texture so land shadows follow
the active weather. Surface/city maps use native 8K detail; normal, ocean and cloud
maps are 4K. Atmosphere, geometry and shared day/night timing remain intact. Earth remains three draws; weather adds no cloud mesh draws.

Run `scripts/validate_weather_assets.cjs` with `gltf-validator` available on the
Node module path to check the exported GLBs and atlas packaging.

See [verification results](validation/ORBITAL_WEATHER.md) for automated checks,
headset evidence and the remaining visual checks.
