# High-cloud morphology

Source and render checks for modeled low, mid, and high cloud fractions.
Headset behavior is recorded separately below and is not inferred from these
checks or from Blender/Unreal previews.

## Provider

The forecast `current` request asks Open-Meteo for `cloud_cover_low`,
`cloud_cover_mid`, and `cloud_cover_high` next to the existing weather code,
total cloud cover, temperature, and wind direction. Those layer fields are
model output (native on some models, humidity-derived on others). They are not
a satellite cloud mask. Missing or null layers fall back to the weather-code
shapes. Out-of-range layer numbers reject the snapshot. Field definitions,
model notes, and the weight rule are in [the weather notes](../WEATHER.md).

## What the automated checks cover

- `forCode` never returns high cloud, including partly cloudy codes `1` and `2`
  and overcast code `3`.
- A reading with no layers, a partial column, a weak high fraction, or a high
  layer sitting on a lower deck stays billowing.
- Dominant high cloud on a scattered or overcast code becomes the high-cloud
  label and a thinner, higher filament field.
- Fog, rain, snow, and thunderstorm codes keep their patterns. A thunderstorm
  with a reported high layer keeps the existing thunderhead pixels.
- Generated maps stay 8192×4096, including the representative cases above.
- The cloud shader and the three Earth draws are unchanged, so the draw and
  sample budget is the same.

## Commands

`./gradlew :app:testDebugUnitTest :app:assembleDebug` passed on 2026-11-12
with JDK 17: 241 unit tests, 0 failures, 0 errors, and a debug APK. The
weather pixel check still matches the previous full-resolution checksums
for rain at the equator, the date line, and the pole. No shader or mesh
file changed.

## Headset

The isolated implementation was not run on Quest. The subsequent combined
build uploaded an 8K high-cloud fixture and passed native lifecycle checks;
see [combined validation](REMAINING_ASSETS_20261112.md). Wearer review is deferred.
Automated checks do not establish how the filaments read in stereo, at the
limb, or over a dark ocean at night.
