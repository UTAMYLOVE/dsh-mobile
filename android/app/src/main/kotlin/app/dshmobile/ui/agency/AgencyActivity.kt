package app.dshmobile.ui.agency

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.dshmobile.DshMobileApp
import app.dshmobile.ui.kit.SakuraTheme

/**
 * The launcher (arch 1.13): 小雨娛樂. Opens the company ([app.dshmobile.agency.AgencyApp.start], off the main thread) and shows
 * the start screens, then 公司 / 指揮室 / 設定. Nothing of dsh is started from here.
 */
class AgencyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val agency = (application as DshMobileApp).agency
        agency.start()
        setContent { SakuraTheme { AgencyRoot(agency) } }
    }
}
