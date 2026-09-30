package com.repmate.ui.profile

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.repmate.engine.ExerciseType
import com.repmate.safety.CheckInScheduler
import com.repmate.ui.components.RepMateButton
import com.repmate.ui.components.RepMateCard
import com.repmate.ui.theme.RepMateTheme
import com.repmate.ui.tutorial.ExerciseTutorialDialog
import java.util.Locale

/**
 * The Profile tab: an account header, a stats summary, and grouped settings rows. Hosts the
 * bottom nav bar -- but that's rendered by `RepMateNavGraph`'s Scaffold, not here, same as Home.
 *
 * Split into this stateful wrapper and the stateless [ProfileContent] below, same reasoning as
 * every other screen in this app: previews render from a plain [ProfileUiState], no Hilt required.
 *
 * Sections, top to bottom: stats, preferences (the three toggles), exercises ("How exercises
 * work", "Recalibrate"), safety ("Check-in alerts" toggle + emergency contact), account
 * ("Friends"), then a standalone bottom button. For this first pass everything is real except the
 * "Friends" row, the only static placeholder.
 *
 * ## Sign out, and guests
 * For a signed-in user the bottom button is "Sign out", drawn in the error colour and gated behind
 * a confirmation dialog. For a guest (anonymous account, [ProfileUiState.isGuest]) it is replaced
 * by "Create account": signing out of an anonymous account discards it and everything saved to
 * it, so a guest is offered an upgrade instead, which keeps the same account (see
 * `AuthViewModel.onCreateAccountClicked`). The header is refreshed each time this screen enters
 * composition so it reflects an upgrade made on the Sign up screen.
 *
 * ## Safety check-in
 * Turning the toggle on doesn't call [ProfileViewModel.onSafetyCheckInToggled] directly -- it
 * first shows [SafetyCheckInDisclaimerDialog], then requests SEND_SMS/ACCESS_FINE_LOCATION/
 * POST_NOTIFICATIONS (on 33+) together, and only persists `enabled = true` if SEND_SMS was
 * granted (see `com.repmate.safety.SmsSafetyAlertSender`'s KDoc for why that's the one that
 * gates the feature -- the other two degrade gracefully instead). Turning it off skips all of
 * that. "Emergency contact" opens [EmergencyContactDialog] regardless of whether check-in is on.
 *
 * ## How exercises work
 * Opens [ExerciseTutorialPicker], and a pick opens that exercise's [ExerciseTutorialDialog] with
 * no checkbox and "Got it": someone opening it on purpose has nothing to opt out of, and nothing
 * is persisted from this path. Both dialogs are local to this screen, like the emergency contact
 * one -- neither navigates anywhere, so `NavGraph.kt` doesn't need to know about them.
 *
 * @param onSignedOut invoked once sign-out completes, so the caller (`NavGraph.kt`) can navigate
 *   back to Welcome with a cleared back stack -- this screen doesn't know about routes at all.
 * @param onCreateAccountClick invoked when a guest taps "Create account"; the caller navigates to
 *   Sign up in upgrade mode.
 * @param onRecalibrateClick invoked when the "Recalibrate" row is tapped. Takes no exercise type:
 *   this screen has no notion of "the current exercise" the way Home's chips do, so the caller
 *   (`NavGraph.kt`) is the one that shows an exercise picker and decides where to navigate once
 *   one is chosen.
 */
@Composable
fun ProfileScreen(
    onSignedOut: () -> Unit,
    onRecalibrateClick: () -> Unit,
    onCreateAccountClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var showDisclaimer by remember { mutableStateOf(false) }
    var showPermissionDeniedNotice by remember { mutableStateOf(false) }
    var showEmergencyContactDialog by remember { mutableStateOf(false) }
    var showTutorialPicker by remember { mutableStateOf(false) }
    var tutorialExercise by remember { mutableStateOf<ExerciseType?>(null) }
    var showSignOutConfirmation by remember { mutableStateOf(false) }

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            // SEND_SMS is the only one that gates the feature -- see this file's KDoc.
            if (grants[Manifest.permission.SEND_SMS] == true) {
                viewModel.onSafetyCheckInToggled(true)
                // Check-in with nobody to alert does nothing when it escalates (see
                // CheckInEscalateWorker), so ask for the contact now rather than leave the user
                // believing they are covered.
                if (uiState.emergencyContactName.isBlank() || uiState.emergencyContactPhone.isBlank()) {
                    showEmergencyContactDialog = true
                }
            } else {
                showPermissionDeniedNotice = true
            }
        }

    LaunchedEffect(viewModel) {
        viewModel.signedOut.collect { onSignedOut() }
    }

    // Runs again on every return to this screen (e.g. back from Sign up after upgrading a guest).
    LaunchedEffect(viewModel) { viewModel.refreshAccount() }

    ProfileContent(
        uiState = uiState,
        onSignOutClicked = { showSignOutConfirmation = true },
        onCreateAccountClicked = onCreateAccountClick,
        onHapticFeedbackToggled = viewModel::onHapticFeedbackToggled,
        onSpokenRepCountToggled = viewModel::onSpokenRepCountToggled,
        onDarkThemeToggled = viewModel::onDarkThemeToggled,
        onRecalibrateClick = onRecalibrateClick,
        onSafetyCheckInToggled = { turningOn ->
            if (turningOn) {
                showDisclaimer = true
            } else {
                viewModel.onSafetyCheckInToggled(false)
            }
        },
        onEmergencyContactClick = { showEmergencyContactDialog = true },
        onHowExercisesWorkClick = { showTutorialPicker = true },
        modifier = modifier,
    )

    if (showSignOutConfirmation) {
        SignOutConfirmationDialog(
            onConfirm = {
                showSignOutConfirmation = false
                viewModel.onSignOutClicked()
            },
            onDismiss = { showSignOutConfirmation = false },
        )
    }

    if (showDisclaimer) {
        SafetyCheckInDisclaimerDialog(
            onConfirm = {
                showDisclaimer = false
                val permissions =
                    buildList {
                        add(Manifest.permission.SEND_SMS)
                        add(Manifest.permission.ACCESS_FINE_LOCATION)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                permissionLauncher.launch(permissions.toTypedArray())
            },
            onDismiss = { showDisclaimer = false },
        )
    }

    if (showPermissionDeniedNotice) {
        AlertDialog(
            onDismissRequest = { showPermissionDeniedNotice = false },
            confirmButton = {
                TextButton(onClick = { showPermissionDeniedNotice = false }) { Text("OK") }
            },
            title = { Text("Safety check-in stays off") },
            text = {
                Text(
                    "Without permission to send a text message, RepMate can't alert your " +
                        "emergency contact, so this feature needs to stay off. You can turn it " +
                        "back on any time.",
                )
            },
        )
    }

    if (showEmergencyContactDialog) {
        EmergencyContactDialog(
            initialName = uiState.emergencyContactName,
            initialPhone = uiState.emergencyContactPhone,
            onSave = { name, phone ->
                viewModel.onEmergencyContactSaved(name, phone)
                showEmergencyContactDialog = false
            },
            onDismiss = { showEmergencyContactDialog = false },
        )
    }

    if (showTutorialPicker) {
        ExerciseTutorialPicker(
            onExerciseSelected = { exerciseType ->
                showTutorialPicker = false
                tutorialExercise = exerciseType
            },
            onDismissRequest = { showTutorialPicker = false },
        )
    }

    tutorialExercise?.let { exerciseType ->
        // "Got it" and back both just close it: there's no checkbox on this path, so the
        // dontShowAgainChecked argument is always false and deliberately ignored.
        ExerciseTutorialDialog(
            exerciseType = exerciseType,
            showDismissCheckbox = false,
            ctaLabel = "Got it",
            onContinue = { tutorialExercise = null },
            onCancel = { tutorialExercise = null },
        )
    }
}

@Composable
private fun ProfileContent(
    uiState: ProfileUiState,
    onSignOutClicked: () -> Unit,
    onCreateAccountClicked: () -> Unit,
    onHapticFeedbackToggled: (Boolean) -> Unit,
    onSpokenRepCountToggled: (Boolean) -> Unit,
    onDarkThemeToggled: (Boolean) -> Unit,
    onRecalibrateClick: () -> Unit,
    onSafetyCheckInToggled: (Boolean) -> Unit = {},
    onEmergencyContactClick: () -> Unit = {},
    onHowExercisesWorkClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ProfileHeader(
            name = uiState.name,
            avatarInitial = uiState.avatarInitial,
            caption = uiState.caption,
        )

        StatsCard(
            sessionsCount = uiState.sessionsCount,
            totalReps = uiState.totalReps,
            averageScore = uiState.averageScore,
        )

        SettingsSection(label = "preferences") {
            SettingsToggleRow(
                label = "Haptic feedback",
                checked = uiState.hapticFeedbackEnabled,
                onCheckedChange = onHapticFeedbackToggled,
            )
            SettingsDivider()
            SettingsToggleRow(
                label = "Spoken rep count",
                checked = uiState.spokenRepCountEnabled,
                onCheckedChange = onSpokenRepCountToggled,
            )
            SettingsDivider()
            SettingsToggleRow(
                label = "Dark theme",
                checked = uiState.darkThemeEnabled,
                onCheckedChange = onDarkThemeToggled,
            )
        }

        SettingsSection(label = "exercises") {
            SettingsNavigationRow(label = "How exercises work", onClick = onHowExercisesWorkClick)
            SettingsDivider()
            SettingsNavigationRow(label = "Recalibrate", onClick = onRecalibrateClick)
        }

        SettingsSection(label = "safety") {
            SettingsToggleRow(
                label = "Check-in alerts",
                checked = uiState.safetyCheckInEnabled,
                onCheckedChange = onSafetyCheckInToggled,
            )
            SettingsDivider()
            SettingsNavigationRow(label = "Emergency contact", onClick = onEmergencyContactClick)
        }

        SettingsSection(label = "account") {
            SettingsNavigationRow(label = "Friends")
        }

        // Outside every card and full width, so the one destructive/committal action is not
        // mistaken for a settings row. A guest gets "Create account" here instead of "Sign out":
        // signing out of an anonymous account would discard it for good.
        if (uiState.isGuest) {
            RepMateButton(text = "Create account", onClick = onCreateAccountClicked)
        } else {
            SignOutButton(onClick = onSignOutClicked)
        }
    }
}

/** Full-width outlined button in the error colour role -- deliberately not the filled primary style. */
@Composable
private fun SignOutButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        contentPadding = PaddingValues(vertical = 14.dp, horizontal = 24.dp),
    ) {
        Text("Sign out")
    }
}

/** Asked before signing out, since it returns the user to Welcome and clears the back stack. */
@Composable
private fun SignOutConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sign out?") },
        text = { Text("You'll return to the welcome screen and need to log in again to come back.") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Sign out", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ProfileHeader(
    name: String,
    avatarInitial: String?,
    caption: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Same derivation and same fallback shape as Home's top-bar avatar (see
        // com.repmate.ui.auth.accountDisplayFor) -- just bigger and not tappable, since this
        // screen already is the destination that avatar links to.
        Box(
            modifier =
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (avatarInitial != null) {
                Text(
                    text = avatarInitial,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(36.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = caption,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StatsCard(
    sessionsCount: Int,
    totalReps: Int,
    averageScore: Float,
    modifier: Modifier = Modifier,
) {
    RepMateCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            StatColumn(value = sessionsCount.toString(), label = "sessions")
            StatColumn(value = totalReps.toString(), label = "total reps")
            // A brand-new user has no reps, so there is no score to show yet -- a dash, not "0.0".
            val averageText = if (totalReps == 0) "–" else String.format(Locale.US, "%.1f", averageScore)
            StatColumn(value = averageText, label = "avg score")
        }
    }
}

@Composable
private fun StatColumn(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsSection(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        RepMateCard {
            content()
        }
    }
}

@Composable
private fun SettingsDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(vertical = 12.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * A settings row with a trailing [Switch]. Leaving [onCheckedChange] null (the default) is
 * Compose's documented pattern for a non-interactive [Switch] -- it renders [checked]'s value
 * without responding to taps or drags, unlike passing a no-op lambda, which would still let the
 * thumb visually drag and then snap back. Every toggle on this screen now passes a real
 * [onCheckedChange]; the null default stays for any future placeholder row.
 *
 * Explicit checked-state colors, set once here so every toggle on this screen matches: Material
 * 3's own [SwitchDefaults.colors] default checkedThumbColor is `colorScheme.onPrimary`, which in
 * this theme is a near-black text-on-lime-accent color, not something meant to double as a knob
 * -- against the lime checked track it reads as a plain black dot. A literal white thumb against
 * [MaterialTheme.colorScheme.primary]'s lime track reads as an actual toggle knob instead.
 * `primary` is the same lime value in both light and dark ([com.repmate.ui.theme.LimeAccent]), so this
 * checked-state pairing needs no light/dark branching to look right in both. Unchecked
 * thumb/track are left at [SwitchDefaults]' own values, which is exactly the already-correct
 * neutral-gray off-state look every toggle on this screen already has.
 */
@Composable
private fun SettingsToggleRow(
    label: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    onCheckedChange: ((Boolean) -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors =
                SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                ),
        )
    }
}

/**
 * A settings row that shows a trailing chevron (or none, for an action row).
 * Non-null [onClick] rows are real; the rest ([onClick] left null) are static placeholders for
 * this first pass, per this screen's explicit scoping (today that is just "Friends").
 */
@Composable
private fun SettingsNavigationRow(
    label: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = true,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .let { rowModifier ->
                    if (onClick != null) {
                        rowModifier.clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
                    } else {
                        rowModifier
                    }
                },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (showChevron) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Shown once, before the permission request, when turning safety check-in on. */
@Composable
private fun SafetyCheckInDisclaimerDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Turn on safety check-in?") },
        text = {
            Text(
                "After each workout, RepMate asks if you're OK. If you don't answer within about " +
                    "${checkInWindowMinutes()} minutes, it texts your emergency contact your last known " +
                    "location. This is not an emergency service. In an emergency, call 000 or use " +
                    "your phone's built-in SOS.",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Continue") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * How long after finishing a workout the emergency contact is texted if the user has not answered:
 * the check-in delay plus the response window. Derived from [CheckInScheduler]'s defaults, not
 * hardcoded, so the copy in the dialogs below cannot drift from the real timing.
 */
private fun checkInWindowMinutes(): Long =
    (CheckInScheduler.DEFAULT_CHECK_IN_DELAY + CheckInScheduler.DEFAULT_RESPONSE_WINDOW).inWholeMinutes

/**
 * The one emergency contact safety check-in alerts -- name and phone number, both required to
 * save. The number is cleaned and checked by [normalizePhoneNumber] on Save, and the cleaned form
 * is what [onSave] receives (and so what is stored and shown again next time).
 */
@Composable
private fun EmergencyContactDialog(
    initialName: String,
    initialPhone: String,
    onSave: (name: String, phone: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var phone by remember { mutableStateOf(initialPhone) }
    var phoneError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Emergency contact") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text =
                        "If you don't tap \"I'm OK\" within about ${checkInWindowMinutes()} minutes of " +
                            "finishing a workout, RepMate sends this person a text message with your " +
                            "last known location. Standard SMS rates may apply.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = {
                        phone = it
                        phoneError = null
                    },
                    label = { Text("Phone number") },
                    singleLine = true,
                    isError = phoneError != null,
                    supportingText = { Text(phoneError ?: "Include the country code, e.g. +61 412 345 678") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val cleaned = normalizePhoneNumber(phone)
                    if (cleaned == null) {
                        phoneError = "Enter a valid phone number, including the country code"
                    } else {
                        onSave(name.trim(), cleaned)
                    }
                },
                enabled = name.isNotBlank() && phone.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private val PREVIEW_STATE =
    ProfileUiState(
        name = "jordan@example.com",
        avatarInitial = "J",
        caption = "Signed in with email",
        sessionsCount = 24,
        totalReps = 486,
        averageScore = 8.1f,
        safetyCheckInEnabled = false,
        hapticFeedbackEnabled = true,
        spokenRepCountEnabled = false,
        darkThemeEnabled = true,
    )

private val GUEST_PREVIEW_STATE =
    PREVIEW_STATE.copy(
        name = "Guest",
        avatarInitial = null,
        caption = "Guest session · create an account to keep your progress",
        isGuest = true,
        sessionsCount = 3,
        totalReps = 30,
    )

@Composable
private fun ProfilePreviewBody(uiState: ProfileUiState) {
    ProfileContent(
        uiState = uiState,
        onSignOutClicked = {},
        onCreateAccountClicked = {},
        onHapticFeedbackToggled = {},
        onSpokenRepCountToggled = {},
        onDarkThemeToggled = {},
        onRecalibrateClick = {},
    )
}

@Preview(name = "Signed in - Light", showBackground = true, widthDp = 360, heightDp = 1000)
@Composable
private fun ProfileScreenLightPreview() {
    RepMateTheme(darkTheme = false) { ProfilePreviewBody(PREVIEW_STATE) }
}

@Preview(name = "Signed in - Dark", showBackground = true, widthDp = 360, heightDp = 1000)
@Composable
private fun ProfileScreenDarkPreview() {
    RepMateTheme(darkTheme = true) { ProfilePreviewBody(PREVIEW_STATE) }
}

@Preview(name = "Guest - Light", showBackground = true, widthDp = 360, heightDp = 1000)
@Composable
private fun ProfileScreenGuestLightPreview() {
    RepMateTheme(darkTheme = false) { ProfilePreviewBody(GUEST_PREVIEW_STATE) }
}

@Preview(name = "Guest - Dark", showBackground = true, widthDp = 360, heightDp = 1000)
@Composable
private fun ProfileScreenGuestDarkPreview() {
    RepMateTheme(darkTheme = true) { ProfilePreviewBody(GUEST_PREVIEW_STATE) }
}
