package casa.archer.metatheatre

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Jellyfin auth, URL building, item mapping, and reporting against a fake transport. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JellyfinClientTest {

    private class FakeTransport(val handler: (JellyfinHttpRequest) -> JellyfinHttpResponse) :
        JellyfinTransport {
        val requests = mutableListOf<JellyfinHttpRequest>()
        override fun execute(request: JellyfinHttpRequest): JellyfinHttpResponse {
            requests.add(request)
            return handler(request)
        }
    }

    private fun authJson(token: String = "tok-123") =
        """{"AccessToken":"$token","ServerId":"srv-1","User":{"Id":"user-1","Name":"alice","ServerId":"srv-1"}}"""

    private fun signedInClient(extra: (JellyfinHttpRequest) -> JellyfinHttpResponse): Pair<JellyfinClient, FakeTransport> {
        val transport = FakeTransport { request ->
            if (request.url.endsWith("/Users/AuthenticateByName")) {
                JellyfinHttpResponse(200, authJson())
            } else {
                extra(request)
            }
        }
        val client = JellyfinClient(MemoryJellyfinSessionStore(), transport, "device-1")
        client.authenticateByName("http://nas.example:8096/", "alice", "secret-pw")
        return client to transport
    }

    @Test fun passwordSignInSavesTokenBoundToServerAndDevice() {
        val store = MemoryJellyfinSessionStore()
        lateinit var captured: JellyfinHttpRequest
        val transport = FakeTransport { request ->
            captured = request
            JellyfinHttpResponse(200, authJson())
        }
        val client = JellyfinClient(store, transport, "device-1")
        val session = client.authenticateByName("http://nas.example:8096/", "alice", "secret-pw")

        assertEquals("http://nas.example:8096", session.serverUrl)
        assertEquals("tok-123", session.accessToken)
        assertEquals("user-1", session.userId)
        assertEquals("alice", session.userName)
        assertEquals(session, store.load())
        assertEquals(session, client.session)
        // The crate's header shape: client, device, device id, version; no token pre-auth.
        val header = captured.headers.getValue("Authorization")
        assertTrue(header.startsWith("MediaBrowser Client=\"Meta%20Theatre\""))
        assertTrue(header.contains("Device=\"Quest%203\""))
        assertTrue(header.contains("DeviceId=\"device-1\""))
        assertFalse(header.contains("Token="))
        val body = JSONObject(captured.body!!)
        assertEquals("alice", body.getString("Username"))
        // Secrets never leak into stored or printed session state.
        assertFalse(session.toString().contains("tok-123"))
        assertFalse(session.toString().contains("secret-pw"))
        assertFalse(store.load().toString().contains("secret-pw"))
    }

    @Test fun serverUrlNormalizationKeepsSubpathAndRejectsUnsafeUrls() {
        assertEquals(
            "http://nas.example:8096/jellyfin",
            jellyfinNormalizeBaseUrl("http://nas.example:8096/jellyfin/"),
        )
        assertEquals("https://jellyfin.example", jellyfinNormalizeBaseUrl(" https://jellyfin.example/ "))
        for (bad in listOf("nas.example:8096", "ftp://nas.example", "http://user:pw@nas.example",
            "http://nas.example/?x=1", "http://nas.example/#frag", "")) {
            try {
                jellyfinNormalizeBaseUrl(bad)
                fail("expected rejection for $bad")
            } catch (expected: JellyfinException) {
                assertEquals(JellyfinFailure.AUTH, expected.failure)
            }
        }
    }

    @Test fun authorizationHeaderEncodesValuesAndAppendsToken() {
        val anonymous = jellyfinAuthorizationHeader("dev ice", null)
        assertFalse(anonymous.contains("Token="))
        assertTrue(anonymous.contains("DeviceId=\"dev%20ice\""))
        val authed = jellyfinAuthorizationHeader("device-1", "a b\"c")
        assertTrue(authed.endsWith(", Token=\"a%20b%22c\""))
        assertEquals("%C3%A4", jellyfinEncodeComponent("ä"))
        assertEquals("A-_.!~*'()", jellyfinEncodeComponent("A-_.!~*'()"))
    }

    @Test fun quickConnectPollsExchangesAndStores() {
        val store = MemoryJellyfinSessionStore()
        var polls = 0
        val transport = FakeTransport { request ->
            when {
                request.url.endsWith("/QuickConnect/Initiate") ->
                    JellyfinHttpResponse(200, """{"Secret":"s3cr3t","Code":"123456","Authenticated":false}""")
                request.url.contains("/QuickConnect/Connect") -> {
                    polls++
                    JellyfinHttpResponse(
                        200,
                        """{"Secret":"s3cr3t","Code":"123456","Authenticated":${polls >= 2}}""",
                    )
                }
                request.url.endsWith("/Users/AuthenticateWithQuickConnect") ->
                    JellyfinHttpResponse(200, authJson("qc-token"))
                else -> JellyfinHttpResponse(404, "")
            }
        }
        val client = JellyfinClient(store, transport, "device-1")
        val challenge = client.initiateQuickConnect("http://nas.example:8096")
        assertEquals("123456", challenge.code)
        assertFalse(challenge.toString().contains("s3cr3t"))

        assertNull(client.pollQuickConnect("http://nas.example:8096", challenge.secret))
        val session = client.pollQuickConnect("http://nas.example:8096", challenge.secret)
        assertEquals("qc-token", session?.accessToken)
        assertEquals(session, store.load())
        val exchange = transport.requests.last { it.url.endsWith("/Users/AuthenticateWithQuickConnect") }
        assertEquals("s3cr3t", JSONObject(exchange.body!!).getString("Secret"))
    }

    @Test fun quickConnectDisabledAndUnknownSecretMapToAuthFailures() {
        val disabled = JellyfinClient(
            MemoryJellyfinSessionStore(),
            FakeTransport { JellyfinHttpResponse(401, "") },
            "device-1",
        )
        try {
            disabled.initiateQuickConnect("http://nas.example:8096")
            fail("expected Quick Connect disabled")
        } catch (expected: JellyfinException) {
            assertTrue(expected.message!!.contains("disabled"))
        }
        val unknown = JellyfinClient(
            MemoryJellyfinSessionStore(),
            FakeTransport { JellyfinHttpResponse(404, "") },
            "device-1",
        )
        try {
            unknown.pollQuickConnect("http://nas.example:8096", "gone")
            fail("expected unknown secret")
        } catch (expected: JellyfinException) {
            assertEquals(JellyfinFailure.AUTH, expected.failure)
        }
    }

    @Test fun directPlayAndImageUrlsCarryApiKey() {
        assertEquals(
            "https://jellyfin.example/Videos/item-1/stream?static=true",
            jellyfinDirectPlayUrl("https://jellyfin.example/", "item-1", null),
        )
        assertEquals(
            "https://jellyfin.example/Videos/item-1/stream?static=true&MediaSourceId=src-9&api_key=tok-abc",
            jellyfinDirectPlayUrl("https://jellyfin.example", "item-1", "tok-abc", "src-9"),
        )
        val image = jellyfinImageUrl("http://nas:8096/jellyfin", "item 1", token = "tok", tag = "tag-1")
        assertTrue(image.startsWith("http://nas:8096/jellyfin/Items/item%201/Images/Primary?"))
        assertTrue(image.contains("api_key=tok"))
        assertTrue(image.contains("tag=tag-1"))
    }

    @Test fun ticksConvertBothWays() {
        assertEquals(9_720_000L, jellyfinTicksToMs(97_200_000_000L))
        assertEquals(97_200_000_000L, jellyfinMsToTicks(9_720_000L))
        assertEquals(0L, jellyfinTicksToMs(0L))
        assertEquals(0L, jellyfinMsToTicks(-5L))
    }

    @Test fun itemMappingReadsResumeStereoStreamsAndArtwork() {
        val json = JSONObject(
            """{
              "Id":"m1","Name":"Avatar","Type":"Movie","ProductionYear":2009,
              "Path":"/media/Avatar.HSBS.mkv","Video3DFormat":"HalfSideBySide",
              "RunTimeTicks":972000000000,
              "ImageTags":{"Primary":"tag-9"},
              "MediaStreams":[
                {"Index":0,"Type":"Video","Codec":"hevc","Width":3840,"Height":1080,"IsDefault":true},
                {"Index":1,"Type":"Audio","Codec":"aac","Language":"eng","IsDefault":true},
                {"Index":2,"Type":"Subtitle","Codec":"srt","Language":"eng"}],
              "UserData":{"PlaybackPositionTicks":600000000,"Played":false,"IsFavorite":true},
              "FutureField":{"Keep":true}
            }""",
        )
        val item = parseJellyfinItem(json)
        assertEquals("m1", item.id)
        assertEquals(2009, item.productionYear)
        assertEquals(97_200_000L, item.durationMs)
        assertEquals(60_000L, item.resumeMs)
        assertTrue(item.isFavorite)
        assertEquals(3840, item.videoStream?.width)
        assertEquals(StereoFormat.HALF_SIDE_BY_SIDE, item.detectedStereo().format)
        assertEquals("tag-9", item.primaryImageTag)
        assertTrue(jellyfinItemSubtitle(item).contains("2009"))
        assertTrue(jellyfinItemSubtitle(item).contains("3D HSBS"))
    }

    @Test fun episodeAndSeriesSubtitlesUseShowLabels() {
        val episode = parseJellyfinItem(
            JSONObject(
                """{"Id":"e1","Name":"Pilot","Type":"Episode","SeriesName":"Dark",
                   "IndexNumber":3,"ParentIndexNumber":1,
                   "UserData":{"PlaybackPositionTicks":0}}""",
            ),
        )
        assertEquals("Dark  ·  S01E03", jellyfinItemSubtitle(episode))
        val series = parseJellyfinItem(
            JSONObject("""{"Id":"s1","Name":"Dark","Type":"Series","ChildCount":3}"""),
        )
        assertEquals("3 seasons", jellyfinItemSubtitle(series))
    }

    @Test fun browseRequestsUseCrateFieldsPagingAndAuthHeader() {
        val (client, transport) = signedInClient { request ->
            assertTrue(request.url.contains("/Items?"))
            JellyfinHttpResponse(
                200,
                """{"Items":[{"Id":"m1","Name":"Dune","Type":"Movie","Path":"/m/Dune.mkv"}],
                   "TotalRecordCount":1,"StartIndex":0}""",
            )
        }
        val items = client.items(parentId = "lib-1", includeItemTypes = listOf("Movie"))
        assertEquals(listOf("Dune"), items.map { it.title })
        val browse = transport.requests.last { it.url.contains("/Items?") }
        assertTrue(browse.url.contains("ParentId=lib-1"))
        assertTrue(browse.url.contains("IncludeItemTypes=Movie"))
        assertTrue(browse.url.contains("Fields=Video3DFormat%2CMediaStreams%2CPath"))
        assertTrue(browse.url.contains("StartIndex=0"))
        assertTrue(browse.headers.getValue("Authorization").contains("Token=\"tok-123\""))
        assertTrue(items.single().poster.contains("/Items/m1/Images/Primary"))
        assertTrue(items.single().poster.contains("api_key=tok-123"))
    }

    @Test fun emptyPageStopsPagingEvenWhenServerClaimsMore() {
        val (client, _) = signedInClient {
            JellyfinHttpResponse(200, """{"Items":[],"TotalRecordCount":500,"StartIndex":0}""")
        }
        assertTrue(client.items(parentId = "lib-1").isEmpty())
    }

    @Test fun playablePrefersDirectPlayAndCarriesStereoAndHeaders() {
        val (client, transport) = signedInClient { request ->
            when {
                request.url.endsWith("/PlaybackInfo") -> JellyfinHttpResponse(
                    200,
                    """{"PlaySessionId":"sess-1","MediaSources":[
                        {"Id":"src-1","Container":"mkv","SupportsDirectPlay":true,
                         "SupportsDirectStream":true,"SupportsTranscoding":false}]}""",
                )
                request.url.contains("/Users/user-1/Items/m1") -> JellyfinHttpResponse(
                    200,
                    """{"Id":"m1","Name":"Avatar","Type":"Movie","Path":"/media/Avatar.mkv",
                       "Video3DFormat":"FullTopAndBottom",
                       "MediaStreams":[{"Index":0,"Type":"Video","Codec":"hevc","Width":1920,"Height":2160}]}""",
                )
                else -> JellyfinHttpResponse(404, "")
            }
        }
        val playable = client.playable(JellyfinItem(id = "m1", title = "Avatar", type = "Movie"))
        assertEquals(
            "http://nas.example:8096/Videos/m1/stream?static=true&MediaSourceId=src-1&api_key=tok-123",
            playable.directUrl,
        )
        assertEquals(SourceKind.JELLYFIN, playable.sourceKind)
        assertEquals("m1", playable.sourceItemId)
        assertEquals(StereoFormat.FULL_OVER_UNDER, playable.stereo.format)
        assertEquals(1920, playable.sourceWidth)
        assertTrue(playable.headers.getValue("Authorization").contains("Token=\"tok-123\""))
        assertFalse(playable.toString().contains("tok-123"))
        val infoRequest = transport.requests.first { it.url.endsWith("/PlaybackInfo") }
        assertEquals("user-1", JSONObject(infoRequest.body!!).getString("UserId"))
    }

    @Test fun resolvePlaybackFallsBackAndRefusesCrossOrigin() {
        val directStream = JellyfinPlaybackInfo(
            playSessionId = "s",
            mediaSources = listOf(
                JellyfinPlaybackSource(
                    id = "src-2", supportsDirectStream = true,
                    directStreamUrl = "/Videos/m1/stream?MediaSourceId=src-2&api_key=leaked",
                ),
            ),
        )
        val resolved = jellyfinResolvePlayback("http://nas:8096/jellyfin", "m1", directStream, "tok")
        assertEquals(JellyfinPlayMethod.DIRECT_STREAM, resolved.method)
        assertTrue(resolved.url.startsWith("http://nas:8096/jellyfin/Videos/m1/stream"))
        assertFalse(resolved.url.contains("api_key"))

        val crossOrigin = JellyfinPlaybackInfo(
            mediaSources = listOf(
                JellyfinPlaybackSource(
                    id = "src-3", supportsDirectStream = true,
                    directStreamUrl = "https://evil.example/Videos/m1/stream",
                ),
            ),
        )
        try {
            jellyfinResolvePlayback("http://nas:8096", "m1", crossOrigin, "tok")
            fail("expected cross-origin refusal")
        } catch (expected: JellyfinException) {
            assertTrue(expected.message!!.contains("stay on the Jellyfin server"))
        }
        try {
            jellyfinResolvePlayback("http://nas:8096", "m1", JellyfinPlaybackInfo(), "tok")
            fail("expected no playable stream")
        } catch (expected: JellyfinException) {
            assertTrue(expected.message!!.contains("no playable stream"))
        }
    }

    @Test fun sessionReportingSendsTicksAndSessionId() {
        val (client, transport) = signedInClient { JellyfinHttpResponse(204, "") }
        client.reportPlaying("m1", "sess-1", 5_000L, "src-1")
        client.reportProgress("m1", "sess-1", 61_500L, isPaused = true)
        client.reportStopped("m1", "sess-1", 62_000L)

        val playing = transport.requests.first { it.url.endsWith("/Sessions/Playing") }
        val playingBody = JSONObject(playing.body!!)
        assertEquals("m1", playingBody.getString("ItemId"))
        assertEquals("DirectPlay", playingBody.getString("PlayMethod"))
        assertEquals(50_000_000L, playingBody.getLong("PositionTicks"))

        val progress = transport.requests.first { it.url.endsWith("/Sessions/Playing/Progress") }
        val progressBody = JSONObject(progress.body!!)
        assertEquals(615_000_000L, progressBody.getLong("PositionTicks"))
        assertEquals(true, progressBody.getBoolean("IsPaused"))
        assertEquals("TimeUpdate", progressBody.getString("EventName"))

        val stopped = transport.requests.first { it.url.endsWith("/Sessions/Playing/Stopped") }
        assertEquals(620_000_000L, JSONObject(stopped.body!!).getLong("PositionTicks"))
    }

    @Test fun libraryViewsAndResumeMapThroughSourceAdapter() {
        val (client, _) = signedInClient { request ->
            when {
                request.url.endsWith("/Views") -> JellyfinHttpResponse(
                    200,
                    """{"Items":[{"Id":"lib-m","Name":"Movies","CollectionType":"movies"},
                                 {"Id":"lib-t","Name":"Shows","CollectionType":"tvshows"}]}""",
                )
                request.url.contains("/Items/Resume") -> JellyfinHttpResponse(
                    200,
                    """{"Items":[{"Id":"m9","Name":"Dune","Type":"Movie",
                       "UserData":{"PlaybackPositionTicks":300000000}}]}""",
                )
                request.url.contains("/Items?") -> JellyfinHttpResponse(
                    200,
                    """{"Items":[{"Id":"m1","Name":"Dune","Type":"Movie","Path":"/m/Dune.mkv"}],
                       "TotalRecordCount":1}""",
                )
                else -> JellyfinHttpResponse(404, "")
            }
        }
        val source = JellyfinMediaSource(client)
        assertEquals(SourceKind.JELLYFIN, source.kind)
        val libraries = source.libraries()
        assertEquals(listOf("Movies", "Shows"), libraries.map { it.title })
        assertEquals(listOf("movie", "show"), libraries.map { it.mediaKind })
        assertEquals(listOf("Dune"), source.items(libraries.first()).map { it.title })
        assertEquals(30_000L, source.resumeItems().single().resumeMs)
    }

    @Test fun signedOutClientRefusesBrowseAndPlayback() {
        val client = JellyfinClient(
            MemoryJellyfinSessionStore(),
            FakeTransport { JellyfinHttpResponse(200, "{}") },
            "device-1",
        )
        try {
            client.libraryViews()
            fail("expected not signed in")
        } catch (expected: JellyfinException) {
            assertEquals(JellyfinFailure.AUTH, expected.failure)
        }
    }

    @Test fun httpFailuresMapToFailuresWithoutLeakingToken() {
        val (client, _) = signedInClient { JellyfinHttpResponse(503, "down") }
        try {
            client.items()
            fail("expected server failure")
        } catch (expected: JellyfinException) {
            assertEquals(JellyfinFailure.SERVER, expected.failure)
            assertFalse(expected.message!!.contains("tok-123"))
        }
    }
}
