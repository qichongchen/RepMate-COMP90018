package com.repmate.ui.displayname

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.repmate.data.repo.ClaimResult
import com.repmate.data.repo.DisplayNameRules
import com.repmate.data.repo.NameAvailability
import com.repmate.data.repo.NameCheck
import com.repmate.data.repo.UsernameRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Milliseconds of typing silence before the server is asked whether a name is free. */
internal const val AVAILABILITY_DEBOUNCE_MS = 400L

/** [ChooseDisplayNameScreen]'s state. */
data class ChooseNameUiState(
    val mode: ChooseNameMode = ChooseNameMode.GATE,
    val name: String = "",
    val hint: NameHint = NameHint.IDLE,
    /** A claim request is in flight. */
    val isSubmitting: Boolean = false,
) {
    /** Continue only when the name is known-good and nothing is already running. */
    val canContinue: Boolean
        get() = !isSubmitting && (hint == NameHint.AVAILABLE || hint == NameHint.MINE)
}

/**
 * Backs the choose-name screen in all three [ChooseNameMode]s.
 *
 * Typing runs the local rules immediately (so a bad format shows at once), and only a locally
 * valid name is sent to the server, after [AVAILABILITY_DEBOUNCE_MS] of quiet, with older
 * in-flight checks cancelled. Continue is enabled only for AVAILABLE or MINE.
 */
@HiltViewModel
class ChooseDisplayNameViewModel
    @Inject
    constructor(
        private val repository: UsernameRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(ChooseNameUiState())
        val uiState: StateFlow<ChooseNameUiState> = _uiState.asStateFlow()

        private val _finished = Channel<Unit>(Channel.BUFFERED)

        /** Fires once when the name is claimed (or already the user's). The screen decides where to go. */
        val finished = _finished.receiveAsFlow()

        private val pendingChecks = MutableStateFlow<String?>(null)
        private var started = false

        init {
            observeChecks()
        }

        /**
         * Sets the mode and prefill. Called by the screen on entry; only the first call counts, so
         * a recomposition or rotation cannot overwrite what the user has typed.
         */
        fun start(mode: ChooseNameMode) {
            if (started) return
            started = true
            val prefill =
                when (mode) {
                    ChooseNameMode.RENAME -> repository.currentDisplayName().orEmpty()
                    else -> DisplayNameRules.suggestFrom(repository.authNameForSuggestion()).orEmpty()
                }
            _uiState.update { it.copy(mode = mode) }
            onNameChanged(prefill)
        }

        fun onNameChanged(value: String) {
            when (val local = localHint(value)) {
                null -> {
                    _uiState.update { it.copy(name = value, hint = NameHint.CHECKING) }
                    pendingChecks.value = value
                }
                else -> {
                    pendingChecks.value = null
                    _uiState.update { it.copy(name = value, hint = local) }
                }
            }
        }

        fun onContinueClicked() {
            val state = _uiState.value
            if (!state.canContinue) return
            val name = state.name
            // Re-confirming exactly the name the account already shows needs no write at all.
            if (state.hint == NameHint.MINE && name == repository.currentDisplayName()) {
                _finished.trySend(Unit)
                return
            }
            _uiState.update { it.copy(isSubmitting = true) }
            viewModelScope.launch {
                val hint =
                    when (val result = repository.claim(name)) {
                        ClaimResult.Success -> {
                            _uiState.update { it.copy(isSubmitting = false) }
                            _finished.send(Unit)
                            return@launch
                        }
                        // A lost race reads exactly like a name that was already taken.
                        ClaimResult.Taken -> NameHint.TAKEN
                        is ClaimResult.Invalid -> hintFor(result.reason)
                        ClaimResult.Error -> NameHint.CANT_CHECK
                    }
                _uiState.update { it.copy(isSubmitting = false, hint = hint) }
            }
        }

        @OptIn(FlowPreview::class)
        private fun observeChecks() {
            viewModelScope.launch {
                pendingChecks
                    .filterNotNull()
                    .debounce(AVAILABILITY_DEBOUNCE_MS)
                    .collectLatest { name ->
                        val hint = hintFor(repository.checkAvailability(name))
                        // Drop a late answer for text the user has since changed.
                        _uiState.update { if (it.name == name) it.copy(hint = hint) else it }
                    }
            }
        }

        /** The hint decidable without the network, or null if the server must be asked. */
        private fun localHint(value: String): NameHint? =
            if (value.isEmpty()) {
                NameHint.IDLE
            } else {
                when (DisplayNameRules.validate(value)) {
                    NameCheck.VALID -> null
                    NameCheck.INVALID_FORMAT -> NameHint.INVALID_FORMAT
                    NameCheck.RESERVED -> NameHint.UNAVAILABLE
                }
            }

        private fun hintFor(check: NameCheck): NameHint =
            when (check) {
                NameCheck.VALID -> NameHint.AVAILABLE
                NameCheck.INVALID_FORMAT -> NameHint.INVALID_FORMAT
                NameCheck.RESERVED -> NameHint.UNAVAILABLE
            }

        private fun hintFor(availability: NameAvailability): NameHint =
            when (availability) {
                NameAvailability.Available -> NameHint.AVAILABLE
                NameAvailability.Mine -> NameHint.MINE
                NameAvailability.Taken -> NameHint.TAKEN
                is NameAvailability.Invalid -> hintFor(availability.reason)
                NameAvailability.Error -> NameHint.CANT_CHECK
            }
    }
