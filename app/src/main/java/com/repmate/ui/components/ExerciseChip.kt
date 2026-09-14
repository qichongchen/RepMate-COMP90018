package com.repmate.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.repmate.ui.theme.RepMateTheme

/**
 * A full-width, tappable row representing one exercise on the Home screen's exercise picker --
 * an icon and label on the left, a play-triangle affordance on the right to start it.
 *
 * The whole row is tappable (not just the play icon) for a larger touch target, and its height
 * comes from content plus padding rather than a fixed dp value, so a longer exercise name still
 * wraps the row correctly instead of being clipped.
 *
 * @param label exercise name, e.g. "Squat".
 * @param onClick invoked on tap anywhere in the row.
 * @param icon icon representing the exercise; defaults to a generic dumbbell.
 */
@Composable
fun ExerciseChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Filled.FitnessCenter,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surface)
                .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.titleLarge)
        }
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = "Start $label",
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Preview(name = "Light", showBackground = true, widthDp = 360)
@Composable
private fun ExerciseChipLightPreview() {
    RepMateTheme(darkTheme = false) {
        ExerciseChip(label = "Squat", onClick = {}, modifier = Modifier.padding(16.dp))
    }
}

@Preview(name = "Dark", showBackground = true, widthDp = 360)
@Composable
private fun ExerciseChipDarkPreview() {
    RepMateTheme(darkTheme = true) {
        ExerciseChip(label = "Push-up", onClick = {}, modifier = Modifier.padding(16.dp))
    }
}
