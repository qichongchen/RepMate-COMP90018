package com.repmate.ui.navigation

import android.net.Uri
import android.util.Log
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
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
import com.repmate.ui.displayname.ChooseDisplayNameScreen
import com.repmate.ui.displayname.ChooseNameMode
import com.repmate.ui.displayname.DisplayNameGateViewModel
import com.repmate.ui.ghostduel.GhostDuelScreen
import com.repmate.ui.history.HistoryScreen
import com.repmate.ui.history.SessionDetailScreen
import com.repmate.ui.home.CalibrationGateViewModel
import com.repmate.ui.home.HomeScreen
import com.repmate.ui.leaderboard.LeaderboardScreen
import com.repmate.ui.onboarding.OnboardingGateViewModel
import com.repmate.ui.onboarding.OnboardingScreen
import com.repmate.ui.motionreplay.MotionReplayScreen
import com.repmate.ui.profile.ProfileScreen
import com.repmate.ui.profile.RecalibrateExercisePicker
import com.repmate.ui.theme.RepMateTheme
import com.repmate.ui.tutorial.ExerciseTutorialDialog
import com.repmate.ui.tutorial.TutorialGateViewModel
import com.repmate.ui.workout.LiveWorkoutScreen
import com.repmate.ui.workout.pushup.PushupWorkoutScreen
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

    /** Bare route, used by every `navigate(...)` call to Sign up and by `popUpTo`. The registered pattern is [SIGNUP_PATTERN]. */
    const val SIGNUP = "signup"
    const val LOGIN = "login"
    const val ONBOARDING = "onboarding"

    /**
     * Bare route for the choose-display-name screen, which opens in gate mode (the argument's
     * default). Used by [afterAuth] and by `popUpTo`. The registered pattern is [CHOOSE_DISPLAY_NAME_PATTERN].
     */
    const val CHOOSE_DISPLAY_NAME = "choose_display_name"
    const val HOME = "home"
    const val HISTORY = "history"
    const val LEADERBOARD = "leaderboard"
    const val PROFILE = "profile"

    /** Bare route, no arguments -- the friend/exercise picks happen on the screen itself, not carried in from the caller. Reached only from Home's Ghost Duel banner. */
    const val GHOST_DUEL = "ghost_duel"

    const val ARG_EXERCISE_TYPE = "exerciseType"
    const val ARG_CALIBRATION_ENTRY_POINT = "entryPoint"
    const val ARG_SESSION_ID = "sessionId"
    const val ARG_EMAIL = "email"
    const val ARG_UPGRADE = "upgrade"
    const val ARG_POST_WORKOUT = "postWorkout"
    const val ARG_NAME_MODE = "mode"

    /** Choose-display-name's registered route: `mode` is an optional [ChooseNameMode] name, defaulting to the gate. */
    const val CHOOSE_DISPLAY_NAME_PATTERN = "$CHOOSE_DISPLAY_NAME?$ARG_NAME_MODE={$ARG_NAME_MODE}"

    /**
     * Sign up's registered route: `upgrade` is an optional query param defaulting to false, so the
     * bare [SIGNUP] route keeps working for everyone. `upgrade=true` (see [signupUpgrade]) is the
     * guest-upgrade mode reached from Profile.
     */
    const val SIGNUP_PATTERN = "signup?$ARG_UPGRADE={$ARG_UPGRADE}"

    /**
     * Route patterns for [NavHost]'s `composable(route = ...)` registration. `CALIBRATION` carries
     * [ARG_CALIBRATION_ENTRY_POINT] as a required second path segment, not an optional query param
     * like `FORGOT_PASSWORD`'s email: every caller always has one to pass (see [calibration]),
     * unlike email, which that screen can genuinely be reached without.
     */
    const val CALIBRATION = "calibration/{$ARG_EXERCISE_TYPE}/{$ARG_CALIBRATION_ENTRY_POINT}"
    const val LIVE_WORKOUT = "live_workout/{$ARG_EXERCISE_TYPE}"

    /**
     * No `{exerciseType}` argument, unlike [LIVE_WORKOUT]: this route is push-ups only, so there
     * is nothing for an argument to select between. See the `PUSHUP` branch of Home's
     * `onExerciseSelected` below for why push-ups get their own route instead of sharing this one.
     */
    const val PUSHUP_WORKOUT = "pushup_workout"

    /**
     * Reached only from the "Review reps" button on Session Detail's post-workout summary, never
     * directly from a workout or from History. Back returns to that summary.
     */
    const val MOTION_REPLAY = "motion_replay/{$ARG_SESSION_ID}"

    /**
     * One session's detail, in two modes selected by the optional [ARG_POST_WORKOUT] query param
     * (default false): reached from a History card tap as plain detail, or from a finished workout
     * as the post-workout summary (see [sessionDetail]). Optional so History's route is unchanged.
     */
    const val SESSION_DETAIL = "session_detail/{$ARG_SESSION_ID}?$ARG_POST_WORKOUT={$ARG_POST_WORKOUT}"

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

    /** The query param is omitted when false, so a History tap builds exactly the route it always did. */
    fun sessionDetail(
        sessionId: String,
        postWorkout: Boolean = false,
    ) = "session_detail/$sessionId" + if (postWorkout) "?$ARG_POST_WORKOUT=true" else ""

    /** Choose display name in the given [mode] (gate is the bare [CHOOSE_DISPLAY_NAME]). */
    fun chooseDisplayName(mode: ChooseNameMode): String = "$CHOOSE_DISPLAY_NAME?$ARG_NAME_MODE=${mode.name}"

    /** Sign up in guest-upgrade mode: links the new credentials to the current anonymous account. */
    fun signupUpgrade(): String = "$SIGNUP?$ARG_UPGRADE=true"

    fun forgotPassword(email: String? = null): String =
        if (email.isNullOrBlank()) {
            "forgot_password"
        } else {
            "forgot_password?$ARG_EMAIL=${Uri.encode(email)}"
        }

    /**
     * Where a signed-in user goes: first Choose display name if they are a real account without a
     * claimed name ([needsDisplayName]), else Home if they have already been through onboarding,
     * else Onboarding. The single home of that rule, used both after a sign-in (see
     * `navigateAfterAuthSuccess`) and at launch for a restored session (see `StartupViewModel`).
     * Leaving the gate calls this again with `needsDisplayName = false`.
     */
    fun afterAuth(
        hasSeenOnboarding: Boolean,
        needsDisplayName: Boolean = false,
    ): String =
        when {
            needsDisplayName -> CHOOSE_DISPLAY_NAME
            hasSeenOnboarding -> HOME
            else -> ONBOARDING
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
 * RepMate's full navigation graph -- every destination is a real screen, built one at a time on
 * this graph, which wires up the routes, the arguments they carry, and the navigation decisions
 * between them. [PlaceholderScreen] remains available for whatever destination is next to be
 * built this way.
 *
 * The bottom nav bar is hosted here, in a [Scaffold] wrapping the [NavHost], rather than inside
 * each of home/history/leaderboard/profile individually -- it only shows for those four routes,
 * driven by the current back stack entry, so screens like welcome or live_workout don't get it.
 *
 * [startDestination] is resolved before this is composed (see [StartupViewModel]): Welcome for a
 * signed-out user, otherwise Home or Onboarding. So Welcome is only on the back stack in the
 * signed-out flow, and anything that pops "the auth screens" must not assume it is there.
 */
@Composable
fun RepMateNavGraph(
    startDestination: String,
    navController: NavHostController = rememberNavController(),
    modifier: Modifier = Modifier,
) {
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route

    // Scoped to this composable (effectively the whole app session, since RepMateNavGraph is
    // called once from MainActivity, not from inside a NavHost destination) rather than to any
    // one screen -- it's used identically by all three "just authenticated" success callbacks
    // below, not owned by any single one of them.
    val onboardingGateViewModel: OnboardingGateViewModel = hiltViewModel()
    // Same reasoning: the display-name gate is asked by navigateAfterAuthSuccess, not owned by a screen.
    val displayNameGateViewModel: DisplayNameGateViewModel = hiltViewModel()
    // Same reasoning as onboardingGateViewModel above: one instance, used by both Home's
    // exercise-chip tap and live_workout's own entry check, rather than each owning a separate
    // "is this exercise calibrated" mechanism.
    val calibrationGateViewModel: CalibrationGateViewModel = hiltViewModel()
    // Same reasoning again: one tutorial gate for every exercise start path.
    val tutorialGateViewModel: TutorialGateViewModel = hiltViewModel()
    val coroutineScope = rememberCoroutineScope()

    // The exercise whose tutorial is showing (see startExercise below), or null. Saveable so a
    // rotation mid-dialog doesn't silently drop the pick.
    var pendingTutorialExercise by rememberSaveable { mutableStateOf<ExerciseType?>(null) }

    // Where an exercise pick leads once any tutorial is out of the way: the calibration-gate
    // branching. Only called from startExercise and the tutorial's "Continue" below, so there's
    // exactly one copy of it rather than a second one drifting out of sync.
    val routeToExercise: (ExerciseType) -> Unit = { exerciseType ->
        if (exerciseType == ExerciseType.PUSHUP) {
            // Push-ups skip the calibration gate entirely and go straight to their
            // own camera workout screen -- there is no per-user profile to check
            // yet (see CalibrationUiState.Unsupported, still shown if this exercise
            // is ever reached through Calibration some other way, e.g. Profile's
            // Recalibrate picker), and PUSHUP_WORKOUT isn't parameterised by
            // exercise type the way LIVE_WORKOUT is, since it only ever means this
            // one exercise.
            navController.navigate(RepMateDestinations.PUSHUP_WORKOUT)
        } else {
            // Real logic, not a stub: this always resolves to CALIBRATION right now
            // only because CalibrationGateViewModel's backing check is itself
            // stubbed to always report false (no Room table for calibration
            // profiles yet) -- see StubCalibrationRepository. The branch itself is
            // real, so a chip tap for an already-calibrated exercise correctly goes
            // straight to live_workout the moment that stub is replaced with a real
            // query.
            coroutineScope.launch {
                val destination =
                    if (calibrationGateViewModel.hasCalibrationProfile(exerciseType)) {
                        RepMateDestinations.liveWorkout(exerciseType)
                    } else {
                        RepMateDestinations.calibration(exerciseType, CalibrationEntryPoint.EXERCISE_START)
                    }
                navController.navigate(destination)
            }
        }
    }

    // Makes Welcome the only entry on the back stack. Used by sign-out, and as the first step of
    // switchToExistingAccount below; it signs nobody out itself (sign-out does that before calling it).
    // Numeric popUpTo(0), not popUpTo(RepMateDestinations.WELCOME): by the time a user reaches
    // Profile, Welcome has typically already been popped off the back stack by
    // navigateAfterAuthSuccess below (or was never on it, for a user launched straight into Home),
    // so a route-based popUpTo targeting it would find nothing to pop. popUpTo(0) clears the entire
    // back stack regardless of what's on it, so nobody can navigate back into an authenticated
    // screen, and it leaves Welcome as the root that navigateAfterAuthSuccess's own
    // popUpTo(WELCOME) { inclusive } relies on.
    val resetToWelcome: () -> Unit = {
        navController.navigate(RepMateDestinations.WELCOME) {
            popUpTo(0) { inclusive = true }
        }
    }

    // Shared by both ways a guest can choose to log in to an existing account ("I already have an
    // account" on Profile, "Log in instead" on the upgrade Sign up screen), after they confirm
    // SwitchAccountDialog. Resets to Welcome first and then puts Log in on top of it, so:
    //  - back from Log in lands on Welcome, and back from Welcome exits the app (it is the root);
    //  - navigateAfterAuthSuccess's popUpTo(WELCOME) { inclusive } clears Welcome *and* Log in.
    // NOTE: not a bare navigate(LOGIN): a guest who launched into Home has no Welcome on the stack,
    // so there would be nothing for that pop to find. The guest is deliberately not signed out here;
    // they stay the current user until a log-in actually succeeds.
    val switchToExistingAccount: () -> Unit = {
        resetToWelcome()
        navController.navigate(RepMateDestinations.LOGIN)
    }

    // The one place that decides "onboarding or home" after sign-up, log-in, or guest sign-in
    // all succeed -- so none of the three hardcodes a destination the way LogIn used to
    // (unconditionally HOME, which was wrong for a first-time log-in on a new device) or SignUp
    // used to (unconditionally ONBOARDING, which would replay it for a returning user).
    val navigateAfterAuthSuccess: () -> Unit = {
        coroutineScope.launch {
            val destination =
                RepMateDestinations.afterAuth(
                    hasSeenOnboarding = onboardingGateViewModel.hasCurrentUserSeenOnboarding(),
                    needsDisplayName = displayNameGateViewModel.needsDisplayName(),
                )
            navController.navigate(destination) {
                // Only ever called from Welcome / Sign up / Log in, which are reached only in the
                // signed-out flow (start destination Welcome, or Profile's sign-out), so Welcome is
                // always on the stack here and popping it inclusive also drops the auth screens
                // above it.
                popUpTo(RepMateDestinations.WELCOME) { inclusive = true }
            }
        }
    }

    // The one entry point for starting an exercise -- shared by Home's exercise chips and Ghost
    // Duel's "Start Workout to Beat It" button. Shows ExerciseTutorialDialog first unless the user
    // has opted out of it for this exercise; the dialog's "Continue" then hands on to
    // routeToExercise. Gated here rather than in each screen's ViewModel so both paths share it:
    // push-up's side-on camera placement matters for accuracy whichever button started it.
    val startExercise: (ExerciseType) -> Unit = { exerciseType ->
        // A fast double tap can land before the dialog draws; don't stack a second decision.
        if (pendingTutorialExercise == null) {
            coroutineScope.launch {
                if (tutorialGateViewModel.hasOptedOutOfTutorial(exerciseType)) {
                    routeToExercise(exerciseType)
                } else {
                    pendingTutorialExercise = exerciseType
                }
            }
        }
    }

    // Shared by the bottom bar and every in-screen shortcut to a tab (Home's avatar, Ghost Duel's
    // "add friend", History's empty state), so all switch tabs identically: the tab highlights
    // correctly and the back stack stays at most [Home, <tab>], so back from any tab reaches Home
    // and back from Home exits the app.
    // NOTE: pops to HOME, not findStartDestination(). The graph's start destination is fixed at
    // launch, so for a user who signed in this session it is Welcome, which is no longer on the back
    // stack -- the pop would then silently do nothing. HOME is always the root of the signed-in back
    // stack (see navigateAfterAuthSuccess and Onboarding).
    // NOTE: no saveState / restoreState. Restoring a saved tab stack brought back the stack that had
    // just been popped (e.g. tapping Home from Profile re-opened Profile); tabs simply start fresh.
    val navigateToBottomNavRoute: (String) -> Unit = { route ->
        // Home is already on the stack, so for the Home tab a plain pop is the most reliable way back
        // to it: it drops everything above Home without recreating it. If the pop does nothing (already
        // on Home, or Home isn't on the stack) fall through to the navigate below, which is a no-op
        // on Home thanks to launchSingleTop.
        val poppedToHome = route == RepMateDestinations.HOME && navController.popBackStack(RepMateDestinations.HOME, inclusive = false)
        if (!poppedToHome) {
            navController.navigate(route) {
                popUpTo(RepMateDestinations.HOME)
                launchSingleTop = true
            }
        }
    }

    // Leaves the post-workout summary for Home. Deliberately not navigateToBottomNavRoute: that helper
    // is for tab switching, and its navigate(HOME) { popUpTo(HOME) ...; launchSingleTop }
    // combination is unreliable when the top entry is the summary (it can leave the summary on the stack).
    // NOTE: try the plain pop first -- it removes the summary and anything above the existing Home entry
    // without recreating Home. Only if Home isn't on the stack do we reset to a single fresh Home.
    // Returns popBackStack's result: true if Home was already on the stack, false if the fallback ran.
    val navigateHomeAfterWorkout: () -> Boolean = {
        val poppedToHome = navController.popBackStack(RepMateDestinations.HOME, inclusive = false)
        if (!poppedToHome) {
            navController.navigate(RepMateDestinations.HOME) {
                popUpTo(navController.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        }
        poppedToHome
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
                        navigateToBottomNavRoute(route)
                    },
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(RepMateDestinations.WELCOME) {
                WelcomeScreen(
                    onSignUp = { navController.navigate(RepMateDestinations.SIGNUP) },
                    onLogIn = { navController.navigate(RepMateDestinations.LOGIN) },
                    onGuestSignInSuccess = navigateAfterAuthSuccess,
                )
            }
            composable(
                route = RepMateDestinations.SIGNUP_PATTERN,
                arguments =
                    listOf(
                        navArgument(RepMateDestinations.ARG_UPGRADE) {
                            type = NavType.BoolType
                            defaultValue = false
                        },
                    ),
            ) { backStackEntry ->
                val upgrade = backStackEntry.arguments?.getBoolean(RepMateDestinations.ARG_UPGRADE) ?: false
                SignUpScreen(
                    onBackClick = { navController.popBackStack() },
                    // An upgrade keeps the same account, so there is no onboarding decision to make
                    // and Welcome is not on the back stack: just return to Profile, which is
                    // directly beneath this screen. Refreshing its header is Profile's job.
                    onSignUpSuccess = {
                        if (upgrade) {
                            // The account is real now but has no claimed name: choose one, then
                            // return to Profile. Replaces Sign up so back from here is Profile.
                            navController.navigate(RepMateDestinations.chooseDisplayName(ChooseNameMode.UPGRADE)) {
                                popUpTo(RepMateDestinations.SIGNUP_PATTERN) { inclusive = true }
                            }
                        } else {
                            navigateAfterAuthSuccess()
                        }
                    },
                    upgrade = upgrade,
                    onSwitchToLogIn = switchToExistingAccount,
                    onLogInClick = {
                        // Replaces this screen on the back stack rather than stacking on top of
                        // it, so back from Log in returns to Welcome, not bounces through Signup.
                        navController.navigate(RepMateDestinations.LOGIN) {
                            popUpTo(RepMateDestinations.SIGNUP_PATTERN) { inclusive = true }
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

            composable(
                route = RepMateDestinations.CHOOSE_DISPLAY_NAME_PATTERN,
                arguments =
                    listOf(
                        navArgument(RepMateDestinations.ARG_NAME_MODE) {
                            type = NavType.StringType
                            defaultValue = ChooseNameMode.GATE.name
                        },
                    ),
            ) { backStackEntry ->
                val mode = ChooseNameMode.fromArgument(backStackEntry.arguments?.getString(RepMateDestinations.ARG_NAME_MODE))
                ChooseDisplayNameScreen(
                    mode = mode,
                    onFinished = {
                        if (mode == ChooseNameMode.GATE) {
                            // Onboarding (if not seen) or Home. Pops this screen itself, like
                            // Onboarding does: it is the root of the stack, so nothing can go back to it.
                            coroutineScope.launch {
                                val destination =
                                    RepMateDestinations.afterAuth(
                                        hasSeenOnboarding = onboardingGateViewModel.hasCurrentUserSeenOnboarding(),
                                        needsDisplayName = false,
                                    )
                                navController.navigate(destination) {
                                    popUpTo(RepMateDestinations.CHOOSE_DISPLAY_NAME_PATTERN) { inclusive = true }
                                }
                            }
                        } else {
                            // Upgrade and rename both sit on top of Profile.
                            navController.popBackStack()
                        }
                    },
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
                            // Pops Onboarding itself, not Welcome: a signed-in user launched
                            // straight into Onboarding has no Welcome on the back stack, so popping
                            // it would do nothing and back from Home would return here.
                            popUpTo(RepMateDestinations.ONBOARDING) { inclusive = true }
                        }
                    },
                )
            }

            composable(RepMateDestinations.HOME) {
                HomeScreen(
                    onExerciseSelected = startExercise,
                    onProfileClick = { navigateToBottomNavRoute(RepMateDestinations.PROFILE) },
                    onGhostDuelClick = { navController.navigate(RepMateDestinations.GHOST_DUEL) },
                )
            }
            composable(RepMateDestinations.GHOST_DUEL) {
                GhostDuelScreen(
                    onBackClick = { navController.popBackStack() },
                    onStartWorkoutClick = startExercise,
                    // TODO: once a real friends-management screen exists (Jasper's work), point this at
                    // that screen instead of Profile, and wire Profile's own "Friends" row the same way.
                    onAddFriendClick = { navigateToBottomNavRoute(RepMateDestinations.PROFILE) },
                )
            }
            composable(RepMateDestinations.HISTORY) {
                HistoryScreen(
                    onSessionClick = { sessionId ->
                        navController.navigate(RepMateDestinations.sessionDetail(sessionId))
                    },
                    // Home is where the exercise chips live, and they go through startExercise, so
                    // the tutorial and calibration checks still apply.
                    onStartWorkoutClick = { navigateToBottomNavRoute(RepMateDestinations.HOME) },
                )
            }
            composable(RepMateDestinations.LEADERBOARD) {
                LeaderboardScreen(
                    onGhostDuelClick = {
                        navController.navigate(RepMateDestinations.GHOST_DUEL)
                    }
                )
            }
            composable(RepMateDestinations.PROFILE) {
                // Local to this destination, not hoisted to RepMateNavGraph level like
                // onboardingGateViewModel/calibrationGateViewModel above -- nothing outside this one
                // screen's visit needs to know whether the picker is open.
                var showRecalibratePicker by remember { mutableStateOf(false) }

                ProfileScreen(
                    onSignedOut = resetToWelcome,
                    onRecalibrateClick = { showRecalibratePicker = true },
                    onCreateAccountClick = { navController.navigate(RepMateDestinations.signupUpgrade()) },
                    onSwitchToExistingAccount = switchToExistingAccount,
                    onEditDisplayNameClick = {
                        navController.navigate(RepMateDestinations.chooseDisplayName(ChooseNameMode.RENAME))
                    },
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
                MotionReplayScreen(
                    sessionId = sessionId,
                    onBackClick = { navController.popBackStack() },
                    onCalibrateClick = { exerciseType ->
                        navController.navigate(RepMateDestinations.calibration(exerciseType, CalibrationEntryPoint.EXERCISE_START))
                    },
                )
            }

            composable(
                route = RepMateDestinations.SESSION_DETAIL,
                arguments =
                    listOf(
                        navArgument(RepMateDestinations.ARG_SESSION_ID) { type = NavType.StringType },
                        navArgument(RepMateDestinations.ARG_POST_WORKOUT) {
                            type = NavType.BoolType
                            defaultValue = false
                        },
                    ),
            ) { backStackEntry ->
                val sessionId = backStackEntry.arguments?.getString(RepMateDestinations.ARG_SESSION_ID).orEmpty()
                val postWorkout = backStackEntry.arguments?.getBoolean(RepMateDestinations.ARG_POST_WORKOUT) ?: false
                SessionDetailScreen(
                    sessionId = sessionId,
                    postWorkout = postWorkout,
                    onBackClick = { navController.popBackStack() },
                    // Pushed on top of the summary (not replacing it), so Motion Replay's back
                    // returns here.
                    onReviewRepsClick = { navController.navigate(RepMateDestinations.motionReplay(sessionId)) },
                    // The finished workout was already popped when this summary was opened, so going
                    // Home here (and on system back, see SessionDetailScreen) can't reach it again.
                    onDoneClick = {
                        // TEMPORARY: debug logging to inspect the navigation state on device (public
                        // APIs only). Remove once the Done / system-back behaviour is confirmed.
                        Log.d(
                            "RepMateNav",
                            "Done before: current=${navController.currentDestination?.route} " +
                                "previous=${navController.previousBackStackEntry?.destination?.route}",
                        )
                        val poppedToHome = navigateHomeAfterWorkout()
                        Log.d(
                            "RepMateNav",
                            "Done after: popBackStack(HOME)=$poppedToHome " +
                                "current=${navController.currentDestination?.route} " +
                                "previous=${navController.previousBackStackEntry?.destination?.route}",
                        )
                    },
                )
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
                    // Only reachable from the push-up "not available" state: back to wherever
                    // calibration was entered from, rather than on into a workout.
                    onBack = { navController.popBackStack() },
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
                        navController.navigate(RepMateDestinations.sessionDetail(sessionId, postWorkout = true)) {
                            popUpTo(RepMateDestinations.liveWorkout(exerciseType)) { inclusive = true }
                        }
                    },
                )
            }

            composable(RepMateDestinations.PUSHUP_WORKOUT) {
                PushupWorkoutScreen(
                    onWorkoutFinished = { sessionId ->
                        navController.navigate(RepMateDestinations.sessionDetail(sessionId, postWorkout = true)) {
                            popUpTo(RepMateDestinations.PUSHUP_WORKOUT) { inclusive = true }
                        }
                    },
                )
            }
        }
    }

    // Drawn over whichever screen started the exercise (Home or Ghost Duel). Only "Continue"
    // proceeds, and only it persists "Don't show this again"; back just closes the dialog.
    pendingTutorialExercise?.let { exerciseType ->
        ExerciseTutorialDialog(
            exerciseType = exerciseType,
            showDismissCheckbox = true,
            ctaLabel = "Continue",
            onContinue = { dontShowAgainChecked ->
                pendingTutorialExercise = null
                coroutineScope.launch {
                    // Written before routing so the next pick already sees it.
                    if (dontShowAgainChecked) tutorialGateViewModel.optOutOfTutorial(exerciseType)
                    routeToExercise(exerciseType)
                }
            },
            onCancel = { pendingTutorialExercise = null },
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun RepMateNavGraphPreview() {
    RepMateTheme {
        RepMateNavGraph(startDestination = RepMateDestinations.WELCOME)
    }
}
