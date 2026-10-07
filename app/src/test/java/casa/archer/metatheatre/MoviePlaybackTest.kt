package casa.archer.metatheatre

import java.net.URI
import java.net.URLDecoder
import org.junit.Assert.*
import org.junit.Test

class MoviePlaybackTest {
    private fun query(url: String): Map<String, String> = URI(url).rawQuery.split('&').associate { part ->
        val key = part.substringBefore('=')
        URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(part.substringAfter('='), "UTF-8")
    }

    @Test fun nativeUhdCopyWinsOverEarlierOptimizedHdCopy() {
        val versions = listOf(
            PlexMediaVersion(0, 1920, 1080, 20_000),
            PlexMediaVersion(1, 3840, 2160, 60_000),
        )
        assertEquals(1, preferredMovieVersion(versions)?.index)
        assertEquals(1, preferredMovieVersion(versions.reversed())?.index)
    }

    @Test fun cinemaAspectRatiosKeepTheirNativeUhdVersion() {
        assertEquals(2, preferredMovieVersion(listOf(
            PlexMediaVersion(0, 1920, 800, 8_000),
            PlexMediaVersion(2, 3840, 1600, 50_000),
        ))?.index)
    }

    @Test fun unsupportedEightKDoesNotDisplaceAvailableUhdCopy() {
        assertEquals(1, preferredMovieVersion(listOf(
            PlexMediaVersion(0, 7680, 4320, 150_000),
            PlexMediaVersion(1, 3840, 2160, 60_000),
        ))?.index)
        assertNull(preferredMovieVersion(emptyList()))
    }

    @Test fun equallySizedCopiesPreferHigherBitrateAndStableIndex() {
        assertEquals(2, preferredMovieVersion(listOf(
            PlexMediaVersion(0, 3840, 2160, 30_000),
            PlexMediaVersion(3, 3840, 2160, 60_000),
            PlexMediaVersion(2, 3840, 2160, 60_000),
        ))?.index)
    }

    @Test fun uhdRequestUsesSelectedCopyAndPreservesEscapedAuthentication() {
        val server = PlexServer("Test", "https://example.invalid/", "test+token&value")
        val url = plexMovieHlsUrl(server, "42", "test-client", 3, MovieQuality.UHD, "test-session")
        val request = query(url)
        assertEquals("3840x2160", request["videoResolution"])
        assertEquals("80000", request["maxVideoBitrate"])
        assertEquals("80000", request["videoBitrate"])
        assertEquals("3", request["mediaIndex"])
        assertEquals("/library/metadata/42", request["path"])
        assertEquals(server.token, request["X-Plex-Token"])
        assertEquals("hls", request["protocol"])
        assertEquals("streaming", request["context"])
        assertEquals("99", request["videoQuality"])
        assertEquals("1", request["directStream"])
        val profile = request.getValue("X-Plex-Client-Profile-Extra")
        assertTrue(profile.contains("name=video.width&value=3840&replace=true"))
        assertTrue(profile.contains("name=video.height&value=2160&replace=true"))
        assertTrue(profile.contains(")+add-limitation("))
        assertFalse(server.toString().contains(server.token))
    }

    @Test fun hdCompatibilityRequestRetainsItsLowerBudgetAndSameSource() {
        val request = query(plexMovieHlsUrl(
            PlexServer("Test", "https://example.invalid", "test-token"),
            "42", "test-client", 3, MovieQuality.HD, "hd-session",
        ))
        assertEquals("1920x1080", request["videoResolution"])
        assertEquals("20000", request["maxVideoBitrate"])
        // A decoder failure at UHD must not just remux that same unsupported video again.
        assertEquals("0", request["directStream"])
        assertEquals("3", request["mediaIndex"])
    }

    @Test fun retriesPreferOriginalThenUhdAndStopAfterHdFailure() {
        val original = PlaybackRoute.DIRECT
        val uhd = original.next()
        val hd = uhd?.next()
        assertEquals(PlaybackRoute.PLEX_UHD, uhd)
        assertEquals(PlaybackRoute.PLEX_HD, hd)
        assertNull(hd?.next())
        assertTrue(PlaybackRoute.PLEX_HD.label.contains("1080p"))
    }

    @Test fun qualityIndicatorUsesActualDecodedDimensions() {
        assertEquals("", movieResolutionLabel(0, 0))
        assertEquals("1920×1080", movieResolutionLabel(1920, 1080))
        assertEquals("4K · 3840×2160", movieResolutionLabel(3840, 2160))
        assertEquals("4K · 3840×1600", movieResolutionLabel(3840, 1600))
    }

    @Test fun explicitQualityChoicesUseTheirRequestedRoute() {
        assertEquals(PlaybackRoute.DIRECT, PlaybackQuality.ORIGINAL.startingRoute)
        assertEquals(PlaybackRoute.PLEX_UHD, PlaybackQuality.UHD.startingRoute)
        assertEquals(PlaybackRoute.PLEX_HD, PlaybackQuality.HD.startingRoute)
    }

    @Test fun lowResolutionSourceIsDistinguishedFromServerDownscaling() {
        assertEquals("4K needs a 4K source file",
            movieQualityNotice(PlaybackQuality.UHD, 720, 404, 720, 404))
        assertTrue(movieQualityNotice(PlaybackQuality.UHD, 3840, 2160, 720, 404).contains("server limits"))
        assertEquals("Plex is delivering a smaller video",
            movieQualityNotice(PlaybackQuality.ORIGINAL, 3840, 1600, 1920, 800))
        assertEquals("", movieQualityNotice(PlaybackQuality.UHD, 3840, 1600, 3840, 1600))
        assertEquals("", movieQualityNotice(PlaybackQuality.UHD, 0, 0, 0, 0))
    }
}
