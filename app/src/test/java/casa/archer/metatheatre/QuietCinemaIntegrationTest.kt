package casa.archer.metatheatre

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class QuietCinemaIntegrationTest {
    @Test
    fun preferenceDefaultsOffAndPersistsAcrossStateInstances() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("theatre_state", 0).edit().clear().commit()
        val initial = TheatreState(context)
        assertFalse(initial.quietCinemaEnabled)

        initial.saveQuietCinemaEnabled(true)

        assertTrue(TheatreState(context).quietCinemaEnabled)
    }

    @Test
    fun preferenceAndWatchingTogetherControlTheActiveCallback() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("theatre_state", 0).edit().clear().commit()
        val changes = mutableListOf<Boolean>()
        val quietCinema = QuietCinema(TheatreState(context), changes::add)

        quietCinema.setWatching(true)
        assertFalse(quietCinema.active)
        assertTrue(changes.isEmpty())

        quietCinema.setPreferenceEnabled(true)
        assertTrue(quietCinema.active)
        assertEquals(listOf(true), changes)

        quietCinema.setPreferenceEnabled(true)
        quietCinema.setWatching(false)
        quietCinema.setPreferenceEnabled(false)
        assertFalse(quietCinema.active)
        assertEquals(listOf(true, false), changes)
        assertFalse(TheatreState(context).quietCinemaEnabled)
    }

    @Test
    fun pausedFlybyClockFreezesAndResumesAtTheSameLogicalPhase() {
        var now = 1_000L
        val clock = PausableElapsedClock { now }

        now += 1_800L
        assertEquals(1.8, clock.elapsedSeconds(), 1e-9)
        clock.setPaused(true)
        now += 3_200L
        assertEquals(1.8, clock.elapsedSeconds(), 1e-9)
        clock.setPaused(false)
        now += 800L

        assertEquals(2.6, clock.elapsedSeconds(), 1e-9)
    }

    @Test
    fun sceneLightingUsesOneDimmerScaleAndRestoresItsNormalStrength() {
        val normal = QuietCinemaLighting.levels(quiet = false)
        val quiet = QuietCinemaLighting.levels(quiet = true)
        val restored = QuietCinemaLighting.levels(quiet = false)

        assertEquals(1f, normal.ambient, 0f)
        assertEquals(1f, normal.sun, 0f)
        assertEquals(1f, normal.environment, 0f)
        assertEquals(quiet.ambient, quiet.sun, 0f)
        assertEquals(quiet.sun, quiet.environment, 0f)
        assertTrue(quiet.ambient in 0f..1f)
        assertEquals(normal, restored)
    }
}
