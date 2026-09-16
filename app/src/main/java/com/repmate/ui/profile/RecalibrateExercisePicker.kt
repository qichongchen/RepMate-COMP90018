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
import com.repmate.ui.theme.RepMateTheme

/**
 * A small dialog listing the three exercises, shown when Profile's "Recalibrate" row is tapped --
 * recalibration needs to know which exercise before navigating anywhere, and Profile itself has no
 * notion of "the current exercise" the way Home's exercise chips do. Doesn't need its own nav
 * route: the caller (`NavGraph.kt`) owns showing/hiding it and decides where [onExerciseSelected]
 * actually navigates.
 */
@Composable
fun RecalibrateExercisePicker(
    onExerciseSelected: (ExerciseType) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        title = { Text("Recalibrate which exercise?") },
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

private fun ExerciseType.displayLabel(): String =
    when (this) {
        ExerciseType.SQUAT -> "Squat"
        ExerciseType.PUSHUP -> "Push-up"
        ExerciseType.JUMPING_JACK -> "Jumping Jack"
    }

@Preview(name = "Light", showBackground = true, widthDp = 360)
@Composable
private fun RecalibrateExercisePickerLightPreview() {
    RepMateTheme(darkTheme = false) {
        RecalibrateExercisePicker(onExerciseSelected = {}, onDismissRequest = {})
    }
}

@Preview(name = "Dark", showBackground = true, widthDp = 360)
@Composable
private fun RecalibrateExercisePickerDarkPreview() {
    RepMateTheme(darkTheme = true) {
        RecalibrateExercisePicker(onExerciseSelected = {}, onDismissRequest = {})
    }
}
