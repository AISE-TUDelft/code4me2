package me.code4me.research.runtime

import java.nio.file.Path

/**
 * Process ownership for a launched agent/proxy (Issue 11).
 *
 * The launcher owns the root process *and* every descendant it spawned: it can
 * close the tree gracefully within a timeout, fall back to a forced kill, and
 * detect orphans left behind after shutdown. All OS interaction is behind the
 * injectable [ProcessHandle] abstraction, so the state machine is deterministic
 * and unit-testable without spawning real processes.
 */

/** One tracked process: its identity, parent link, and start time. */
data class ProcessNode(
    val pid: Long,
    val parentPid: Long?,
    val startedAtEpochMs: Long,
)

/** Terminal result of owning a process tree. */
enum class ProcessOutcome {
    /** Every tracked process exited after a graceful request. */
    EXITED_CLEAN,

    /** Graceful close timed out and a forced kill was required. */
    FORCED_KILLED,

    /** Processes survived even the forced kill and are now orphaned. */
    ORPHANED,

    /** The process could not be started at all. */
    FAILED_TO_START,
}

/**
 * Injectable process abstraction.
 *
 * [awaitExit] blocks up to [timeoutMs] and reports whether the process has
 * exited; implementations must not throw for an ordinary timeout.
 */
interface ProcessHandle {
    val pid: Long
    val parentPid: Long?
    val startedAtEpochMs: Long
    val isAlive: Boolean

    /** Every descendant (direct and transitive) known for this handle. */
    fun descendants(): List<ProcessHandle>

    /** Request a graceful shutdown; returns true when the request was delivered. */
    fun gracefulTerminate(): Boolean

    /** Forcefully terminate; returns true when the signal was delivered. */
    fun forceTerminate(): Boolean

    /** Wait up to [timeoutMs]; true when the process is no longer alive. */
    fun awaitExit(timeoutMs: Long): Boolean
}

/** Injectable process launcher used by the [ProcessTreeManager.start] factory. */
fun interface ProcessLauncher {
    fun start(
        command: List<String>,
        workingDirectory: Path?,
    ): ProcessHandle
}

/** The recorded result of a shutdown attempt. */
data class ProcessCloseReport(
    val outcome: ProcessOutcome,
    val trackedNodes: List<ProcessNode>,
    val forced: Boolean,
    val orphaned: List<ProcessNode>,
)

/**
 * Owns exactly one process tree and records its terminal [outcome] once.
 *
 * @property clock injectable wall clock in epoch milliseconds.
 */
class ProcessTreeManager private constructor(
    private val root: ProcessHandle?,
    private val clock: () -> Long,
    initialOutcome: ProcessOutcome?,
) {
    @Volatile
    private var terminalOutcome: ProcessOutcome? = initialOutcome

    @Volatile
    private var forcedKillUsed: Boolean = false

    /** The terminal outcome, or `null` while the tree is still running. */
    val outcome: ProcessOutcome?
        get() = terminalOutcome

    /** The root node, or `null` when the process never started. */
    val rootNode: ProcessNode?
        get() = root?.let { toNode(it) }

    /** Every tracked process (root plus descendants), newest snapshot. */
    fun trackedNodes(): List<ProcessNode> = collectHandles().map { toNode(it) }

    /**
     * Close the whole tree within [timeoutMs].
     *
     * Sends a graceful request to every tracked process, waits up to the shared
     * deadline, then force-kills any survivor. The outcome is terminal: calling
     * this again returns the recorded result unchanged.
     */
    fun closeGracefully(timeoutMs: Long): ProcessCloseReport {
        terminalOutcome?.let { recorded ->
            return ProcessCloseReport(
                outcome = recorded,
                trackedNodes = trackedNodes(),
                forced = forcedKillUsed,
                orphaned = orphanCheck(),
            )
        }
        if (root == null) {
            terminalOutcome = ProcessOutcome.FAILED_TO_START
            return ProcessCloseReport(ProcessOutcome.FAILED_TO_START, emptyList(), forced = false, orphaned = emptyList())
        }

        val handles = collectHandles()
        handles.forEach { it.gracefulTerminate() }

        val deadline = clock() + timeoutMs
        val gracefullyExited =
            handles.all { handle ->
                val remaining = (deadline - clock()).coerceAtLeast(0L)
                handle.awaitExit(remaining)
            }

        if (gracefullyExited) {
            terminalOutcome = ProcessOutcome.EXITED_CLEAN
            return ProcessCloseReport(
                outcome = ProcessOutcome.EXITED_CLEAN,
                trackedNodes = handles.map { toNode(it) },
                forced = false,
                orphaned = emptyList(),
            )
        }

        val survivors = handles.filter { it.isAlive }
        survivors.forEach { it.forceTerminate() }
        forcedKillUsed = true
        val forcedExited = survivors.all { it.awaitExit(timeoutMs) }
        terminalOutcome = if (forcedExited) ProcessOutcome.FORCED_KILLED else ProcessOutcome.ORPHANED

        return ProcessCloseReport(
            outcome = terminalOutcome!!,
            trackedNodes = handles.map { toNode(it) },
            forced = true,
            orphaned = orphanCheck(),
        )
    }

    /**
     * Processes still alive *after* a terminal shutdown. Returns an empty list
     * before shutdown, so a running tree is never mistaken for orphaned.
     */
    fun orphanCheck(): List<ProcessNode> {
        if (terminalOutcome == null) return emptyList()
        return collectHandles().filter { it.isAlive }.map { toNode(it) }
    }

    private fun collectHandles(): List<ProcessHandle> {
        val start = root ?: return emptyList()
        val result = mutableListOf<ProcessHandle>()
        val visited = mutableSetOf<Long>()
        val queue = ArrayDeque<ProcessHandle>()
        queue.add(start)
        while (queue.isNotEmpty()) {
            val handle = queue.removeFirst()
            if (!visited.add(handle.pid)) continue
            result += handle
            handle.descendants().forEach { child ->
                if (child.pid !in visited) queue.add(child)
            }
        }
        return result
    }

    private fun toNode(handle: ProcessHandle): ProcessNode =
        ProcessNode(pid = handle.pid, parentPid = handle.parentPid, startedAtEpochMs = handle.startedAtEpochMs)

    companion object {
        /** Attach an already-started root process. */
        fun attach(
            root: ProcessHandle,
            clock: () -> Long = System::currentTimeMillis,
        ): ProcessTreeManager = ProcessTreeManager(root = root, clock = clock, initialOutcome = null)

        /** A manager for a process that never started; its outcome is terminal. */
        fun failedToStart(clock: () -> Long = System::currentTimeMillis): ProcessTreeManager =
            ProcessTreeManager(root = null, clock = clock, initialOutcome = ProcessOutcome.FAILED_TO_START)

        /**
         * Start a process through [launcher]. A launcher failure yields a terminal
         * [ProcessOutcome.FAILED_TO_START] manager rather than an exception, so
         * callers always get a cleanup handle.
         */
        fun start(
            launcher: ProcessLauncher,
            command: List<String>,
            workingDirectory: Path? = null,
            clock: () -> Long = System::currentTimeMillis,
        ): ProcessTreeManager =
            try {
                attach(launcher.start(command, workingDirectory), clock)
            } catch (_: Exception) {
                failedToStart(clock)
            }
    }
}
