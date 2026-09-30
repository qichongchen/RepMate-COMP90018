package com.example.repmate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.repmate.ui.navigation.RepMateNavGraph
import com.repmate.ui.navigation.StartupViewModel
import com.repmate.ui.theme.RepMateTheme
import com.repmate.ui.theme.ThemePreferences
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var themePreferences: ThemePreferences

    private val startupViewModel: StartupViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before super.onCreate: it swaps the launch theme (Theme.RepMate.Splash) for the
        // real one. The splash then stays up until StartupViewModel has resolved where to start, so
        // a signed-in user never sees Welcome flash by.
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition { startupViewModel.startDestination.value == null }
        enableEdgeToEdge()
        setContent {
            // Collected here, at the theme root, rather than read once: this is what makes
            // Profile's "Dark theme" toggle recompose the whole app immediately, no restart
            // needed -- DataStore's Flow emits again the moment ThemePreferences.setDarkThemeEnabled
            // writes, and this collection is what turns that into a recomposition.
            val isDarkTheme by themePreferences.isDarkThemeEnabled.collectAsStateWithLifecycle(initialValue = true)
            val startDestination by startupViewModel.startDestination.collectAsStateWithLifecycle()
            RepMateTheme(darkTheme = isDarkTheme) {
                // Not composed until resolved: the nav graph's start destination is fixed at first
                // composition, and the splash is still covering this whole time.
                startDestination?.let { RepMateNavGraph(startDestination = it) }
            }
        }
    }
}
