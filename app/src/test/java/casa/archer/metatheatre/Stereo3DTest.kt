package casa.archer.metatheatre

import com.meta.spatial.runtime.StereoMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported cases from questkit `xr-video::format` and quest-jellyfin-player `format`. */
class Stereo3DTest {

    @Test fun everyJellyfinValueMapsAndUnknownValuesDoNot() {
        assertEquals(StereoFormat.HALF_SIDE_BY_SIDE, jellyfinStereoFormat("HalfSideBySide"))
        assertEquals(StereoFormat.FULL_SIDE_BY_SIDE, jellyfinStereoFormat("FullSideBySide"))
        assertEquals(StereoFormat.FULL_OVER_UNDER, jellyfinStereoFormat("FullTopAndBottom"))
        assertEquals(StereoFormat.HALF_OVER_UNDER, jellyfinStereoFormat("HalfTopAndBottom"))
        assertEquals(StereoFormat.FULL_OVER_UNDER, jellyfinStereoFormat("FullOverUnder"))
        assertEquals(StereoFormat.HALF_OVER_UNDER, jellyfinStereoFormat("HalfOverUnder"))
        assertEquals(StereoFormat.MVC, jellyfinStereoFormat("MVC"))
        assertEquals(StereoFormat.HALF_SIDE_BY_SIDE, jellyfinStereoFormat(" halfSIDEbySIDE "))
        assertEquals(null, jellyfinStereoFormat(""))
        assertEquals(null, jellyfinStereoFormat("FutureFormat"))
        assertEquals(null, jellyfinStereoFormat(null))
    }

    @Test fun jellyfinMetadataWinsOverFilename() {
        val detected = detectStereo("FullTopAndBottom", "/m/Movie.SBS.mkv")
        assertEquals(StereoFormat.FULL_OVER_UNDER, detected.format)
        assertEquals(EyeOrder.TOP_BOTTOM, detected.eyeOrder)
        assertEquals(DetectionSource.JELLYFIN, detected.source)
    }

    @Test fun filenamePackingAndEyeOrder() {
        val sbs = detectStereo(null, "/m/Movie_3D_SBS_RL.mkv")
        assertEquals(StereoFormat.HALF_SIDE_BY_SIDE, sbs.format)
        assertEquals(EyeOrder.RIGHT_LEFT, sbs.eyeOrder)
        assertEquals(DetectionSource.FILENAME, sbs.source)

        val ou = detectStereo("HalfTopAndBottom", "/m/Movie_BT.mkv")
        assertEquals(EyeOrder.BOTTOM_TOP, ou.eyeOrder)
    }

    @Test fun mismatchedEyeOrderFallsBackToPackingDefault() {
        val detected = detectStereo("HalfSideBySide", "/m/Movie_BT.mkv")
        assertEquals(EyeOrder.LEFT_RIGHT, detected.eyeOrder)
    }

    @Test fun realWorldFilenamePatterns() {
        val cases = listOf(
            Triple("Avatar.2009.3D.HSBS.1080p.BluRay.x264.mkv", StereoFormat.HALF_SIDE_BY_SIDE, StereoProjection.FLAT),
            Triple("Gravity (2013) [3d-fSbS].mkv", StereoFormat.FULL_SIDE_BY_SIDE, StereoProjection.FLAT),
            Triple("Dredd.2012.3D.SBS.1080p.mkv", StereoFormat.HALF_SIDE_BY_SIDE, StereoProjection.FLAT),
            Triple("Life of Pi (2012) 3D OU.mkv", StereoFormat.HALF_OVER_UNDER, StereoProjection.FLAT),
            Triple("Hugo.2011.3D.TAB.mkv", StereoFormat.HALF_OVER_UNDER, StereoProjection.FLAT),
            Triple("Movie.3D.FOU.mkv", StereoFormat.FULL_OVER_UNDER, StereoProjection.FLAT),
            Triple("Movie.HTAB.mkv", StereoFormat.HALF_OVER_UNDER, StereoProjection.FLAT),
            Triple("Movie.FTAB.mkv", StereoFormat.FULL_OVER_UNDER, StereoProjection.FLAT),
            Triple("Movie.HOU.mkv", StereoFormat.HALF_OVER_UNDER, StereoProjection.FLAT),
            Triple("Vacation_180_LR.mp4", StereoFormat.HALF_SIDE_BY_SIDE, StereoProjection.EQUIRECT_180),
            Triple("Space_360_tB.mp4", StereoFormat.HALF_OVER_UNDER, StereoProjection.EQUIRECT_360),
            Triple("Tour.360.mp4", StereoFormat.MONO, StereoProjection.EQUIRECT_360),
            Triple("Movie.MVC.mkv", StereoFormat.MVC, StereoProjection.FLAT),
            Triple("Movie.3d.mkv", StereoFormat.UNSPECIFIED_3D, StereoProjection.FLAT),
            Triple("Movie.1080p.x360.x264.mkv", StereoFormat.MONO, StereoProjection.FLAT),
            Triple("SBSpecial.OUtlander.TABle.3Days.1800.360p.mkv", StereoFormat.MONO, StereoProjection.FLAT),
            Triple("Movie.LR.TB.mkv", StereoFormat.MONO, StereoProjection.FLAT),
            Triple("/media/3D/360/Normal.1080p.mkv", StereoFormat.MONO, StereoProjection.FLAT),
            Triple("Movie.hsbs", StereoFormat.MONO, StereoProjection.FLAT),
        )
        for ((name, stereo, projection) in cases) {
            val detected = detectStereo(null, name)
            assertEquals("$name stereo", stereo, detected.format)
            assertEquals("$name projection", projection, detected.projection)
        }
    }

    @Test fun multiviewIsNeverMistakenForOrdinaryHevcOrSbs() {
        for (label in listOf("MV-HEVC", "SpatialMvHevc", "Multiview Main 10")) {
            val detected = detectStereo("HalfSideBySide", "/m/video.mkv", listOf(label))
            assertEquals(label, StereoFormat.MV_HEVC, detected.format)
        }
        assertEquals(StereoFormat.MV_HEVC, detectStereo(null, "/m/video.MV-HEVC.mkv").format)
        assertEquals(
            StereoFormat.MV_HEVC,
            detectStereo("SpatialMvHevc", "/m/video.mkv").format.let {
                detectStereo(null, "/m/video.mkv", listOf("SpatialMvHevc")).format
            },
        )
        val regular = detectStereo(null, "/m/video.hevc.mkv", listOf("hevc Main 10"))
        assertEquals(StereoFormat.MONO, regular.format)
    }

    @Test fun plainMovieIsMonoFlat() {
        val detected = detectStereo(null, "/m/Movie (2020) 1080p.mkv")
        assertEquals(StereoFormat.MONO, detected.format)
        assertEquals(StereoProjection.FLAT, detected.projection)
        assertEquals(DetectionSource.DEFAULT, detected.source)
        assertFalse(detected.isStereo)
        assertEquals("", detected.label)
    }

    @Test fun stereoLabelsAndPanelModes() {
        assertEquals("3D HSBS", DetectedStereo(StereoFormat.HALF_SIDE_BY_SIDE).label)
        assertTrue(DetectedStereo(StereoFormat.FULL_OVER_UNDER).isStereo)
        assertEquals(StereoMode.LeftRight, stereoModeFor(StereoFormat.HALF_SIDE_BY_SIDE))
        assertEquals(StereoMode.LeftRight, stereoModeFor(StereoFormat.FULL_SIDE_BY_SIDE))
        assertEquals(StereoMode.UpDown, stereoModeFor(StereoFormat.HALF_OVER_UNDER))
        assertEquals(StereoMode.UpDown, stereoModeFor(StereoFormat.FULL_OVER_UNDER))
        assertEquals(StereoMode.None, stereoModeFor(StereoFormat.MONO))
        assertEquals(StereoMode.None, stereoModeFor(StereoFormat.MVC))
        assertEquals(StereoMode.None, stereoModeFor(StereoFormat.MV_HEVC))
        assertEquals(StereoMode.None, stereoModeFor(StereoFormat.UNSPECIFIED_3D))
    }
}
