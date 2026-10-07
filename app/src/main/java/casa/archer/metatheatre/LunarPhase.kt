package casa.archer.metatheatre

import java.time.Instant
import kotlin.math.*

/** Offline geocentric phase, Meeus 47/48; SunCalc BSD-2-Clause adaptation, art/licenses/suncalc.txt. */
object LunarPhase {
    data class Phase(val fraction: Double, val cycle: Double) {
        val waxing get() = cycle < 0.5
    }
    private const val RAD = PI / 180.0

    fun at(instant: Instant): Phase {
        val days = instant.epochSecond / 86400.0 + instant.nano / 8.64e13 - 10957.5
        val year = 2000 + days / 365.2425
        val dt = if (year < 2050) {
            val y = year - 2000
            62.92 + y * (0.32217 + y * 0.005589)
        } else {
            val y = (year - 1820) / 100
            -20 + 32 * y * y - 0.5628 * (2150 - year)
        }
        val t = (days + dt / 86400) / 36525
        val om = RAD * (125.04452 - 1934.136261 * t)
        val ls = RAD * (280.4665 + 36000.7698 * t)
        val lm = RAD * (218.3165 + 481267.8813 * t)
        val dpsi = (-17.20 * sin(om) - 1.32 * sin(2 * ls) - 0.23 * sin(2 * lm) + 0.21 * sin(2 * om)) / 3600
        val deps = (9.20 * cos(om) + 0.57 * cos(2 * ls) + 0.10 * cos(2 * lm) - 0.09 * cos(2 * om)) / 3600
        val eps0 = 23.439291 - t * (0.0130042 + t * (0.00000016 - t * 0.000000504))
        val eps = RAD * (eps0 + deps)
        val lp = 218.3164477 + t * (481267.88123421 + t * (-0.0015786 + t * (1.0 / 538841 - t / 65194000)))
        val d = RAD * (297.8501921 + t * (445267.1114034 + t * (-0.0018819 + t * (1.0 / 545868 - t / 113065000))))
        val m = RAD * (357.5291092 + t * (35999.0502909 + t * (-0.0001536 + t / 24490000)))
        val mp = RAD * (134.9633964 + t * (477198.8675055 + t * (0.0087414 + t * (1.0 / 69699 - t / 14712000))))
        val f = RAD * (93.2720950 + t * (483202.0175233 + t * (-0.0036539 + t * (-1.0 / 3526000 + t / 863310000))))
        val e = 1 - t * (0.002516 + t * 0.0000074)
        var sl = 0.0; var sr = 0.0; var sb = 0.0
        fun eccentricity(n: Int) = when (abs(n)) { 1 -> e; 2 -> e * e; else -> 1.0 }
        val lon = LunarTerms.moonLon
        for (i in lon.indices step 6) {
            val arg = lon[i] * d + lon[i + 1] * m + lon[i + 2] * mp + lon[i + 3] * f
            val factor = eccentricity(lon[i + 1])
            sl += lon[i + 4] * factor * sin(arg)
            sr += lon[i + 5] * factor * cos(arg)
        }
        val lat = LunarTerms.moonLat
        for (i in lat.indices step 5) {
            val arg = lat[i] * d + lat[i + 1] * m + lat[i + 2] * mp + lat[i + 3] * f
            sb += lat[i + 4] * eccentricity(lat[i + 1]) * sin(arg)
        }
        val a1 = RAD * (119.75 + 131.849 * t)
        val lpr = RAD * lp
        sl += 3958 * sin(a1) + 1962 * sin(lpr - f) + 318 * sin(RAD * (53.09 + 479264.290 * t))
        sb += -2235 * sin(lpr) + 382 * sin(RAD * (313.45 + 481266.484 * t)) +
            175 * sin(a1 - f) + 175 * sin(a1 + f) + 127 * sin(lpr - mp) - 115 * sin(lpr + mp)
        val ml = RAD * (lp + sl / 1e6 + dpsi)
        val mb = RAD * (sb / 1e6)
        val moonRa = atan2(sin(ml) * cos(eps) - tan(mb) * sin(eps), cos(ml))
        val moonDec = asin(sin(mb) * cos(eps) + cos(mb) * sin(eps) * sin(ml))
        val distance = 385000.56 + sr / 1000
        val sunM = RAD * (357.52911 + t * (35999.05029 - t * 0.0001537))
        val c = RAD * ((1.914602 - t * (0.004817 + t * 0.000014)) * sin(sunM) +
            (0.019993 - 0.000101 * t) * sin(2 * sunM) + 0.000289 * sin(3 * sunM))
        val sunOm = RAD * (125.04 - 1934.136 * t)
        val sunLon = RAD * (280.46646 + t * (36000.76983 + t * 0.0003032)) + c -
            RAD * (0.00569 + 0.00478 * sin(sunOm))
        val sunEps = RAD * eps0 + RAD * 0.00256 * cos(sunOm)
        val sunRa = atan2(cos(sunEps) * sin(sunLon), cos(sunLon))
        val sunDec = asin(sin(sunEps) * sin(sunLon))
        val elongation = acos((sin(sunDec) * sin(moonDec) + cos(sunDec) * cos(moonDec) * cos(sunRa - moonRa)).coerceIn(-1.0, 1.0))
        val incidence = atan2(149598000 * sin(elongation), distance - 149598000 * cos(elongation))
        val cycle = ((ml - sunLon) / (2 * PI) % 1 + 1) % 1
        return Phase((1 + cos(incidence)) / 2, cycle)
    }
}
