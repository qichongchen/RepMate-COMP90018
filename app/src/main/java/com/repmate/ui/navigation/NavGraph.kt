package com.repmate.ui.navigation

import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.repmate.engine.ExerciseType
import com.repmate.ui.auth.ForgotPasswordScreen
import com.repmate.ui.auth.LogInScreen
import com.repmate.ui.auth.SignUpScreen
import com.repmate.ui.auth.WelcomeScreen
import com.repmate.ui.calibration.CalibrationScreen
import com.repmate.ui.components.BottomNavBar
import com.repmate.ui.components.BottomNavItem
import com.repmate.ui.home.CalibrationGateViewModel
import com.repmate.ui.home.HomeScreen
import com.repmate.ui.onboarding.OnboardingGateViewModel
import com.repmate.ui.onboarding.OnboardingScreen
import com.repmate.ui.profile.ProfileScreen
import com.repmate.ui.profile.RecalibrateExercisePicker
import com.repmate.ui.theme.RepMateTheme
import com.repmate.ui.workout.LiveWorkoutScreen
import kotlinx.coroutines.launch

/**
 * Why Calibration was entered, carried as a route argument so its own "what happens when this
 * finishes" decision (see the `CALIBRATION` composable in [RepMateNavGraph]) doesn't have to guess
 * from the back stack.
 */
enum class CalibrationEntryPoint {
    /** Reached via Home's exercise chips -- finishing should proceed into the workout. */
    EXERCISE_START,

    /** Reached via Profile's "Recalibrate" row -- finishing should return to Profile, not start a workout nobody asked for. */
    RECALIBRATE,
}

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
    const val ARG_CALIBRATION_ENTRY_POINT = "entryPoint"
    const val ARG_SESSION_ID = "sessionId"
    const val ARG_EMAIL = "email"

    /**
     * Route patterns for [NavHost]'s `composable(route = ...)` registration. `CALIBRATION` carries
     * [ARG_CALIBRATION_ENTRY_POINT] as a required second path segment, not an optional query param
     * like `FORGOT_PASSWORD`'s email: every caller always has one to pass (see [calibration]),
     * unlike email, which that screen can genuinely be reached without.
     */
    const val CALIBRATION = "calibration/{$ARG_EXERCISE_TYPE}/{$ARG_CALIBRATION_ENTRY_POINT}"
    const val LIVE_WORKOUT = "live_workout/{$ARG_EXERCISE_TYPE}"
    const val MOTION_REPLAY = "motion_replay/{$ARG_SESSION_ID}"

    /**
     * `email` is an optional query param (`?email={email}`), not a required path segment: this
     * screen is also reachable without one (any future "forgot password" entry point that isn't
     * a failed LogIn attempt), in which case it just starts with a blank field.
     */
    const val FORGOT_PASSWORD = "forgot_password?$ARG_EMAIL={$ARG_EMAIL}"

    /** Concrete routes for [NavHostController.navigate] call sites. */
    fun calibration(
        exerciseType: ExerciseType,
        entryPoint: CalibrationEntryPoint,
    ) = "calibration/${exerciseType.name}/${entryPoint.name}"

    fun liveWorkout(exerciseType: ExerciseType) = "live_workout/${exerciseType.name}"

    fun motionReplay(sessionId: String) = "motion_replay/$sessionId"

    fun forgotPassword(email: String? = null): String =
        if (email.isNullOrBlank()) {
            "forgot_password"
        } else {
            "forgot_password?$ARG_EMAIL=${Uri.encode(email)}"
        }

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

/**
 * Turns the raw `{entryPoint}` path argument back into a [CalibrationEntryPoint].
 *
 * Falls back to [CalibrationEntryPoint.EXERCISE_START] for a missing or unrecognised value, same
 * reasoning as [parseExerciseType] -- and it's also the entry point every caller used before this
 * argument existed, so a malformed value degrades to the original behavior rather than a new one.
 */
private fun parseCalibrationEntryPoint(raw: String?): CalibrationEntryPoint =
    CalibrationEntryPoint.entries.firstOrNull { it.name == raw } ?: CalibrationEntryPoint.EXERCISE_START

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
 * RepMate's full navigation graph. History, Leaderboard, and Motion Replay are still
 * [PlaceholderScreen]s; every other destination is a real screen built one at a time on this
 * graph, which wires up the routes, the arguments they carry, and the navigation decisions
 * between them.
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

    // Scoped to this composable (effectively the whole app session, since RepMateNavGraph is
    // called once from MainActivity, not from inside a NavHost destination) rather than to any
    // one screen -- it's used identically by all three "just authenticated" success callbacks
    // below, not owned by any single one of them.
    val onboardingGateViewModel: OnboardingGateViewModel = hiltViewModel()
    // Same reasoning as onboardingGateViewModel above: one instance, used by both Home's
    // exercise-chip tap and live_workout's own entry check, rather than each owning a separate
    // "is this exercise calibrated" mechanism.
    val calibrationGateViewModel: CalibrationGateViewModel = hiltViewModel()
    val coroutineScope = rememberCoroutineScope()

    // The one place that decides "onboarding or home" after sign-up, log-in, or guest sign-in
    // all succeed -- so none of the three hardcodes a destination the way LogIn used to
    // (unconditionally HOME, which was wrong for a first-time log-in on a new device) or SignUp
    // used to (unconditionally ONBOARDING, which would replay it for a returning user).
    val navigateAfterAuthSuccess: () -> Unit = {
        coroutineScope.launch {
            val destination =
                if (onboardingGateViewModel.hasCurrentUserSeenOnboarding()) {
                    RepMateDestinations.HOME
                } else {
                    RepMateDestinations.ONBOARDING
                }
            navController.navigate(destination) {
                popUpTo(RepMateDestinations.WELCOME) { inclusive = true }
            }
        }
    }

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
            composable(RepMateDestinations.WELCOME) {
                WelcomeScreen(
                    onSignUp = { navController.navigate(RepMateDestinations.SIGNUP) },
                    onLogIn = { navController.navigate(RepMateDestinations.LOGIN) },
                    onGuestSignInSuccess = navigateAfterAuthSuccess,
                )
            }
            composable(RepMateDestinations.SIGNUP) {
                SignUpScreen(
                    onBackClick = { navController.popBackStack() },
                    onSignUpSuccess = navigateAfterAuthSuccess,
                    onLogInClick = {
                        // Replaces this screen on the back stack rather than stacking on top of
                        // it, so back from Log in returns to Welcome, not bounces through Signup.
                        navController.navigate(RepMateDestinations.LOGIN) {
                            popUpTo(RepMateDestinations.SIGNUP) { inclusive = true }
                        }
                    },
                )
            }
            composable(RepMateDestinations.LOGIN) {
                LogInScreen(
                    onBackClick = { navController.popBackStack() },
                    onLogInSuccess = navigateAfterAuthSuccess,
                    onForgotPasswordClick = { email ->
                        navController.navigate(RepMateDestinations.forgotPassword(email))
                    },
                    onSignUpClick = {
                        // Mirrors Signup's "Log in" link: replaces this screen on the back stack
                        // rather than stacking on top of it, so back from Sign up returns to
                        // Welcome, not bounces through Login.
                        navController.navigate(RepMateDestinations.SIGNUP) {
                            popUpTo(RepMateDestinations.LOGIN) { inclusive = true }
                        }
                    },
                )
            }

            composable(
                route = RepMateDestinations.FORGOT_PASSWORD,
                arguments =
                    listOf(
                        navArgument(RepMateDestinations.ARG_EMAIL) {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                    ),
            ) { backStackEntry ->
                ForgotPasswordScreen(
                    initialEmail = backStackEntry.arguments?.getString(RepMateDestinations.ARG_EMAIL).orEmpty(),
                    onBackClick = { navController.popBackStack() },
                )
            }

            composable(RepMateDestinations.ONBOARDING) {
                OnboardingScreen(
                    onFinish = {
                        // NOTE: onboarding goes straight to home once its 3 explainer pages are done
                        // (or Skip is tapped). Calibration is deliberately NOT triggered from here --
                        // confirmed with the engine side that calibration should run lazily, the
                        // first time someone attempts an exercise with no profile yet (see the
                        // live_workout check below), not as a blanket step after onboarding. If that
                        // policy ever changes, this is the one line to edit.
                        navController.navigate(RepMateDestinations.HOME) {
                            popUpTo(RepMateDestinations.WELCOME) { inclusive = true }
                        }
                    },
                )
            }

            composable(RepMateDestinations.HOME) {
                HomeScreen(
                    onExerciseSelected = { exerciseType ->
                        // Real logic, not a stub: this always resolves to CALIBRATION right now
                        // only because CalibrationGateViewModel's backing check is itself stubbed
                        // to always report false (no Room table for calibration profiles yet) --
                        // see StubCalibrationRepository. The branch itself is real, so a chip tap
                        // for an already-calibrated exercise correctly goes straight to
                        // live_workout the moment that stub is replaced with a real query.
                        coroutineScope.launch {
                            val destination =
                                if (calibrationGateViewModel.hasCalibrationProfile(exerciseType)) {
                                    RepMateDestinations.liveWorkout(exerciseType)
                                } else {
                                    RepMateDestinations.calibration(exerciseType, CalibrationEntryPoint.EXERCISE_START)
                                }
                            navController.navigate(destination)
                        }
                    },
                    onProfileClick = { navController.navigate(RepMateDestinations.PROFILE) },
                )
            }
            composable(RepMateDestinations.HISTORY) { PlaceholderScreen(RepMateDestinations.HISTORY) }
            composable(RepMateDestinations.LEADERBOARD) { PlaceholderScreen(RepMateDestinations.LEADERBOARD) }
            composable(RepMateDestinations.PROFILE) {
                // Local to this destination, not hoisted to RepMateNavGraph level like
                // onboardingGateViewModel/calibrationGateViewModel above -- nothing outside this one
                // screen's visit needs to know whether the picker is open.
                var showRecalibratePicker by remember { mutableStateOf(false) }

                ProfileScreen(
                    onSignedOut = {
                        // Numeric popUpTo(0), not popUpTo(RepMateDestinations.WELCOME): by the time a
                        // user reaches Profile, Welcome has typically already been popped off the back
                        // stack by navigateAfterAuthSuccess above, so a route-based popUpTo targeting it
                        // would find nothing to pop. popUpTo(0) clears the entire back stack regardless
                        // of what's on it, so a signed-out user can never navigate back into an
                        // authenticated screen.
                        navController.navigate(RepMateDestinations.WELCOME) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                    onRecalibrateClick = { showRecalibratePicker = true },
                )

                if (showRecalibratePicker) {
                    RecalibrateExercisePicker(
                        onExerciseSelected = { exerciseType ->
                            showRecalibratePicker = false
                            navController.navigate(RepMateDestinations.calibration(exerciseType, CalibrationEntryPoint.RECALIBRATE))
                        },
                        onDismissRequest = { showRecalibratePicker = false },
                    )
                }
            }

            composable(
                route = RepMateDestinations.MOTION_REPLAY,
                arguments = listOf(navArgument(RepMateDestinations.ARG_SESSION_ID) { type = NavType.StringType }),
            ) { backStackEntry ->
                val sessionId = backStackEntry.arguments?.getString(RepMateDestinations.ARG_SESSION_ID).orEmpty()
                PlaceholderScreen("motion_replay/$sessionId")
            }

            composable(
                route = RepMateDestinations.CALIBRATION,
                arguments =
                    listOf(
                        navArgument(RepMateDestinations.ARG_EXERCISE_TYPE) { type = NavType.StringType },
                        navArgument(RepMateDestinations.ARG_CALIBRATION_ENTRY_POINT) { type = NavType.StringType },
                    ),
            ) { backStackEntry ->
                val exerciseType = parseExerciseType(backStackEntry.arguments?.getString(RepMateDestinations.ARG_EXERCISE_TYPE))
                val entryPoint =
                    parseCalibrationEntryPoint(backStackEntry.arguments?.getString(RepMateDestinations.ARG_CALIBRATION_ENTRY_POINT))
                CalibrationScreen(
                    exerciseType = exerciseType,
                    onCalibrationComplete = {
                        when (entryPoint) {
                            CalibrationEntryPoint.EXERCISE_START ->
                                // Calibration is only ever reached this way via a Home chip tap now
                                // that LiveWorkoutViewModel itself no longer redirects here on an
                                // uncalibrated profile (it proceeds with profile = null instead, which
                                // FormScorer already handles) -- so there is no live_workout entry
                                // beneath this one on the back stack to worry about resuming instead
                                // of duplicating.
                                navController.navigate(RepMateDestinations.liveWorkout(exerciseType)) {
                                    popUpTo(RepMateDestinations.calibration(exerciseType, entryPoint)) { inclusive = true }
                                }
                            CalibrationEntryPoint.RECALIBRATE ->
                                // Profile is still directly below Calibration on the back stack in this
                                // flow -- Recalibrate is only ever launched straight from Profile, never
                                // via a redirect chain like live_workout's pre-check above -- so popping
                                // back to it is simpler and correct, with no forward destination to
                                // navigate into and no duplicate-entry risk to guard against.
                                navController.popBackStack()
                        }
                    },
                )
            }

            composable(
                route = RepMateDestinations.LIVE_WORKOUT,
                arguments = listOf(navArgument(RepMateDestinations.ARG_EXERCISE_TYPE) { type = NavType.StringType }),
            ) { backStackEntry ->
                val exerciseType = parseExerciseType(backStackEntry.arguments?.getString(RepMateDestinations.ARG_EXERCISE_TYPE))
                LiveWorkoutScreen(
                    exerciseType = exerciseType,
                    onWorkoutFinished = { sessionId ->
                        navController.navigate(RepMateDestinations.motionReplay(sessionId)) {
                            popUpTo(RepMateDestinations.liveWorkout(exerciseType)) { inclusive = true }
                        }
                    },
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun RepMateNavGraphPreview() {
    RepMateTheme {
        RepMateNavGraph()
    }
}
