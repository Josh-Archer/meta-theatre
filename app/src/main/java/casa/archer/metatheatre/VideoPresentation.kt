package casa.archer.metatheatre

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.meta.spatial.runtime.AlphaMode
import com.meta.spatial.runtime.BlendFactor
import com.meta.spatial.runtime.LayerAlphaBlend
import com.meta.spatial.runtime.PanelConfigOptions
import com.meta.spatial.runtime.PanelShapeLayerBlendType
import com.meta.spatial.runtime.SceneMaterial

/** A movie copies opaque pixels into its layer; only the scene cutout uses hole-punch alpha. */
internal object VideoPresentation {
    fun configure(options: PanelConfigOptions) {
        options.alphaMode = AlphaMode.HOLE_PUNCH
        options.panelShader = SceneMaterial.HOLE_PUNCH_PANEL_SHADER
        options.enableTransparent = false
        options.layerBlendType = PanelShapeLayerBlendType.OPAQUE
        options.layerConfig?.alphaBlend = LayerAlphaBlend(BlendFactor.ONE, BlendFactor.ZERO, BlendFactor.ONE, BlendFactor.ZERO)
    }
}

/** Describes the selected input, not an unmeasured claim about the headset's display output. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun videoColorLabel(format: Format): String {
    val codecs = format.codecs.orEmpty()
    if (format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION ||
        codecs.split(',').any { it.trim().startsWith("dvhe.") || it.trim().startsWith("dvh1.") || it.trim().startsWith("dav1.") }) {
        return "Dolby Vision"
    }
    return when (format.colorInfo?.colorTransfer) {
        C.COLOR_TRANSFER_ST2084 -> "HDR10 · PQ"
        C.COLOR_TRANSFER_HLG -> "HDR · HLG"
        C.COLOR_TRANSFER_SDR -> when (format.colorInfo?.colorSpace) {
            C.COLOR_SPACE_BT709 -> "SDR · Rec.709"
            C.COLOR_SPACE_BT601 -> "SDR · Rec.601"
            C.COLOR_SPACE_BT2020 -> "SDR · Rec.2020"
            else -> "SDR"
        }
        else -> "Color not specified"
    }
}
