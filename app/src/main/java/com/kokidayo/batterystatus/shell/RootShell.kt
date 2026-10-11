package com.kokidayo.batterystatus.shell

import com.kokidayo.batterystatus.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

enum class RootState { UNKNOWN, UNAVAILABLE, DENIED, GRANTED }

/**
 * Root backend: every call is `su -c <command>`. Magisk/KernelSU cache the grant,
 * so only the first call shows a prompt.
 */
object RootShell : ShellBackend {
    private const val TAG = "RootShell"

    override val label: String = "Root"
    override val uid: Int? get() = if (_state.value == RootState.GRANTED) 0 else null

    private val _state = MutableStateFlow(RootState.UNKNOWN)
    val state: StateFlow<RootState> = _state.asStateFlow()

    /** Asks for root (may show the su manager's prompt) and records the result. */
    suspend fun probe(): RootState = withContext(Dispatchers.IO) {
        val result = runCatching { runSu("id", 20_000) }
        val output = result.getOrNull().orEmpty()
        val newState = when {
            result.isFailure -> RootState.UNAVAILABLE // no `su` binary at all
            output.contains("uid=0") -> RootState.GRANTED
            else -> RootState.DENIED
        }
        if (newState != _state.value) AppLog.i(TAG, "root probe: $newState (${output.trim().take(80)})")
        _state.value = newState
        newState
    }

    fun reset() {
        _state.value = RootState.UNKNOWN
    }

    override suspend fun exec(command: String, timeoutMs: Long): String = withContext(Dispatchers.IO) {
        runSu(command, timeoutMs)
    }

    private fun runSu(command: String, timeoutMs: Long): String {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        val buffer = ByteArrayOutputStream()
        val reader = thread(name = "su-reader", isDaemon = true) {
            try {
                process.inputStream.use { input ->
                    val chunk = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(chunk)
                        if (read < 0) break
                        synchronized(buffer) { if (buffer.size() < MAX_OUTPUT) buffer.write(chunk, 0, read) }
                    }
                }
            } catch (_: Exception) {
            }
        }
        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) process.destroyForcibly()
        reader.join(2_000)
        val output = synchronized(buffer) { buffer.toString(Charsets.UTF_8.name()) }
        return if (finished) output else output + "\n" + ShellTimeout.MARKER + "\n"
    }

    private const val MAX_OUTPUT = 2 * 1024 * 1024
}
