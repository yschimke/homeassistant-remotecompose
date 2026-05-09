package ee.schimke.terrazzo.widget

import android.appwidget.AppWidgetManager
import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Preview(name = "widget sizing · normal host", showBackground = true, widthDp = 260, heightDp = 180)
@Preview(name = "widget sizing · forced large host", showBackground = true, widthDp = 420, heightDp = 260)
@Composable
fun WidgetSizingPreview() {
    val smallHost = sizingFrom(minWidth = 120, maxWidth = 200, minHeight = 60, maxHeight = 90)
    val largeHost = sizingFrom(minWidth = 240, maxWidth = 380, minHeight = 120, maxHeight = 220)

    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SizingRow(label = "Normal host", size = smallHost)
        SizingRow(label = "Forced larger host", size = largeHost)
    }
}

@Composable
private fun SizingRow(label: String, size: WidgetSizeDp) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "$label → ${size.widthDp}dp × ${size.heightDp}dp",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(12.dp),
        )
    }
}

private fun sizingFrom(minWidth: Int, maxWidth: Int, minHeight: Int, maxHeight: Int): WidgetSizeDp {
    val options = Bundle().apply {
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, minWidth)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, maxWidth)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, minHeight)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, maxHeight)
    }
    return WidgetSizing.targetSizeFromOptions(options, cardHeightDp = 999)
}
