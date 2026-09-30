package com.repmate.ui.auth

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * The confirmation a guest sees before leaving their guest session to log in to an existing
 * account. Shared by every entry point ([com.repmate.ui.profile.ProfileScreen]'s "I already have an
 * account" button and [SignUpScreen]'s "Log in instead" error action) so the wording and the
 * consequence they agree to are identical wherever they start.
 *
 * Opening or confirming this dialog does not sign anyone out: the caller only navigates to Log in
 * (on top of Welcome), and the guest stays the current Firebase user until a log-in actually
 * succeeds. Cancel, back, or abandoning Log in / Welcome therefore leaves them as the same guest
 * with their data.
 *
 * @param onConfirm invoked on "Log in"; the caller navigates (see `switchToExistingAccount` in
 *   `NavGraph.kt`) and hides the dialog.
 * @param onDismiss invoked on "Cancel" or an outside tap / back.
 */
@Composable
fun SwitchAccountDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log in to another account?") },
        text = {
            Text(
                "Workouts you've recorded as a guest on this phone won't carry over to that account. " +
                    "Cancel to keep your guest session.",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Log in") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
