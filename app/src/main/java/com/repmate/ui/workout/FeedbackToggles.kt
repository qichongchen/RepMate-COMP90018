package com.repmate.ui.workout

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.repmate.ui.theme.RepMateTheme

/**
 * The "buzz on rep" and "spoken count" switches shown on both workout screens, so a user can set
 * them before a set and then lock the screen and pocket the phone.
 *
 * They are bound to the same global settings as Profile's toggles (see
 * [LiveWorkoutViewModel.onHapticFeedbackToggled] and friends), not per-session overrides, so a
 * change here shows up on Profile and the reverse.
 *
 * Each switch is a chip with its label underneath. On is a filled [tint] disc with a contrasting
 * icon; off is an outlined, dimmed disc -- a shape change, not just a colour change, so the state
 * reads at a glance and to anyone who cannot tell the two colours apart. The whole chip-and-label
 * column is the touch target (chip alone is already 56 dp, above the 48 dp minimum), so it is easy
 * to hit with one thumb at arm's length.
 *
 * @param tint the base colour for the fill, outline and label. `LiveWorkoutScreen` passes a theme
 *   colour; the push-up screen passes white so it stays legible over the camera preview.
 * @param enabled false while the screen is locked (see `ScreenLockOverlay`): the switches ignore
 *   touches and are drawn dimmed, but still show the current state so they are correct when
 *   unlocked. The lock scrim already absorbs pointer taps; this additionally stops an
 *   accessibility service from activating them underneath it.
 */
@Composable
internal fun FeedbackToggles(
    hapticEnabled: Boolean,
    spokenEnabled: Boolean,
    onHapticToggled: (Boolean) -> Unit,
    onSpokenToggled: (Boolean) -> Unit,
    enabled: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        FeedbackToggle(
            icon = Icons.Filled.Vibration,
            label = "buzz on rep",
            description = "Buzz on each rep",
            checked = hapticEnabled,
            onCheckedChange = onHapticToggled,
            enabled = enabled,
            tint = tint,
        )
        FeedbackToggle(
            icon = Icons.Filled.RecordVoiceOver,
            label = "spoken count",
            description = "Spoken rep count",
            checked = spokenEnabled,
            onCheckedChange = onSpokenToggled,
            enabled = enabled,
            tint = tint,
        )
    }
}

@Composable
private fun FeedbackToggle(
    icon: ImageVector,
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    // Icon colour that stays readable on whatever fill tint is: black on a light tint, white on a dark one.
    val onTint = if (tint.luminance() > 0.5f) Color.Black else Color.White
    val iconColor = if (checked) onTint else tint.copy(alpha = 0.7f)
    val labelColor = if (checked) tint else tint.copy(alpha = 0.7f)

    Column(
        modifier =
            modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .clip(RoundedCornerShape(12.dp))
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                ).semantics {
                    contentDescription = description
                    stateDescription = if (checked) "On" else "Off"
                }.padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier =
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .then(
                        if (checked) {
                            Modifier.background(tint, CircleShape)
                        } else {
                            Modifier.border(2.dp, tint.copy(alpha = 0.5f), CircleShape)
                        },
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(28.dp))
        }
        Spacer(modifier = Modifier.height(4.dp))
        // Cleared so a screen reader says the switch's description once, not the label as well.
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = labelColor,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

private const val DISABLED_ALPHA = 0.4f

@Composable
private fun FeedbackTogglesPreviewBody(
    hapticEnabled: Boolean,
    spokenEnabled: Boolean,
    enabled: Boolean = true,
) {
    Box(modifier = Modifier.background(MaterialTheme.colorScheme.background).padding(24.dp)) {
        FeedbackToggles(
            hapticEnabled = hapticEnabled,
            spokenEnabled = spokenEnabled,
            onHapticToggled = {},
            onSpokenToggled = {},
            enabled = enabled,
            tint = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Preview(name = "Both on", showBackground = true)
@Composable
private fun FeedbackTogglesBothOnPreview() {
    RepMateTheme(darkTheme = true) { FeedbackTogglesPreviewBody(hapticEnabled = true, spokenEnabled = true) }
}

@Preview(name = "Both off", showBackground = true)
@Composable
private fun FeedbackTogglesBothOffPreview() {
    RepMateTheme(darkTheme = true) { FeedbackTogglesPreviewBody(hapticEnabled = false, spokenEnabled = false) }
}

@Preview(name = "Buzz on, spoken off", showBackground = true)
@Composable
private fun FeedbackTogglesOneOnPreview() {
    RepMateTheme(darkTheme = true) { FeedbackTogglesPreviewBody(hapticEnabled = true, spokenEnabled = false) }
}

@Preview(name = "Locked (disabled), buzz on", showBackground = true)
@Composable
private fun FeedbackTogglesDisabledPreview() {
    RepMateTheme(darkTheme = true) {
        FeedbackTogglesPreviewBody(hapticEnabled = true, spokenEnabled = false, enabled = false)
    }
}

@Preview(name = "Both on - light theme", showBackground = true)
@Composable
private fun FeedbackTogglesLightPreview() {
    RepMateTheme(darkTheme = false) { FeedbackTogglesPreviewBody(hapticEnabled = true, spokenEnabled = true) }
}
