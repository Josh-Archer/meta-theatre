package casa.archer.metatheatre

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Same normalized coordinates as ray selection; draws after the UI so panels cannot hide it. */
@Composable
internal fun PanelReticle(model: TheatreModel, panel: PointerPanel, enabled: Boolean) {
    val cursor by model.panelCursor.collectAsState()
    val point=cursor?.takeIf{enabled && it.panel==panel && it.x.isFinite() && it.y.isFinite() && it.x in 0f..1f && it.y in 0f..1f} ?: return
    Canvas(Modifier.fillMaxSize().semantics{contentDescription=if(panel==PointerPanel.PLAYER) "Side menu pointer" else "Video selector pointer"}) {
        val center=Offset(point.x*size.width,point.y*size.height)
        drawCircle(Color.Black,radius=12.dp.toPx(),center=center,style=Stroke(6.dp.toPx()))
        drawCircle(Color.White,radius=12.dp.toPx(),center=center,style=Stroke(3.dp.toPx()))
        drawCircle(Color(0xFFFFD980),radius=4.dp.toPx(),center=center)
    }
}
