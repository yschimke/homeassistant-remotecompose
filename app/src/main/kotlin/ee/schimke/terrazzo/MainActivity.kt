package ee.schimke.terrazzo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import ee.schimke.terrazzo.ui.TerrazzoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as TerrazzoApplication).graph
        setContent {
            CompositionLocalProvider(LocalTerrazzoGraph provides graph) {
                TerrazzoTheme {
                    TerrazzoApp()
                }
            }
        }
    }
}
