package com.example.repmate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.repmate.ui.navigation.RepMateNavGraph
import com.repmate.ui.theme.RepMateTheme
import com.repmate.ui.theme.ThemePreferences
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var themePreferences: ThemePreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Collected here, at the theme root, rather than read once: this is what makes
            // Profile's "Dark theme" toggle recompose the whole app immediately, no restart
            // needed -- DataStore's Flow emits again the moment ThemePreferences.setDarkThemeEnabled
            // writes, and this collection is what turns that into a recomposition.
            val isDarkTheme by themePreferences.isDarkThemeEnabled.collectAsStateWithLifecycle(initialValue = true)
            RepMateTheme(darkTheme = isDarkTheme) {
                RepMateNavGraph()
            }
        }
    }
}