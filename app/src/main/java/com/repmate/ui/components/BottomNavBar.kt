package com.repmate.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import com.repmate.ui.theme.RepMateTheme

/** The four top-level destinations reachable from [BottomNavBar]. */
enum class BottomNavItem(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Filled.Home),
    History("History", Icons.Filled.History),

    /** [EmojiEvents] (a trophy) stands in for the podium/leaderboard destination. */
    Leaderboard("Leaderboard", Icons.Filled.EmojiEvents),
    Profile("Profile", Icons.Filled.Person),
}

/**
 * The app's bottom navigation bar: Home, History, Leaderboard, Profile.
 *
 * Built on Material 3's [NavigationBar], which spaces its items evenly across whatever width
 * it's given and sizes its own height from content plus Material's standard bar padding -- so
 * no explicit width or height is set here, and the bar adapts to any phone screen on its own.
 *
 * @param activeItem the currently selected destination, highlighted in the primary color.
 * @param onItemSelected invoked with the tapped destination.
 */
@Composable
fun BottomNavBar(
    activeItem: BottomNavItem,
    onItemSelected: (BottomNavItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(
        modifier = modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        BottomNavItem.entries.forEach { item ->
            NavigationBarItem(
                selected = item == activeItem,
                onClick = { onItemSelected(item) },
                icon = { Icon(item.icon, contentDescription = item.label) },
                label = { Text(item.label) },
                colors =
                    NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
            )
        }
    }
}

@Preview(name = "Light", showBackground = true, widthDp = 360)
@Composable
private fun BottomNavBarLightPreview() {
    RepMateTheme(darkTheme = false) {
        BottomNavBar(activeItem = BottomNavItem.Home, onItemSelected = {})
    }
}

@Preview(name = "Dark", showBackground = true, widthDp = 360)
@Composable
private fun BottomNavBarDarkPreview() {
    RepMateTheme(darkTheme = true) {
        BottomNavBar(activeItem = BottomNavItem.Leaderboard, onItemSelected = {})
    }
}
