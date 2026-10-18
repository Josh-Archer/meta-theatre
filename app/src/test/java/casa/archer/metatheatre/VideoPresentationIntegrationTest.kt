package casa.archer.metatheatre

import android.os.Looper
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import com.meta.spatial.runtime.AlphaMode
import com.meta.spatial.runtime.BlendFactor
import com.meta.spatial.runtime.LayerConfig
import com.meta.spatial.runtime.PanelConfigOptions
import com.meta.spatial.runtime.PanelShapeLayerBlendType
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoPresentationIntegrationTest {
    private fun format(transfer: Int, mime: String = MimeTypes.VIDEO_H265) = Format.Builder()
        .setSampleMimeType(mime).setColorInfo(ColorInfo.Builder().setColorSpace(C.COLOR_SPACE_BT2020)
            .setColorTransfer(transfer).build()).build()

    @Test fun opaqueMovieLayerKeepsTheSceneHolePunchButCannotBlendSpaceThroughVideo() {
        val options = PanelConfigOptions().apply { layerConfig = LayerConfig(zIndex = -1) }
        VideoPresentation.configure(options)
        assertEquals(AlphaMode.HOLE_PUNCH, options.alphaMode)
        assertEquals(PanelShapeLayerBlendType.OPAQUE, options.layerBlendType)
        assertFalse(options.enableTransparent)
        val blend = options.layerConfig!!.alphaBlend!!
        assertEquals(BlendFactor.ONE, blend.sourceFactorColor)
        assertEquals(BlendFactor.ZERO, blend.destinationFactorColor)
        assertEquals(BlendFactor.ONE, blend.sourceFactorAlpha)
        assertEquals(BlendFactor.ZERO, blend.destinationFactorAlpha)
    }

    @Test fun selectedFrameMetadataDistinguishesDolbyHdrHlgAndUnknownWithoutInferringFrom4k() {
        assertEquals("Dolby Vision", videoColorLabel(format(C.COLOR_TRANSFER_ST2084, MimeTypes.VIDEO_DOLBY_VISION)))
        assertEquals("Dolby Vision", videoColorLabel(Format.Builder().setCodecs("dvhe.05.06").build()))
        assertEquals("HDR10 · PQ", videoColorLabel(format(C.COLOR_TRANSFER_ST2084)))
        assertEquals("HDR · HLG", videoColorLabel(format(C.COLOR_TRANSFER_HLG)))
        assertEquals("Color not specified", videoColorLabel(Format.Builder().setWidth(3840).setHeight(2160).build()))
    }

    @Test fun realPlayerFrameCallbackPublishesColorChangesAndRejectsQueuedMetadataAfterClose() {
        val native = mock(ExoPlayer::class.java)
        lateinit var metadata: VideoFrameMetadataListener
        doAnswer { metadata = it.getArgument(0); null }.`when`(native).setVideoFrameMetadataListener(any())
        val screen = ScreenPlayer(RuntimeEnvironment.getApplication()) { _, _, _ -> native }
        screen.attach(mock(Surface::class.java))
        screen.play(Playable("Fixture", "https://example.invalid/movie", "https://example.invalid/stream", emptyMap()))
        metadata.onVideoFrameAboutToBeRendered(0, 0, format(C.COLOR_TRANSFER_ST2084, MimeTypes.VIDEO_DOLBY_VISION), null)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Dolby Vision", screen.transport.value.videoColor)
        metadata.onVideoFrameAboutToBeRendered(0, 0, format(C.COLOR_TRANSFER_SDR), null)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("SDR · Rec.2020", screen.transport.value.videoColor)
        metadata.onVideoFrameAboutToBeRendered(0, 0, format(C.COLOR_TRANSFER_HLG), null)
        screen.close()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("", screen.transport.value.videoColor)
        screen.release()
    }
}
