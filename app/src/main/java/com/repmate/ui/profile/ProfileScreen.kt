package com.repmate.ui.profile

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.repmate.engine.ExerciseType
import com.repmate.ui.components.RepMateCard
import com.repmate.ui.theme.RepMateTheme
import com.repmate.ui.tutorial.ExerciseTutorialDialog

/**
 * The Profile tab: an account header, a stats summary, and grouped settings rows. Hosts the
 * bottom nav bar -- but that's rendered by `RepMateNavGraph`'s Scaffold, not here, same as Home.
 *
 * Split into this stateful wrapper and the stateless [ProfileContent] below, same reasoning as
 * every other screen in this app: previews render from a plain [ProfileUiState], no Hilt required.
 *
 * For this first pass, only the header (account name/avatar/caption), "Sign out", the "Haptic
 * feedback"/"Spoken rep count"/"Dark theme" toggles, "Recalibrate", "How exercises work", and the
 * safety check-in section (toggle + emergency contact) are real -- the average score and the "Friends" row are
 * static placeholders. See the TODO on [ProfileUiState.averageScore] for what it should
 * eventually read from.
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
 * @param onRecalibrateClick invoked when the "Recalibrate" row is tapped. Takes no exercise type:
 *   this screen has no notion of "the current exercise" the way Home's chips do, so the caller
 *   (`NavGraph.kt`) is the one that shows an exercise picker and decides where to navigate once
 *   one is chosen.
 */
@Composable
fun ProfileScreen(
    onSignedOut: () -> Unit,
    onRecalibrateClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var showDisclaimer by remember { mutableStateOf(false) }
    var showPermissionDeniedNotice by remember { mutableStateOf(false) }
    var showEmergencyContactDialog by remember { mutableStateOf(false) }
    var showTutorialPicker by remember { mutableStateOf(false) }
    var tutorialExercise by remember { mutableStateOf<ExerciseType?>(null) }

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            // SEND_SMS is the only one that gates the feature -- see this file's KDoc.
            if (grants[Manifest.permission.SEND_SMS] == true) {
                viewModel.onSafetyCheckInToggled(true)
            } else {
                showPermissionDeniedNotice = true
            }
        }

    LaunchedEffect(viewModel) {
        viewModel.signedOut.collect { onSignedOut() }
    }

    ProfileContent(
        uiState = uiState,
        onSignOutClicked = viewModel::onSignOutClicked,
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

        SettingsSection(label = "safety check-in") {
            SettingsToggleRow(
                label = "Safety check-in",
                checked = uiState.safetyCheckInEnabled,
                onCheckedChange = onSafetyCheckInToggled,
            )
            SettingsDivider()
            SettingsNavigationRow(label = "Emergency contact", onClick = onEmergencyContactClick)
        }

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
            SettingsDivider()
            SettingsNavigationRow(label = "Recalibrate", onClick = onRecalibrateClick)
        }

        SettingsSection(label = "help") {
            SettingsNavigationRow(label = "How exercises work", onClick = onHowExercisesWorkClick)
        }

        SettingsSection(label = "account") {
            SettingsNavigationRow(label = "Friends")
            SettingsDivider()
            // The one real row on this screen for this first pass -- see ProfileScreen's KDoc.
            SettingsNavigationRow(
                label = "Sign out",
                onClick = onSignOutClicked,
                showChevron = false,
            )
        }
    }
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
            StatColumn(value = "%.1f".format(averageScore), label = "avg score")
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
 * A settings row that shows a trailing chevron (or none, for an action row like "Sign out").
 * Non-null [onClick] rows are real; the rest ([onClick] left null) are static placeholders for
 * this first pass, per this screen's explicit scoping -- see the TODOs on [ProfileUiState].
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
                "This notifies a contact you choose. It is not an emergency service. In an " +
                    "emergency, call 000 or use your phone's built-in SOS.",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Continue") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The one emergency contact safety check-in alerts -- name and phone number, both required to save. */
@Composable
private fun EmergencyContactDialog(
    initialName: String,
    initialPhone: String,
    onSave: (name: String, phone: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var phone by remember { mutableStateOf(initialPhone) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Emergency contact") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Phone number") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim(), phone.trim()) },
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

@Preview(name = "Light", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun ProfileScreenLightPreview() {
    RepMateTheme(darkTheme = false) {
        ProfileContent(
            uiState = PREVIEW_STATE,
            onSignOutClicked = {},
            onHapticFeedbackToggled = {},
            onSpokenRepCountToggled = {},
            onDarkThemeToggled = {},
            onRecalibrateClick = {},
        )
    }
}

@Preview(name = "Dark", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun ProfileScreenDarkPreview() {
    RepMateTheme(darkTheme = true) {
        ProfileContent(
            uiState = PREVIEW_STATE,
            onSignOutClicked = {},
            onHapticFeedbackToggled = {},
            onSpokenRepCountToggled = {},
            onDarkThemeToggled = {},
            onRecalibrateClick = {},
        )
    }
}
