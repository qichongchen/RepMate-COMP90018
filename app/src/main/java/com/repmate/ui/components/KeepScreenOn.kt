package com.repmate.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Keeps the screen on for as long as this composable is in the composition; the phone does not
 * dim or lock while an exercise is under way, and goes back to its normal timeout afterwards.
 *
 * Put it in every screen that runs while the user is exercising: the phone is propped up or in a
 * pocket with nobody touching it, so the usual "no touches, time to sleep" timeout would fire
 * mid-set. That also matters beyond looks: once the screen turns off and the activity stops,
 * Android stops delivering sensor events to a background app, so the IMU workouts would silently
 * stop counting, and the camera one would lose its camera.
 *
 * Holds are counted, not just set and cleared. When one workout screen navigates to another, the
 * outgoing screen is often disposed *after* the incoming one has started, and a plain
 * "clear the flag on dispose" would then turn the screen-on flag off under the screen that still
 * needs it.
 */
@Composable
fun KeepScreenOn() {
    val window = LocalContext.current.findActivity()?.window
    DisposableEffect(window) {
        if (window != null) {
            screenOnHolds.acquire()
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            if (window != null && screenOnHolds.release()) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
}

/** Only ever touched from the main thread, which is where composition runs. */
private val screenOnHolds = HoldCounter()

/**
 * Counts how many things currently want something kept on, so it is only turned off when the last
 * of them lets go. Pure Kotlin so the overlap case can be unit tested.
 */
internal class HoldCounter {
    private var holds = 0

    fun acquire() {
        holds++
    }

    /** Lets one hold go; true if that was the last one, i.e. it is now safe to turn the thing off. */
    fun release(): Boolean {
        if (holds == 0) return false
        holds--
        return holds == 0
    }
}

/** The [Activity] behind this context, which Compose often hands over wrapped in other contexts. */
private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
