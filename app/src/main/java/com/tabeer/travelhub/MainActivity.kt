package com.tabeer.travelhub

import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import com.tabeer.travelhub.ui.*

/** FragmentActivity (a ComponentActivity) so BiometricPrompt can attach to it. */
class MainActivity : FragmentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { TravelHubApp(vm, this) }
    }

    override fun onStart() {
        super.onStart()
        vm.onForegrounded()
    }

    override fun onStop() {
        vm.onBackgrounded()
        super.onStop()
    }
}

@Composable
fun TravelHubApp(vm: AppViewModel, activity: FragmentActivity) {
    val context = LocalContext.current

    TravelHubTheme(vm.settings.theme) {
        // FLAG_SECURE blocks screenshots and blanks the recent-apps thumbnail.
        SideEffect {
            if (vm.settings.blockScreenshots) activity.window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
            else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        LaunchedEffect(vm.message) {
            vm.message?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); vm.message = null }
        }

        if (vm.locked) {
            LockScreen(vm, activity)
        } else {
            BackHandler(enabled = vm.screen != Screen.Dashboard && vm.screen !is Screen.Browser) { vm.screen = Screen.Dashboard }
            when (val s = vm.screen) {
                Screen.Dashboard -> DashboardScreen(vm)
                is Screen.Form -> FormScreen(vm, s.portalId)
                is Screen.Browser -> {
                    val p = vm.portal(s.portalId)
                    if (p == null) vm.screen = Screen.Dashboard else BrowserScreen(vm, p)
                }
                Screen.Settings -> SettingsScreen(vm, activity)
            }
        }
    }
}
