package com.repmate.data.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [parseFirestoreRepScore] and the mapping to the Room rep entity, fed the values Firestore
 * actually returns: a Float that was uploaded comes back as a [Double], a whole number as a [Long].
 */
class FirestoreRepScoreParsingTest {
    private fun rawRep(
        score: Any?,
        repIndex: Any? = 1,
    ): Map<String, Any?> =
        mapOf(
            "repIndex" to repIndex,
            "score" to score,
            "tempoSeconds" to 1.5,
            "rangePercent" to 90L,
            "pauseSeconds" to 0.25,
            "reasons" to listOf("good depth"),
        )

    /** Parse, wrap in a session, and map to Room, the way `restoreMissingSessions` does. */
    private fun restoredScores(vararg scores: Any?): List<Float> {
        val reps = scores.mapIndexed { i, s -> parseFirestoreRepScore(rawRep(s, repIndex = i + 1))!! }
        val session =
            FirestoreWorkoutSession(
                id = "s",
                userId = "u",
                exercise = "SQUAT",
                startedAt = 0L,
                endedAt = null,
                reps = reps,
            )
        return session.toRoomRepScores().map { it.score }
    }

    @Test
    fun aFractionalScoreSurvivesParsingAndTheRoomMappingExactly() {
        // Firestore stores the uploaded Float as a double and returns a Double.
        val restored = restoredScores(7.3f.toDouble())

        assertEquals(listOf(7.3f), restored)
    }

    @Test
    fun aWholeNumberScoreFromAnOldDocumentStillParses() {
        assertEquals(7.0f, parseFirestoreRepScore(rawRep(7L))!!.score, 0f)
        assertEquals(7.0f, parseFirestoreRepScore(rawRep(7))!!.score, 0f)
        assertEquals(listOf(7.0f), restoredScores(7L))
    }

    @Test
    fun theAverageOfFractionalScoresIsUnchangedByTheRoundTrip() {
        val original = listOf(7.3f, 8.6f, 6.1f, 9.4f, 7.7f)

        val restored = restoredScores(*original.map { it.toDouble() }.toTypedArray())

        assertEquals(original, restored)
        assertEquals(original.map { it.toDouble() }.average(), restored.map { it.toDouble() }.average(), 0.0)
    }

    @Test
    fun theOldTruncationWouldHaveChangedTheAverage() {
        // Guards the test above: the same scores rounded the old way do not average the same, so
        // the round-trip assertion would fail if the Int narrowing came back.
        val original = listOf(7.3f, 8.6f, 6.1f)
        val truncated = original.map { it.toInt().toFloat() }

        assertNotEquals(original.average(), truncated.average(), 0.0)
    }

    @Test
    fun aMissingScoreSkipsTheRepAsBefore() {
        assertNull(parseFirestoreRepScore(mapOf("repIndex" to 1L, "tempoSeconds" to 1.5)))
        assertNull(parseFirestoreRepScore(rawRep(score = null)))
    }

    @Test
    fun aNonNumericScoreSkipsTheRepAsBefore() {
        assertNull(parseFirestoreRepScore(rawRep(score = "7.3")))
        assertNull(parseFirestoreRepScore(rawRep(score = true)))
    }

    @Test
    fun aMissingOrNonNumericRepIndexOrANonMapSkipsTheRepAsBefore() {
        assertNull(parseFirestoreRepScore(rawRep(score = 7.3, repIndex = null)))
        assertNull(parseFirestoreRepScore(rawRep(score = 7.3, repIndex = "1")))
        assertNull(parseFirestoreRepScore("not a map"))
        assertNull(parseFirestoreRepScore(null))
    }

    @Test
    fun theOtherFieldsAreReadAsBeforeAndDefaultToZeroWhenMissing() {
        val full = parseFirestoreRepScore(rawRep(score = 7.3))!!
        assertEquals(1, full.repIndex)
        assertEquals(1.5, full.tempoSeconds, 0.0)
        assertEquals(90.0, full.rangePercent, 0.0)
        assertEquals(0.25, full.pauseSeconds, 0.0)
        assertEquals(listOf("good depth"), full.reasons)

        val bare = parseFirestoreRepScore(mapOf("repIndex" to 2L, "score" to 5.5))
        assertNotNull(bare)
        assertEquals(0.0, bare!!.tempoSeconds, 0.0)
        assertEquals(0.0, bare.rangePercent, 0.0)
        assertEquals(0.0, bare.pauseSeconds, 0.0)
        assertEquals(emptyList<String>(), bare.reasons)
    }
}
