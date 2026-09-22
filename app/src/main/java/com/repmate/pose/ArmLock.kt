package com.repmate.pose

/**
 * Picks one arm at the start of a set and sticks with it. Pure Kotlin, no Android or ML Kit types
 * -- see [PoseGeometry.kt][elbowAngleDegrees] for why that split exists.
 *
 * ## Why the arm is locked instead of chosen every frame
 * Re-picking the nearer arm on every frame, measured against a real recorded set, switched **70
 * times in 60 seconds**. Every switch swaps one arm's angle for the other's, and the angle steps
 * sharply at a switch -- noise [com.repmate.engine.PushupRepDetector] then has to fight, on top of
 * what its own median filter already absorbs. The two arms' depth estimates are also noisy enough
 * moment to moment that no per-frame rule settles the question cleanly. Deciding **once**, from
 * evidence over a short window, can.
 *
 * ## The rule
 * 1. For the first [settleFrames] frames in which a pose was found, collect each arm's mean depth
 *    ([Arm.meanZ]; smaller is nearer the camera).
 * 2. Lock to the arm with the lower **median** depth. A median, not a mean, so one frame with a
 *    wild depth estimate cannot decide it.
 * 3. From then on, [armFor] returns that side's arm and nothing else, until [reset]. If the locked
 *    arm is not in a frame, the answer is null: falling back to the other arm would reintroduce
 *    exactly the switch this class exists to prevent.
 *
 * ## What it does not do
 * It does not check that the locked arm was the *right* one to lock to. If the user moves after
 * the lock, or the depth estimate at the start was wrong, the choice stays wrong until [reset] --
 * which the workout screen calls on Reset and at the start of every new set.
 *
 * @param settleFrames how many frames with a pose to observe before locking.
 */
class ArmLock(private val settleFrames: Int = DEFAULT_SETTLE_FRAMES) {
    init {
        require(settleFrames >= 1) { "settleFrames must be at least 1, got $settleFrames" }
    }

    /** The locked side, or null while still choosing. */
    var side: Arm.Side? = null
        private set

    val isLocked: Boolean get() = side != null

    /** Frames with a pose seen so far while choosing. */
    var framesObserved: Int = 0
        private set

    private val leftDepths = ArrayList<Float>()
    private val rightDepths = ArrayList<Float>()

    /** The median depths the decision was made on, for logging; null until locked. */
    var lockedDepths: Pair<Float?, Float?>? = null
        private set

    /**
     * Adds one frame to the evidence, locking once [settleFrames] frames with a pose have been
     * seen. Does nothing once locked, and a frame with no pose at all is not evidence.
     */
    fun observe(left: Arm?, right: Arm?) {
        if (isLocked || (left == null && right == null)) return
        framesObserved++
        left?.let { leftDepths += it.meanZ }
        right?.let { rightDepths += it.meanZ }
        if (framesObserved >= settleFrames) lock()
    }

    /**
     * The arm to read this frame: the locked side's arm, which is null if that arm is missing --
     * never the other one. Null while still choosing, so the caller decides what to show meanwhile.
     */
    fun armFor(left: Arm?, right: Arm?): Arm? = when (side) {
        Arm.Side.LEFT -> left
        Arm.Side.RIGHT -> right
        null -> null
    }

    /** Forgets the lock and the evidence, so the next set chooses afresh. */
    fun reset() {
        side = null
        framesObserved = 0
        leftDepths.clear()
        rightDepths.clear()
        lockedDepths = null
    }

    private fun lock() {
        val left = median(leftDepths)
        val right = median(rightDepths)
        side =
            when {
                left == null -> Arm.Side.RIGHT
                right == null -> Arm.Side.LEFT
                right < left -> Arm.Side.RIGHT
                else -> Arm.Side.LEFT // an exact tie goes to the left
            }
        lockedDepths = left to right
    }

    private fun median(values: List<Float>): Float? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
    }

    companion object {
        /** About 0.6 s at ~24 analysed frames per second. */
        const val DEFAULT_SETTLE_FRAMES = 15
    }
}
