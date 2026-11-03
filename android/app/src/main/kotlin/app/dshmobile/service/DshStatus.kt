package app.dshmobile.service

/** Milliseconds from a monotonic clock; [DshStatus.sinceMs] and the screens that count from it use the same one. */
fun monotonicMs(): Long = System.nanoTime() / 1_000_000

/** Where dsh's life is right now (stage 2 §A/§G: the restart says exactly what it is doing). */
enum class DshPhase {
    STOPPED,
    /** Ending the old dsh's process tree. */
    STOPPING,
    /** Before a start: ending leftover dsh processes nobody tracks. */
    SWEEPING,
    /** proot and dsh launched; waiting for dsh's launch line. */
    STARTING,
    /** Asking dsh on its port whether it is healthy. */
    HEALTH_CHECK,
    /** Registering the workspace in dsh. */
    REGISTERING,
    RUNNING,
}

/** Why a stop or a start failed, in terms the screens put into plain words. */
enum class DshFailReason {
    /** Old processes survived every signal ([DshFailure.number]: how many). */
    STILL_RUNNING,
    /** No launch line in time ([DshFailure.number]: the limit in seconds). */
    LAUNCH_TIMEOUT,
    /** dsh ended before it was ready ([DshFailure.number]: its exit code). */
    EXITED_EARLY,
    HEALTH_FAILED,
    ROOTFS_MISSING,
    /** proot itself could not be started. */
    LAUNCH_ERROR,
    OTHER,
}

/** The step that failed and why. */
data class DshFailure(val step: DshPhase, val reason: DshFailReason, val number: Int? = null)

/**
 * The controller's detailed state: the [phase] since [sinceMs] ([monotonicMs]), the dsh [port] being checked, how many processes
 * are still [remaining] while stopping or sweeping, how many old ones are [leftover] after a stop that could not end them (no
 * start happens until they are gone) and the [failure] of the last stop or start (cleared by the next success).
 */
data class DshStatus(
    val phase: DshPhase,
    val sinceMs: Long,
    val port: Int? = null,
    val remaining: Int = 0,
    val leftover: Int = 0,
    val failure: DshFailure? = null,
) {
    /** A stop or start is under way (what the restart progress shows). */
    val busy: Boolean get() = phase != DshPhase.STOPPED && phase != DshPhase.RUNNING
}

/** [DshController.ensureRunning] refused to start: [count] processes of an old dsh could not be ended. */
class DshStillRunningException(val count: Int) : IllegalStateException("$count old dsh processes are still running")
