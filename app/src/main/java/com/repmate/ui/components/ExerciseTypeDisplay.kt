package com.repmate.ui.components

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
