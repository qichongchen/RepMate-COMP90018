package com.repmate.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.example.repmate.R
import com.repmate.engine.ExerciseType

/**
 * The one place [ExerciseType] is turned into the label shown on screen -- previously copied
 * verbatim (as a private function) into HomeScreen, LiveWorkoutScreen, and MotionReplayScreen;
 * pulled out here so History/SessionDetail can reuse it instead of adding a fourth copy.
 */
fun ExerciseType.displayLabel(): String =
    when (this) {
        ExerciseType.SQUAT -> "Squat"
        ExerciseType.PUSHUP -> "Push-up"
        ExerciseType.JUMPING_JACK -> "Jumping Jack"
    }

/**
 * The per-exercise icon, alongside [displayLabel] for the same reason: one mapping every exercise
 * picker shares, instead of each screen choosing its own.
 *
 * `@Composable` because [ImageVector.Companion.vectorResource] needs the current resources to
 * inflate the drawable (and caches it per composition). The drawables are single-color black
 * silhouettes, so callers tint them through `Icon` like any Material icon.
 */
@Composable
fun ExerciseType.icon(): ImageVector =
    when (this) {
        ExerciseType.SQUAT -> ImageVector.vectorResource(id = R.drawable.ic_squat)
        ExerciseType.PUSHUP -> ImageVector.vectorResource(id = R.drawable.ic_push_up)
        ExerciseType.JUMPING_JACK -> ImageVector.vectorResource(id = R.drawable.ic_jumping_jack)
    }
