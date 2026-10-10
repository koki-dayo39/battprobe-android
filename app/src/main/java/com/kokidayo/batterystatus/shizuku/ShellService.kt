package com.kokidayo.batterystatus.shizuku

import android.content.Context
import android.os.Process
import androidx.annotation.Keep
import com.kokidayo.batterystatus.IShellService
import java.io.ByteArrayOutputStream
import kotlin.system.exitProcess

/**
 * Runs inside a separate process spawned by the Shizuku server with the
 * Shizuku user's privileges (shell uid 2000, or root when Shizuku runs as root).
 *
 * Instantiated reflectively by Shizuku, so it must stay un-obfuscated and keep
 * these constructors.
 */
@Keep
class ShellService : IShellService.Stub {

    constructor() : super()

    /** Shizuku 13+ passes a Context when this constructor exists. */
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context) : super()

    override fun destroy() {
        exitProcess(0)
    }

    override fun exit() {
        destroy()
    }

    override fun exec(command: String): String {
        val process = ProcessBuilder("sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        val buffer = ByteArrayOutputStream()
        process.inputStream.use { input ->
            val chunk = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                if (buffer.size() < MAX_OUTPUT_BYTES) buffer.write(chunk, 0, read)
            }
        }
        process.waitFor()
        return buffer.toString(Charsets.UTF_8.name())
    }

    override fun getUid(): Int = Process.myUid()

    private companion object {
        const val MAX_OUTPUT_BYTES = 2 * 1024 * 1024
    }
}
