package casa.archer.metatheatre

import com.meta.spatial.core.Vector3
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.*

/** UTC-based rotation and NOAA's approximate solar declination/equation of time. */
internal object RealEarthTime {
    const val SIDEREAL_SECONDS = 86164.0905
    fun rotationSince(anchor: Instant, now: Instant): Float {
        val seconds = Duration.between(anchor, now).let { it.seconds.toDouble() + it.nano / 1e9 }
        return ((seconds % SIDEREAL_SECONDS) / SIDEREAL_SECONDS * 360).toFloat()
    }

    // https://gml.noaa.gov/grad/solcalc/solareqns.PDF
    fun subsolar(instant: Instant): WeatherPlace {
        val utc = instant.atZone(ZoneOffset.UTC)
        val hours = utc.toLocalTime().toNanoOfDay() / 3_600_000_000_000.0
        val year = 2 * PI / utc.toLocalDate().lengthOfYear() * (utc.dayOfYear - 1 + (hours - 12) / 24)
        val equation = 229.18 * (.000075 + .001868*cos(year) - .032077*sin(year) -
            .014615*cos(2*year) - .040849*sin(2*year))
        val declination = .006918 - .399912*cos(year) + .070257*sin(year) - .006758*cos(2*year) +
            .000907*sin(2*year) - .002697*cos(3*year) + .00148*sin(3*year)
        return WeatherPlace(Math.toDegrees(declination), WeatherPlace.wrap(180 - hours*15 - equation/4))
    }

    fun localSun(instant: Instant): Vector3 {
        val place = subsolar(instant)
        // The asset's front meridian is 100 degrees west, not Greenwich.
        return WeatherPlace(place.latitude, WeatherPlace.wrap(place.longitude - EarthLighting.CENTER_LONGITUDE)).normal()
    }
}
