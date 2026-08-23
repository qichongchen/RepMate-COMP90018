package engine

fun main() {
    val reps = 10
    val trace = syntheticSquatTrace(reps = reps)

    println("Generated a synthetic squat trace")
    println("Ground-truth reps: $reps")
    println("Total frames:      ${trace.size}  (expected ${reps} * 100 = ${reps * 100})")
    println("Duration:          ${trace.last().tMillis / 1000.0} seconds")
    println()
    println("First 3 frames:")
    trace.take(3).forEach { println("  $it") }
    println()
    println("Vertical acceleration (ay) across the first rep — should dip below 9.8 then rise above it:")
    for (i in 0 until 100 step 12) {
        println("  sample %3d: ay = %.2f".format(i, trace[i].ay))
    }
}