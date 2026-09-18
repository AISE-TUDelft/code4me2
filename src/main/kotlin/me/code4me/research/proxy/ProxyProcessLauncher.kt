package me.code4me.research.proxy

import me.code4me.research.runtime.ProcessTreeManager
import me.code4me.research.session.ProxyHandle
import me.code4me.research.session.ProxyLauncher
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Typed outcome of starting the proxy process; never thrown.
 *
 * [Running] exposes the owned [ProcessTreeManager] so the caller can tear the
 * whole tree down (graceful, then forced) without depending on process internals.
 */
sealed interface ProxyProcessStart {
    data class Running(val process: Process, val tree: ProcessTreeManager) : ProxyProcessStart

    data class Failed(val detail: String) : ProxyProcessStart
}

/**
 * A real [ProxyLauncher] (Issue 08/11).
 *
 * The argv is always passed as an argument array — never a shell string — and
 * the child environment is an explicit allowlist (the ambient environment,
 * minus secrets, plus the spec's non-secret markers). Startup and teardown
 * exceptions never escape: a failed start yields [ProxyProcessStart.Failed] and
 * a no-op [ProxyHandle], so research can never break ordinary plugin behaviour.
 */
class ProxyProcessLauncher(
    private val graceMillis: Long = DEFAULT_GRACE_MILLIS,
    private val processStarter: (List<String>, Path?, Map<String, String>) -> Process = ::startProcess,
) : ProxyLauncher {
    /** Start the proxy and own its process tree; typed failure instead of throwing. */
    fun start(spec: ProxyLaunchSpec): ProxyProcessStart =
        try {
            val process = processStarter(spec.toCommandList(), spec.workingDirectory, spec.environment)
            ProxyProcessStart.Running(
                process = process,
                tree = ProcessTreeManager.attach(JavaProcessTreeHandle(process.toHandle())),
            )
        } catch (exception: Exception) {
            ProxyProcessStart.Failed(
                exception.message ?: exception::class.simpleName ?: "the proxy process could not start",
            )
        }

    override fun launch(spec: ProxyLaunchSpec): ProxyHandle =
        when (val started = start(spec)) {
            is ProxyProcessStart.Running -> ProxyHandle { started.tree.closeGracefully(graceMillis) }
            is ProxyProcessStart.Failed -> ProxyHandle { }
        }

    companion object {
        const val DEFAULT_GRACE_MILLIS: Long = 5_000L

        /**
         * Environment variables the child may inherit. Everything else is
         * dropped, so a host secret can never leak into the proxy by accident.
         */
        val ALLOWED_ENVIRONMENT_KEYS: Set<String> =
            setOf(
                "PATH",
                "HOME",
                "USERPROFILE",
                "LANG",
                "LC_ALL",
                "LC_CTYPE",
                "TMPDIR",
                "TMP",
                "TEMP",
                "SystemRoot",
                "SystemDrive",
                "COMSPEC",
                "PATHEXT",
            )

        private fun startProcess(
            argv: List<String>,
            workingDirectory: Path?,
            environment: Map<String, String>,
        ): Process {
            val builder = ProcessBuilder(argv)
            workingDirectory?.let { builder.directory(it.toFile()) }
            val childEnvironment = builder.environment()
            childEnvironment.clear()
            childEnvironment.putAll(allowedEnvironment(environment))
            return builder.start()
        }

        /** The explicit child environment: allowlisted ambient keys plus [overrides]. */
        fun allowedEnvironment(overrides: Map<String, String>): Map<String, String> {
            val child = linkedMapOf<String, String>()
            for (key in ALLOWED_ENVIRONMENT_KEYS) {
                System.getenv(key)?.let { child[key] = it }
            }
            child.putAll(overrides)
            return child
        }
    }
}

/**
 * Adapts a `java.lang.ProcessHandle` tree to the platform-independent
 * [me.code4me.research.runtime.ProcessHandle] the [ProcessTreeManager] owns.
 */
private class JavaProcessTreeHandle(
    private val handle: java.lang.ProcessHandle,
) : me.code4me.research.runtime.ProcessHandle {
    override val pid: Long
        get() = handle.pid()

    override val parentPid: Long?
        get() = handle.parent().orElse(null)?.pid()

    override val startedAtEpochMs: Long
        get() = handle.info().startInstant().orElse(null)?.toEpochMilli() ?: 0L

    override val isAlive: Boolean
        get() = handle.isAlive

    override fun descendants(): List<me.code4me.research.runtime.ProcessHandle> =
        handle.descendants().toList().map { JavaProcessTreeHandle(it) }

    override fun gracefulTerminate(): Boolean = handle.destroy()

    override fun forceTerminate(): Boolean = handle.destroyForcibly()

    override fun awaitExit(timeoutMs: Long): Boolean {
        if (!handle.isAlive) return true
        return try {
            handle.onExit().get(timeoutMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            true
        } catch (_: Exception) {
            !handle.isAlive
        }
    }
}
