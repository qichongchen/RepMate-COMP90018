package com.repmate.ui.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.repmate.engine.ExerciseType
import com.repmate.ui.components.displayLabel
import com.repmate.ui.theme.RepMateTheme

/**
 * A small dialog listing all three exercises, shown when Profile's "How exercises work" row is
 * tapped; picking one opens that exercise's `ExerciseTutorialDialog`. Same shape as
 * [RecalibrateExercisePicker] (caller-owned show/hide, no nav route) but its own composable, since
 * the two lists aren't guaranteed to match: every exercise has a tutorial, including push-up,
 * which has no calibration.
 */
@Composable
fun ExerciseTutorialPicker(
    onExerciseSelected: (ExerciseType) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        title = { Text("How exercises work") },
        text = {
            Column {
                ExerciseType.entries.forEachIndexed { index, exerciseType ->
                    Text(
                        text = exerciseType.displayLabel(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onExerciseSelected(exerciseType) }
                                .padding(vertical = 12.dp),
                    )
                    if (index != ExerciseType.entries.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) { Text("Cancel") }
        },
    )
}

@Preview(name = "Light", showBackground = true, widthDp = 360)
@Composable
private fun ExerciseTutorialPickerLightPreview() {
    RepMateTheme(darkTheme = false) {
        ExerciseTutorialPicker(onExerciseSelected = {}, onDismissRequest = {})
    }
}

@Preview(name = "Dark", showBackground = true, widthDp = 360)
@Composable
private fun ExerciseTutorialPickerDarkPreview() {
    RepMateTheme(darkTheme = true) {
        ExerciseTutorialPicker(onExerciseSelected = {}, onDismissRequest = {})
    }
}
