package com.repmate.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.repmate.ui.theme.RepMateTheme

/**
 * The header every sub-screen uses: a bordered circular back button and a bold title.
 *
 * Deliberately **not** a Material `TopAppBar`. The app's screens draw their own header on the
 * ordinary background, so a `TopAppBar` brings its own surface colour and inset and reads as a
 * band from a different app -- which is exactly how the Friends screen looked before it used
 * this.
 *
 * The title carries an explicit [MaterialTheme.colorScheme.onBackground]: History once shipped
 * without one and the title was invisible in dark mode.
 *
 * Extracted from the identical private copies `GhostDuelTopBar` and `SessionDetailTopBar` had,
 * so a fourth screen cannot quietly drift from the other three.
 *
 * @param actions optional trailing content, e.g. Friends' "add" button.
 */
@Composable
fun RepMateTopBar(
    title: String,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconButton(
            onClick = onBackClick,
            modifier =
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

@Preview(name = "Light", showBackground = true, widthDp = 360)
@Composable
private fun RepMateTopBarLightPreview() {
    RepMateTheme(darkTheme = false) {
        RepMateTopBar(title = "Friends", onBackClick = {})
    }
}

@Preview(name = "Dark", showBackground = true, widthDp = 360)
@Composable
private fun RepMateTopBarDarkPreview() {
    RepMateTheme(darkTheme = true) {
        RepMateTopBar(title = "Ghost Duel", onBackClick = {})
    }
}
