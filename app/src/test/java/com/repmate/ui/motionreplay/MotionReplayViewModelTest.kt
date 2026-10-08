package com.repmate.ui.motionreplay

import com.repmate.data.memory.JustFinishedSessionStore
import com.repmate.data.repo.BestWorkoutScore
import com.repmate.data.repo.CalibrationRepository
import com.repmate.data.repo.SessionRepository
import com.repmate.engine.CalibrationProfile
import com.repmate.engine.ExerciseType
import com.repmate.engine.RepScore
import com.repmate.engine.WorkoutSession
import com.repmate.engine.syntheticSquatTrace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MotionReplayViewModel]'s two sources: the just-finished squat set held in memory by
 * [JustFinishedSessionStore], which can draw a real curve, and anything read back from Room,
 * which cannot because no frames are stored.
 *
 * `isReplayAvailable` was false for every session before the in-memory path existed, so these
 * tests exist to pin that it is now true for exactly one session and still false for the rest.
 * Frames come from [syntheticSquatTrace], the fixture the detector's own replay tests use, so the
 * frames contain real rep cycles rather than noise.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MotionReplayViewModelTest {
    private val store = JustFinishedSessionStore()
    private val sessions = FakeSessionRepository()
    private val calibration = FakeCalibrationRepository()

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun theJustFinishedSquatSetReplaysInsteadOfShowingTheNoDataState() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            store.remember(squatSession(SESSION_ID, withFrames = true))

            val uiState = replayStateFor(SESSION_ID)

            assertTrue("the in-memory session should replay", uiState.isReplayAvailable)
            assertTrue("expected replayed reps", uiState.reps.isNotEmpty())
            assertEquals(SESSION_ID, uiState.sessionId)
            assertEquals(ExerciseType.SQUAT, uiState.exercise)
        }

    @Test
    fun aCalibratedSetReplaysEveryRepWithItsCurveAndCalibrationBand() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            calibration.profile = PROFILE
            store.remember(squatSession(SESSION_ID, withFrames = true))

            val reps = replayStateFor(SESSION_ID).reps
            val calibrated = reps.filterIsInstance<MotionReplayRepUi.Calibrated>()

            assertEquals("every rep should be calibrated when a profile exists", reps.size, calibrated.size)
            // An empty curve or a collapsed band would render as a blank chart, which is the thing
            // this feature exists to stop.
            assertTrue(calibrated.all { it.curve.isNotEmpty() })
            assertTrue(calibrated.all { it.calibrationBand.start < it.calibrationBand.endInclusive })
        }

    @Test
    fun anUncalibratedSetStillReplays() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            // No profile: the curve is still real, there is just no band to compare it against.
            calibration.profile = null
            store.remember(squatSession(SESSION_ID, withFrames = true))

            val uiState = replayStateFor(SESSION_ID)

            assertTrue(uiState.isReplayAvailable)
            assertTrue(uiState.reps.all { it is MotionReplayRepUi.Uncalibrated })
        }

    @Test
    fun aSessionReadBackFromRoomHasNoReplayData() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            // Room saves frames = null, so this is every session except the one just finished.
            sessions.stored = squatSession(OTHER_SESSION_ID, withFrames = false)

            val uiState = replayStateFor(OTHER_SESSION_ID)

            assertFalse(uiState.isReplayAvailable)
            assertTrue(uiState.reps.isEmpty())
        }

    @Test
    fun theStoredSessionIsNotUsedForADifferentSessionId() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            store.remember(squatSession(SESSION_ID, withFrames = true))
            sessions.stored = squatSession(OTHER_SESSION_ID, withFrames = false)

            // Opening an older session must not draw the just-finished set's curve under its id.
            assertFalse(replayStateFor(OTHER_SESSION_ID).isReplayAvailable)
        }

    @Test
    fun startingTheNextWorkoutSendsTheSetBackToTheNoReplayState() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            store.remember(squatSession(SESSION_ID, withFrames = true))
            // What LiveWorkoutViewModel.onExerciseType does when the next set starts.
            store.clear()
            sessions.stored = squatSession(SESSION_ID, withFrames = false)

            assertFalse(replayStateFor(SESSION_ID).isReplayAvailable)
        }

    @Test
    fun theStoreKeepsBothImuExercisesAndRefusesPushUps() {
        // Squats and jumping jacks both replay: SessionReplayer picks the detector that counted
        // the set, and both read the same smoothed magnitude. Push-ups are counted from the
        // camera and their frames describe a phone held still, so there is nothing to replay.
        store.remember(squatSession(SESSION_ID, withFrames = true))
        assertNotNull(store.sessionFor(SESSION_ID))

        store.clear()
        store.remember(squatSession(SESSION_ID, withFrames = true, exercise = ExerciseType.JUMPING_JACK))
        assertNotNull(store.sessionFor(SESSION_ID))

        store.clear()
        store.remember(squatSession(SESSION_ID, withFrames = true, exercise = ExerciseType.PUSHUP))
        assertNull(store.sessionFor(SESSION_ID))
    }

    @Test
    fun aPushUpSetFallsBackToTheNoReplayState() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            store.remember(squatSession(SESSION_ID, withFrames = true, exercise = ExerciseType.PUSHUP))
            sessions.stored = squatSession(SESSION_ID, withFrames = false, exercise = ExerciseType.PUSHUP)

            assertFalse(replayStateFor(SESSION_ID).isReplayAvailable)
        }

    private fun TestScope.replayStateFor(sessionId: String): MotionReplayUiState {
        val viewModel = MotionReplayViewModel(sessions, calibration, store)
        viewModel.onSessionId(sessionId)
        advanceUntilIdle()
        // Null is the "session not found" state, which these tests never set up: every case
        // either stores the session in memory or in the fake repository.
        return requireNotNull(viewModel.screenState.value.uiState) { "session $sessionId was not found" }
    }

    private companion object {
        const val SESSION_ID = "just-finished"
        const val OTHER_SESSION_ID = "from-history"

        /** Same shape as FormScorerTest's fixture: a plausible profile, not a derived one. */
        val PROFILE =
            CalibrationProfile(
                minAmplitude = 1.3f,
                minRepDurationMs = 500L,
                maxRepDurationMs = 2600L,
                cooldownMs = 700L,
                sampleCount = 5,
                softestSampleAmplitude = 2.0f,
                loudestSampleAmplitude = 2.5f,
                shortestSampleMs = 800L,
                longestSampleMs = 1500L,
                fastestSampleGapMs = 1000L,
                notes = emptyList(),
            )

        fun squatSession(
            id: String,
            withFrames: Boolean,
            exercise: ExerciseType = ExerciseType.SQUAT,
        ) = WorkoutSession(
            id = id,
            exercise = exercise,
            startedAt = 1_000L,
            endedAt = 2_000L,
            reps =
                List(3) { index ->
                    RepScore(
                        repIndex = index + 1,
                        score = 7.5f,
                        tempoSeconds = 1.5f,
                        rangePercent = 90,
                        pauseSeconds = 0f,
                        reasons = listOf("good depth"),
                    )
                },
            frames = if (withFrames) syntheticSquatTrace(reps = 6) else null,
        )
    }
}

private class FakeSessionRepository : SessionRepository {
    var stored: WorkoutSession? = null

    override suspend fun save(session: WorkoutSession) = Unit

    override fun recent(limit: Int): Flow<List<WorkoutSession>> = flowOf(listOfNotNull(stored))

    override suspend fun getById(id: String): WorkoutSession? = stored?.takeIf { it.id == id }

    override suspend fun getMyBestScore(exercise: ExerciseType): BestWorkoutScore? = null
}

private class FakeCalibrationRepository : CalibrationRepository {
    var profile: CalibrationProfile? = null

    override suspend fun hasProfile(exerciseType: ExerciseType): Boolean = profile != null

    override suspend fun saveProfile(
        exerciseType: ExerciseType,
        profile: CalibrationProfile,
    ) {
        this.profile = profile
    }

    override suspend fun getProfile(exerciseType: ExerciseType): CalibrationProfile? = profile
}
