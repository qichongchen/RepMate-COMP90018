package com.repmate.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.repmate.data.repo.SessionRepository
import com.repmate.data.sync.SyncingSessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Sessions to pull from Room per [SessionRepository.recent] call -- there is no pagination anywhere else in the app; 500 is far more than a uni-project's worth of workouts. */
private const val HISTORY_SESSION_LIMIT = 500

/**
 * Backs [HistoryScreen]: observes [SessionRepository.recent] and re-buckets it by calendar month
 * (see [groupSessionsIntoSections]) on every emission, so a freshly finished workout appears in
 * the list without the screen being told to refresh.
 */
@HiltViewModel
class HistoryViewModel
@Inject
constructor(
    private val sessionRepository: SessionRepository,
    private val syncingSessionRepository: SyncingSessionRepository,
) : ViewModel() {

    private companion object {
        const val TAG = "HistoryViewModel"
    }

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        // Keep observing Room, even when cloud restore fails.
        viewModelScope.launch {
            sessionRepository.recent(HISTORY_SESSION_LIMIT)
                .collect { sessions ->
                    _uiState.value = HistoryUiState(
                        sections = groupSessionsIntoSections(sessions),
                        isLoading = false
                    )
                }
        }

        // Restore missing cloud sessions without blocking local History.
        viewModelScope.launch {
            try {
                syncingSessionRepository.restoreMissingSessions()
                    .onFailure { error ->
                        Log.w(
                            TAG,
                            "Failed to restore History from Firestore",
                            error
                        )
                    }
            } catch (e: Exception) {
                Log.w(TAG, "Unexpected History restore failure", e)
            }
        }
    }
}
