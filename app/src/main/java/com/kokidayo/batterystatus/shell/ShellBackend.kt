package com.kokidayo.batterystatus.shell

import com.kokidayo.batterystatus.IShellService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Something that can run shell commands with elevated privileges. */
interface ShellBackend {
    /** Short label for the UI, e.g. "Shizuku" or "Root". */
    val label: String

    /** uid the commands run as, when known (2000 = shell, 0 = root). */
    val uid: Int?

    /** Runs `sh -c command`; output is stdout+stderr, truncated with [ShellTimeout.MARKER] on timeout. */
    suspend fun exec(command: String, timeoutMs: Long): String
}

object ShellTimeout {
    const val MARKER = "###TIMEOUT"
}

class ShizukuBackend(private val service: IShellService) : ShellBackend {
    override val label: String = "Shizuku"
    override val uid: Int? by lazy { runCatching { service.uid }.getOrNull() }

    override suspend fun exec(command: String, timeoutMs: Long): String =
        withContext(Dispatchers.IO) { service.exec(command, timeoutMs) }
}
