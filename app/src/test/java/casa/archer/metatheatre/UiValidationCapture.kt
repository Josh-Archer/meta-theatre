package casa.archer.metatheatre

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import java.io.File

/** Optional native-rendered previews use test fixtures only, never Plex or headset data. */
internal fun captureUiFixture(activity: Activity, filename: String) {
    val directory = System.getenv("THEATRE_UI_CAPTURE")?.let(::File) ?: return
    val view = activity.findViewById<View>(android.R.id.content)
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    view.draw(Canvas(bitmap))
    directory.mkdirs()
    File(directory, filename).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bitmap.recycle()
}
