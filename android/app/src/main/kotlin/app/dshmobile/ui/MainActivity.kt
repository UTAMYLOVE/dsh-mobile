package app.dshmobile.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

/**
 * The only screen host. The bundle of UI-side interfaces comes from the application object when it provides one
 * ([UiGraphProvider]); otherwise the demo bundle is shown (previews, a build without the real wiring).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as? UiGraphProvider)?.uiGraph ?: demoGraph()
        setContent { UiRoot(graph) }
    }
}
