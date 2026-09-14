package com.repmate.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.repmate.engine.ExerciseType
import com.repmate.ui.components.BottomNavBar
import com.repmate.ui.components.BottomNavItem
import com.repmate.ui.components.RepMateButton
import com.repmate.ui.components.RepMateButtonVariant
import com.repmate.ui.theme.RepMateTheme

/**
 * Every route RepMate navigates between, plus the small helpers for building/parsing the ones
 * that carry an argument. Kept as plain string constants (not a sealed class of typed objects)
 * because that's exactly what [androidx.navigation.compose.NavHost] and [composable] consume --
 * a typed wrapper would just be translated back to these same strings at the call site.
 */
object RepMateDestinations {
    const val WELCOME = "welcome"
    const val SIGNUP = "signup"
    const val LOGIN = "login"
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val HISTORY = "history"
    const val LEADERBOARD = "leaderboard"
    const val PROFILE = "profile"

    const val ARG_EXERCISE_TYPE = "exerciseType"
    const val ARG_SESSION_ID = "sessionId"

    /** Route patterns for [NavHost]'s `composable(route = ...)` registration. */
    const val CALIBRATION = "calibration/{$ARG_EXERCISE_TYPE}"
    const val LIVE_WORKOUT = "live_workout/{$ARG_EXERCISE_TYPE}"
    const val MOTION_REPLAY = "motion_replay/{$ARG_SESSION_ID}"

    /** Concrete routes for [NavHostController.navigate] call sites. */
    fun calibration(exerciseType: ExerciseType) = "calibration/${exerciseType.name}"

    fun liveWorkout(exerciseType: ExerciseType) = "live_workout/${exerciseType.name}"

    fun motionReplay(sessionId: String) = "motion_replay/$sessionId"

    /** The destinations [BottomNavBar] switches between -- these are shown with the bar visible. */
    val BOTTOM_NAV_ROUTES = setOf(HOME, HISTORY, LEADERBOARD, PROFILE)
}

/**
 * Turns the raw `{exerciseType}` path argument back into an [ExerciseType].
 *
 * Falls back to [ExerciseType.SQUAT] for a missing or unrecognised value instead of throwing --
 * Golden Rule 7 (degrade gracefully): a malformed deep link or nav argument must never crash the
 * app, just fall back to something sensible.
 */
private fun parseExerciseType(raw: String?): ExerciseType = ExerciseType.entries.firstOrNull { it.name == raw } ?: ExerciseType.SQUAT

/** Maps a bottom-nav route back to the [BottomNavItem] it represents, for highlighting the active tab. */
private fun String?.toBottomNavItemOrNull(): BottomNavItem? =
    when (this) {
        RepMateDestinations.HOME -> BottomNavItem.Home
        RepMateDestinations.HISTORY -> BottomNavItem.History
        RepMateDestinations.LEADERBOARD -> BottomNavItem.Leaderboard
        RepMateDestinations.PROFILE -> BottomNavItem.Profile
        else -> null
    }

/**
 * RepMate's full navigation graph. Every destination below is a [PlaceholderScreen] (or a thin
 * wrapper around one) since no real screens exist yet -- this wires up the routes, the arguments
 * they carry, and the handful of navigation decisions that are already settled, so the graph can
 * be run and clicked through end to end while the real screens are built one at a time.
 *
 * The bottom nav bar is hosted here, in a [Scaffold] wrapping the [NavHost], rather than inside
 * each of home/history/leaderboard/profile individually -- it only shows for those four routes,
 * driven by the current back stack entry, so screens like welcome or live_workout don't get it.
 */
@Composable
fun RepMateNavGraph(
    navController: NavHostController = rememberNavController(),
    modifier: Modifier = Modifier,
) {
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route

    Scaffold(
        modifier = modifier,
        bottomBar = {
            if (currentRoute in RepMateDestinations.BOTTOM_NAV_ROUTES) {
                BottomNavBar(
                    activeItem = currentRoute.toBottomNavItemOrNull() ?: BottomNavItem.Home,
                    onItemSelected = { item ->
                        val route =
                            when (item) {
                                BottomNavItem.Home -> RepMateDestinations.HOME
                                BottomNavItem.History -> RepMateDestinations.HISTORY
                                BottomNavItem.Leaderboard -> RepMateDestinations.LEADERBOARD
                                BottomNavItem.Profile -> RepMateDestinations.PROFILE
                            }
                        navController.navigate(route) {
                            // Standard bottom-nav behaviour: don't stack a new copy of a tab the user
                            // is already on, and restore each tab's state when they switch back to it.
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = RepMateDestinations.WELCOME,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(RepMateDestinations.WELCOME) { PlaceholderScreen(RepMateDestinations.WELCOME) }
            composable(RepMateDestinations.SIGNUP) { PlaceholderScreen(RepMateDestinations.SIGNUP) }
            composable(RepMateDestinations.LOGIN) { PlaceholderScreen(RepMateDestinations.LOGIN) }

            composable(RepMateDestinations.ONBOARDING) {
                OnboardingPlaceholder(
                    onFinished = {
                        // NOTE: onboarding goes straight to home once its 3 explainer pages are done.
                        // Calibration is deliberately NOT triggered from here -- confirmed with the
                        // engine side that calibration should run lazily, the first time someone
                        // attempts an exercise with no profile yet (see the live_workout check below),
                        // not as a blanket step after onboarding. If that policy ever changes, this is
                        // the one line to edit.
                        navController.navigate(RepMateDestinations.HOME) {
                            popUpTo(RepMateDestinations.WELCOME) { inclusive = true }
                        }
                    },
                )
            }

            composable(RepMateDestinations.HOME) { PlaceholderScreen(RepMateDestinations.HOME) }
            composable(RepMateDestinations.HISTORY) { PlaceholderScreen(RepMateDestinations.HISTORY) }
            composable(RepMateDestinations.LEADERBOARD) { PlaceholderScreen(RepMateDestinations.LEADERBOARD) }
            composable(RepMateDestinations.PROFILE) { PlaceholderScreen(RepMateDestinations.PROFILE) }

            composable(
                route = RepMateDestinations.MOTION_REPLAY,
                arguments = listOf(navArgument(RepMateDestinations.ARG_SESSION_ID) { type = NavType.StringType }),
            ) { backStackEntry ->
                val sessionId = backStackEntry.arguments?.getString(RepMateDestinations.ARG_SESSION_ID).orEmpty()
                PlaceholderScreen("motion_replay/$sessionId")
            }

            composable(
                route = RepMateDestinations.CALIBRATION,
                arguments = listOf(navArgument(RepMateDestinations.ARG_EXERCISE_TYPE) { type = NavType.StringType }),
            ) { backStackEntry ->
                val exerciseType = parseExerciseType(backStackEntry.arguments?.getString(RepMateDestinations.ARG_EXERCISE_TYPE))
                // TODO(engine): jumping-jack calibration needs an audible metronome at ~52 BPM (one
                // beep per full jack) running for the whole calibration set -- a hard requirement from
                // Mohit on the engine side, not optional. Not implemented yet; wire it in when this
                // becomes a real screen, gated on exerciseType == ExerciseType.JUMPING_JACK. Squats
                // and push-ups don't need it.
                CalibrationPlaceholder(
                    exerciseType = exerciseType,
                    onDone = {
                        // Calibration is always pushed on top of whatever triggered it (currently only
                        // live_workout), so popping the back stack is "go to wherever this came from"
                        // -- home or the workout in progress -- with no return route to pass around.
                        // Skip and a completed calibration both land here for now since neither a real
                        // calibration UI nor profile storage exists yet.
                        navController.popBackStack()
                    },
                )
            }

            composable(
                route = RepMateDestinations.LIVE_WORKOUT,
                arguments = listOf(navArgument(RepMateDestinations.ARG_EXERCISE_TYPE) { type = NavType.StringType }),
            ) { backStackEntry ->
                val exerciseType = parseExerciseType(backStackEntry.arguments?.getString(RepMateDestinations.ARG_EXERCISE_TYPE))
                // TODO(engine): jumping jacks need an audible metronome at ~52 BPM (one beep per full
                // jack) for the whole set -- a hard requirement from Mohit on the engine side, not
                // optional. Not implemented yet; wire it in when this becomes a real screen, gated on
                // exerciseType == ExerciseType.JUMPING_JACK. Squats and push-ups don't need it.
                LiveWorkoutPlaceholder(exerciseType = exerciseType, navController = navController)
            }
        }
    }
}

@Composable
private fun OnboardingPlaceholder(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(RepMateDestinations.ONBOARDING, style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(24.dp))
        // Stands in for "the user swiped through all 3 explainer pages" until that flow is built.
        RepMateButton(text = "Continue", onClick = onFinished)
    }
}

@Composable
private fun CalibrationPlaceholder(
    exerciseType: ExerciseType,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(RepMateDestinations.calibration(exerciseType), style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(24.dp))
        // Calibration is optional -- skipping it just falls back to the detector's default
        // thresholds, per the engine team's spec, so skip and a (not yet built) completed
        // calibration both just leave this screen for now.
        RepMateButton(text = "Skip calibration", onClick = onDone, variant = RepMateButtonVariant.Ghost)
    }
}

@Composable
private fun LiveWorkoutPlaceholder(
    exerciseType: ExerciseType,
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    // TODO: replace with a real lookup once calibration profiles are persisted -- no Room/local
    // storage exists yet, so this always reports "not calibrated". That's intentional for now: it's
    // what exercises the redirect-to-calibration path below until the real check is wired in.
    val isCalibrated = false

    // rememberSaveable ties this flag to the back stack entry rather than just this composition, so
    // it survives calibration being pushed on top and then popped back off -- without it, popping
    // back here would recompose this screen from scratch and immediately redirect to calibration
    // again, forever.
    var hasCheckedCalibration by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!hasCheckedCalibration) {
            hasCheckedCalibration = true
            if (!isCalibrated) {
                navController.navigate(RepMateDestinations.calibration(exerciseType))
            }
        }
    }

    PlaceholderScreen(RepMateDestinations.liveWorkout(exerciseType), modifier = modifier)
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun RepMateNavGraphPreview() {
    RepMateTheme {
        RepMateNavGraph()
    }
}
