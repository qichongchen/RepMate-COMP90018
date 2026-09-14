package com.repmate.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * A stand-in for a screen that hasn't been built yet: centered text naming the route it
 * represents. Lets the navigation graph be wired up, run, and clicked through end to end before
 * any real screen exists -- replace each call site with the real screen as it's built.
 *
 * @param label text to show, typically the route string (e.g. "home", "calibration/SQUAT").
 */
@Composable
fun PlaceholderScreen(
    label: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.titleLarge)
    }
}
