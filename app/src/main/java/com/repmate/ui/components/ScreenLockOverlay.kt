package com.repmate.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import com.repmate.ui.theme.RepMateTheme

/**
 * Pocket-safety lock for the active-workout screens. While [screenLocked] is true, a dimmed scrim
 * covers [content] and absorbs every tap, and system back (button and gesture) is swallowed, so a
 * phone in a pocket can neither press a button nor back out of an in-progress workout. The only
 * thing still tappable is the unlock icon, which sits above the scrim at exactly the spot the lock
 * icon occupies in the screen's own bottom row. One tap unlocks -- no confirm step, since nobody
 * is looking at the screen while it's locked, so there are no controls to keep uncluttered.
 *
 * Blocking back is the more important half: a stray tap on "Pause" is recoverable, but backing
 * out of the screen loses the session.
 *
 * ## How the icon stays in place
 * [content] places the lock icon in its own layout via [ScreenLockScope.ScreenLockButton], so the
 * row it sits in lays out the same whether locked or not. That in-row copy reports where it landed
 * on screen; while locked, it's made invisible (still taking up its slot, so nothing reflows) and
 * a full-opacity copy showing the unlock glyph is drawn at the same position on top of the scrim.
 * Drawing it twice is needed because in Compose a child of [content] can't be drawn above a
 * sibling of [content] (the scrim), however it's ordered inside its own parent.
 *
 * Lock state is deliberately not here, or in any ViewModel: it's ephemeral UI state the caller
 * holds with `remember`, so every visit to a workout screen starts unlocked.
 *
 * @param scrimColor base color of the scrim; [SCRIM_ALPHA] is applied here so both screens dim
 *   by the same amount. Taken as a parameter because the two screens color things differently
 *   (`LiveWorkoutScreen` themes off `MaterialTheme.colorScheme`, `PushupWorkoutScreen` hardcodes
 *   white/black to stay legible over the camera preview).
 * @param iconTint tint of the lock/unlock glyph.
 * @param outlineColor the icon button's circular outline, matching `LiveWorkoutScreen`'s
 *   `PauseResumeButton` treatment.
 */
@Composable
fun ScreenLockOverlay(
    screenLocked: Boolean,
    onScreenLockToggled: () -> Unit,
    scrimColor: Color,
    iconTint: Color,
    outlineColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable ScreenLockScope.() -> Unit,
) {
    BackHandler(enabled = screenLocked) { }

    // Both in root coordinates, so their difference is the button's offset inside this Box
    // regardless of what padding or insets sit between the two.
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    var buttonOrigin by remember { mutableStateOf<Offset?>(null) }

    val scope =
        ScreenLockScope(
            screenLocked = screenLocked,
            onScreenLockToggled = onScreenLockToggled,
            iconTint = iconTint,
            outlineColor = outlineColor,
            onButtonPositioned = { buttonOrigin = it },
        )

    Box(modifier = modifier.onGloballyPositioned { overlayOrigin = it.positionInRoot() }) {
        scope.content()

        if (screenLocked) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(scrimColor.copy(alpha = SCRIM_ALPHA))
                        // Background alone lets taps fall through -- this no-op clickable is what
                        // actually absorbs them. Same trick as LoadingOverlay.
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        ),
            )
            buttonOrigin?.let { origin ->
                LockIconButton(
                    screenLocked = true,
                    onClick = onScreenLockToggled,
                    iconTint = iconTint,
                    outlineColor = outlineColor,
                    modifier = Modifier.offset { (origin - overlayOrigin).round() },
                )
            }
        }
    }
}

/**
 * Receiver scope for [ScreenLockOverlay]'s content, giving it the one composable it needs to place:
 * the lock button, wherever the screen's own layout wants it.
 */
// NOTE: kept in this file rather than its own, despite the one-class-per-file convention -- it's
// only meaningful as ScreenLockOverlay's receiver, the same way BoxScope sits beside Box.
class ScreenLockScope internal constructor(
    private val screenLocked: Boolean,
    private val onScreenLockToggled: () -> Unit,
    private val iconTint: Color,
    private val outlineColor: Color,
    private val onButtonPositioned: (Offset) -> Unit,
) {
    /**
     * The in-row lock button. Always laid out, so its slot is reserved whether locked or not;
     * invisible while locked, because [ScreenLockOverlay] draws the tappable unlock copy above the
     * scrim in exactly this spot.
     */
    @Composable
    fun ScreenLockButton(modifier: Modifier = Modifier) {
        LockIconButton(
            screenLocked = screenLocked,
            onClick = onScreenLockToggled,
            iconTint = iconTint,
            outlineColor = outlineColor,
            modifier =
                modifier
                    .onGloballyPositioned { onButtonPositioned(it.positionInRoot()) }
                    .alpha(if (screenLocked) 0f else 1f),
        )
    }
}

/** Small circular outline icon button, same treatment as `LiveWorkoutScreen`'s `PauseResumeButton`. */
@Composable
private fun LockIconButton(
    screenLocked: Boolean,
    onClick: () -> Unit,
    iconTint: Color,
    outlineColor: Color,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier =
            modifier
                .size(56.dp)
                .clip(CircleShape)
                .border(1.dp, outlineColor, CircleShape),
    ) {
        Icon(
            imageVector = if (screenLocked) Icons.Filled.LockOpen else Icons.Filled.Lock,
            contentDescription = if (screenLocked) "Unlock screen" else "Lock screen",
            tint = iconTint,
        )
    }
}

// NOTE: 35% is dim enough to read as "locked" at a glance, while the rep count underneath stays
// readable -- the point is to block taps, not to hide the workout.
private const val SCRIM_ALPHA = 0.35f

@Composable
private fun ScreenLockOverlayPreviewBody(screenLocked: Boolean) {
    ScreenLockOverlay(
        screenLocked = screenLocked,
        onScreenLockToggled = {},
        scrimColor = MaterialTheme.colorScheme.scrim,
        iconTint = MaterialTheme.colorScheme.onBackground,
        outlineColor = MaterialTheme.colorScheme.outline,
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("12", style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.onBackground)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RepMateButton(text = "End workout", onClick = {}, modifier = Modifier.weight(1f))
                ScreenLockButton()
            }
        }
    }
}

@Preview(name = "Unlocked", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun ScreenLockOverlayUnlockedPreview() {
    RepMateTheme(darkTheme = true) { ScreenLockOverlayPreviewBody(screenLocked = false) }
}

@Preview(name = "Locked", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun ScreenLockOverlayLockedPreview() {
    RepMateTheme(darkTheme = true) { ScreenLockOverlayPreviewBody(screenLocked = true) }
}
