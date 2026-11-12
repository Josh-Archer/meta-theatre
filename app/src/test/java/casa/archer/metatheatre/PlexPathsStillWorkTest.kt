package casa.archer.metatheatre

import java.net.URI
import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Regression guard: adding Jellyfin must not change Plex URL or routing behavior. */
class PlexPathsStillWorkTest {

    private fun query(url: String): Map<String, String> = URI(url).rawQuery.split('&').associate { part ->
        URLDecoder.decode(part.substringBefore('='), "UTF-8") to
            URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
    }

    @Test fun plexHlsUrlKeepsQualityTokenAndProfileContract() {
        val server = PlexServer("Test", "https://example.invalid/", "test+token&value")
        val request = query(plexMovieHlsUrl(server, "42", "test-client", 3, MovieQuality.UHD, "test-session"))
        assertEquals("3840x2160", request["videoResolution"])
        assertEquals("80000", request["maxVideoBitrate"])
        assertEquals("/library/metadata/42", request["path"])
        assertEquals(server.token, request["X-Plex-Token"])
        assertEquals("hls", request["protocol"])
        assertFalse(server.toString().contains(server.token))
    }

    @Test fun plexRouteFallbackChainIsUnchanged() {
        assertEquals(PlaybackRoute.DIRECT, PlaybackQuality.ORIGINAL.startingRoute)
        assertEquals(PlaybackRoute.PLEX_UHD, PlaybackRoute.DIRECT.next())
        assertEquals(PlaybackRoute.PLEX_HD, PlaybackRoute.PLEX_UHD.next())
        assertNull(PlaybackRoute.PLEX_HD.next())
    }

    @Test fun plexVersionPreferenceIsUnchanged() {
        val versions = listOf(
            PlexMediaVersion(0, 1920, 1080, 20_000),
            PlexMediaVersion(1, 3840, 2160, 60_000),
        )
        assertEquals(1, preferredMovieVersion(versions)?.index)
        assertNull(preferredMovieVersion(emptyList()))
    }

    @Test fun plexPlayableDefaultsStayPlexMono() {
        val playable = Playable(
            title = "Movie",
            directUrl = "https://example.invalid/video",
            hlsUrl = "https://example.invalid/hls",
            headers = emptyMap(),
        )
        assertEquals(SourceKind.PLEX, playable.sourceKind)
        assertEquals(StereoFormat.MONO, playable.stereo.format)
        assertEquals("", playable.sourceItemId)
        assertFalse(playable.toString().contains("example.invalid"))
    }
}
