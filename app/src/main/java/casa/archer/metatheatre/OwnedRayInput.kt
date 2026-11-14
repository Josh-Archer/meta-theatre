package casa.archer.metatheatre

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/** ScreenGrip dispatches the owning ray. Do not also accept ISDK's per-hand touch clicks. */
internal fun Modifier.ownedRayInput(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}
