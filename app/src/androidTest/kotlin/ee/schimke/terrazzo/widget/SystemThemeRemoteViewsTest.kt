@file:Suppress("RestrictedApi")

package ee.schimke.terrazzo.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.compose.remote.core.operations.layout.managers.BoxLayout
import androidx.compose.remote.creation.Rc
import androidx.compose.remote.creation.RemoteComposeWriter
import androidx.compose.remote.creation.modifiers.RecordingModifier
import androidx.compose.remote.creation.profile.RcPlatformProfiles
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemThemeRemoteViewsTest {

  @Test
  @SdkSuppress(minSdkVersion = 37)
  fun colorTheme_coldStart_lightTheme_resolvesSystemColors() {
    val rcDoc = RemoteComposeWriter.obtain(100, 100, RcPlatformProfiles.ANDROIDX)
    val fallbackColor = 0xFFFF00FF.toInt()
    val colorId =
      rcDoc.addThemedColor(
        Rc.AndroidColors.GROUP,
        Rc.AndroidColors.SYSTEM_ACCENT2_50,
        Rc.AndroidColors.SYSTEM_ACCENT2_800,
        fallbackColor,
        fallbackColor,
      )
    rcDoc.root {
      rcDoc.box(
        RecordingModifier().backgroundId(colorId).fillMaxSize(),
        BoxLayout.CENTER,
        BoxLayout.CENTER,
      ) {}
    }

    val base = ApplicationProvider.getApplicationContext<Context>()
    val lightContext =
      base.createConfigurationContext(
        Configuration(base.resources.configuration).apply {
          uiMode =
            (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
        }
      )
    val parent = FrameLayout(lightContext)
    val remoteViews =
      RemoteViews(RemoteViews.DrawInstructions.Builder(listOf(rcDoc.encodeToByteArray())).build())
    val rendered = remoteViews.apply(lightContext, parent)
    parent.addView(rendered, FrameLayout.LayoutParams(100, 100))

    parent.measure(
      View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
      View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
    )
    parent.layout(0, 0, 100, 100)
    val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
    parent.draw(Canvas(bitmap))

    assertNotNull(rendered)
    assertNotEquals("system color fell back to magenta", fallbackColor, bitmap.getPixel(50, 50))
    assertNotEquals("system color rendered transparent", Color.TRANSPARENT, bitmap.getPixel(50, 50))
  }
}
