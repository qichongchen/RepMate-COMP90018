package com.repmate.data.repo

import com.repmate.engine.WorkoutSession
import kotlinx.coroutines.flow.Flow

/**
 * The boundary between producing a workout and storing one.
 *
 * The engine's job ends with a [WorkoutSession]: frames come in, reps are detected and
 * scored, and a session falls out. What happens to it next — Room, Firestore, both, neither
 * — is not the engine's business and this interface is where that stops being its business.
 *
 * ## Why this interface exists rather than a direct call to a DAO
 * Without it, anything that finishes a workout has to know how workouts are stored. A
 * ViewModel would import a Room DAO, the storage decision would be duplicated at every call
 * site, and changing it later would mean touching all of them. With it there is one seam:
 * callers depend on this file, and exactly one implementation depends on the database.
 *
 * It also lets the two halves be built independently. A caller can work against a ten-line
 * in-memory fake from the first day, long before any table exists, and swap to the real
 * implementation through Hilt without changing a line of calling code.
 *
 * ## Who owns which side
 * This contract is defined alongside the engine because [WorkoutSession] is an engine model
 * and frozen — the shape of what gets stored is settled before storage is. The
 * implementation belongs to the data layer, under `com.repmate.data.local` for Room and
 * `com.repmate.cloud` for anything remote, and is bound to this interface by a Hilt module.
 *
 * **There is deliberately no implementation in this package.** An interface with one
 * obvious implementation beside it is just indirection; the point here is that the two
 * sides are written by different people against a fixed contract.
 *
 * ## What the implementation decides, and what it does not
 * Free to choose: the storage engine, the table or document shape, threading (both methods
 * may be called from the main dispatcher and must move their own work off it), caching, and
 * whether a session is also pushed to the cloud.
 *
 * Not free to choose: the three contracts below. They are what callers are allowed to assume.
 *
 * One open question is flagged rather than decided here, because it is a storage question:
 * [WorkoutSession.frames] can hold tens of thousands of samples — a 30-second recording at
 * ~99 Hz is roughly 3,000 frames, and it is nullable precisely so it can be dropped. An
 * implementation may persist it, drop it, or store it separately, but it should be a
 * decision taken knowingly rather than a `SELECT *` that turns every session read into
 * megabytes.
 */
interface SessionRepository {

    /**
     * Stores a completed session, replacing any session already held under the same
     * [WorkoutSession.id].
     *
     * Replace rather than append, so that saving the same session twice — a retry, a
     * recomposition, a process death and resume — leaves one row and not two.
     *
     * `suspend` because storage is I/O: the caller may invoke this from the main dispatcher
     * and the implementation is responsible for moving the work off it.
     *
     * Per the project's degrade-gracefully rule, a failure to store must not crash a
     * workout the user has already performed. An implementation that cannot write should
     * fail in a way the caller can survive rather than propagating a raw storage exception.
     *
     * @param session the finished workout, as the engine produced it.
     */
    suspend fun save(session: WorkoutSession)

    /**
     * Observes the most recent sessions, newest first by [WorkoutSession.startedAt].
     *
     * A [Flow] rather than a one-shot read because the natural consumer is a screen: it
     * should show a new session the moment one is saved, without being told to refresh.
     * Collecting emits the current contents immediately, then again on every change.
     *
     * Returns fewer than [limit] when fewer sessions exist, and an empty list when none do
     * — an empty history is a normal state for a new user, not an error.
     *
     * @param limit the maximum number of sessions to emit. Must be positive.
     */
    fun recent(limit: Int): Flow<List<WorkoutSession>>

    /**
     * Returns a single session by id for the current user.
     *
     * Returns null if the session does not exist or does not belong to the current user.
     */
    suspend fun getById(id: String): WorkoutSession?
}
