package com.repmate.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.repmate.ui.theme.RepMateTheme

/** Visual treatment for [RepMateButton]. */
enum class RepMateButtonVariant {
    /** Filled background in the primary color -- the single primary action on a screen ("Sign up", "Next"). */
    Solid,

    /** Outlined border, transparent background, primary-colored text -- secondary/retry actions ("Try again"). */
    Ghost,
}

/** Where [RepMateButton] places its optional [ImageVector] relative to the label text. */
enum class RepMateIconPosition {
    /** Icon before the text, e.g. a back arrow on "Cancel". */
    Leading,

    /** Icon after the text, e.g. a chevron on "Next ›" -- the common case for forward navigation. */
    Trailing,
}

/**
 * A full-width call-to-action button, used for primary actions like "Sign up", "Next", or
 * "Try again".
 *
 * The button always fills the width it's given rather than sizing off a fixed dp value, so the
 * same call site looks right on a 360dp phone and a 430dp phone alike -- callers control overall
 * placement with [modifier] (e.g. horizontal padding), not a hardcoded width.
 *
 * @param text label shown on the button.
 * @param onClick invoked on tap.
 * @param variant [RepMateButtonVariant.Solid] or [RepMateButtonVariant.Ghost].
 * @param enabled whether the button responds to taps.
 * @param icon optional icon, e.g. a chevron on a "Next" button.
 * @param iconPosition where [icon] sits relative to [text]; defaults to leading.
 */
@Composable
fun RepMateButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: RepMateButtonVariant = RepMateButtonVariant.Solid,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    iconPosition: RepMateIconPosition = RepMateIconPosition.Leading,
) {
    val shape = MaterialTheme.shapes.medium
    val contentPadding = PaddingValues(vertical = 14.dp, horizontal = 24.dp)

    when (variant) {
        RepMateButtonVariant.Solid ->
            Button(
                onClick = onClick,
                modifier = modifier.fillMaxWidth(),
                enabled = enabled,
                shape = shape,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                contentPadding = contentPadding,
            ) {
                RepMateButtonLabel(text, icon, iconPosition)
            }

        RepMateButtonVariant.Ghost ->
            OutlinedButton(
                onClick = onClick,
                modifier = modifier.fillMaxWidth(),
                enabled = enabled,
                shape = shape,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                contentPadding = contentPadding,
            ) {
                RepMateButtonLabel(text, icon, iconPosition)
            }
    }
}

@Composable
private fun RepMateButtonLabel(
    text: String,
    icon: ImageVector?,
    iconPosition: RepMateIconPosition,
) {
    if (icon != null && iconPosition == RepMateIconPosition.Leading) {
        Icon(icon, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
    }
    Text(text)
    if (icon != null && iconPosition == RepMateIconPosition.Trailing) {
        Spacer(modifier = Modifier.width(8.dp))
        Icon(icon, contentDescription = null)
    }
}

@Preview(name = "Solid - Light", showBackground = true, widthDp = 360)
@Composable
private fun RepMateButtonSolidLightPreview() {
    RepMateTheme(darkTheme = false) {
        RepMateButton(text = "Sign up", onClick = {}, modifier = Modifier.padding(16.dp))
    }
}

@Preview(name = "Solid - Dark", showBackground = true, widthDp = 360)
@Composable
private fun RepMateButtonSolidDarkPreview() {
    RepMateTheme(darkTheme = true) {
        RepMateButton(
            text = "Next",
            onClick = {},
            icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            iconPosition = RepMateIconPosition.Trailing,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(name = "Ghost - Light", showBackground = true, widthDp = 360)
@Composable
private fun RepMateButtonGhostLightPreview() {
    RepMateTheme(darkTheme = false) {
        RepMateButton(
            text = "Try again",
            onClick = {},
            variant = RepMateButtonVariant.Ghost,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(name = "Ghost - Dark", showBackground = true, widthDp = 360)
@Composable
private fun RepMateButtonGhostDarkPreview() {
    RepMateTheme(darkTheme = true) {
        RepMateButton(
            text = "Try again",
            onClick = {},
            variant = RepMateButtonVariant.Ghost,
            modifier = Modifier.padding(16.dp),
        )
    }
}
