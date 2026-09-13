package com.repmate.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.repmate.ui.theme.RepMateTheme

/**
 * A bordered, rounded container for grouping content -- a stats row, a settings section, a list
 * of items. Sizes to the width it's given and to its content's height rather than a fixed dp
 * box, so it drops into any screen layout without per-screen tuning.
 *
 * @param content the card body, laid out in a [Column] with standard padding already applied.
 */
@Composable
fun RepMateCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Preview(name = "Light", showBackground = true, widthDp = 360)
@Composable
private fun RepMateCardLightPreview() {
    RepMateTheme(darkTheme = false) {
        RepMateCard(modifier = Modifier.padding(16.dp)) {
            Text("This week", style = MaterialTheme.typography.titleLarge)
            Text("12 workouts · 148 reps", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Preview(name = "Dark", showBackground = true, widthDp = 360)
@Composable
private fun RepMateCardDarkPreview() {
    RepMateTheme(darkTheme = true) {
        RepMateCard(modifier = Modifier.padding(16.dp)) {
            Text("This week", style = MaterialTheme.typography.titleLarge)
            Text("12 workouts · 148 reps", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
