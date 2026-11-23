package studio.converter.ide.core

import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.file.Path
import java.time.Duration
import java.util.Locale
import java.util.concurrent.TimeUnit

/** The operating system, for tool names, install folders and how to open a link. */
enum class Os {
    WINDOWS,
    MAC,
    LINUX;

    companion object {
        fun current(): Os {
            val name = System.getProperty("os.name").lowercase(Locale.ROOT)
            return when {
                name.startsWith("windows") -> WINDOWS
                name.startsWith("mac") || name.startsWith("darwin") -> MAC
                else -> LINUX
            }
        }
    }
}

/** Asked often while something long runs: true once the user pressed Cancel. */
fun interface CancelCheck {
    fun isCanceled(): Boolean

    /** Throws [CanceledException] once canceled. */
    fun check() {
        if (isCanceled()) throw CanceledException()
    }

    companion object {
        val NEVER = CancelCheck { false }
    }
}

/** Why a conversion did not happen: a fixed code for the log and a sentence for the user. */
open class ConversionException(val code: String, message: String, cause: Throwable? = null) : Exception(message, cause)

/** The user canceled. */
class CanceledException : ConversionException("canceled", "Canceled.")

/** How a program ended. [output] is the end of what it printed (stdout and stderr together). */
data class ProcessResult(val exitCode: Int, val output: String)

/**
 * Runs a program with an argument list (never through a shell), with a time limit and Cancel.
 * Its input is closed at once, so a program never waits for the keyboard; on Cancel or time-out
 * the program and every process it started are stopped.
 */
open class ProcessRunner {
    open fun run(
        command: List<String>,
        timeout: Duration,
        cancel: CancelCheck = CancelCheck.NEVER,
        workDir: Path? = null,
        environment: Map<String, String> = emptyMap(),
    ): ProcessResult {
        require(command.isNotEmpty()) { "empty command" }
        val builder = ProcessBuilder(command).redirectErrorStream(true)
        if (workDir != null) builder.directory(workDir.toFile())
        builder.environment().putAll(environment)
        val process = try {
            builder.start()
        } catch (e: java.io.IOException) {
            throw ConversionException("toolFailed", "${File(command[0]).name} could not be started: ${e.message}", e)
        }
        runCatching { process.outputStream.close() }
        val output = TailReader(process.inputStream, OUTPUT_LIMIT)
        val reader = Thread(output, "Converter Studio: ${File(command[0]).name} output").apply {
            isDaemon = true
            start()
        }
        val deadline = System.nanoTime() + timeout.toNanos()
        try {
            while (!process.waitFor(POLL_MS, TimeUnit.MILLISECONDS)) {
                if (cancel.isCanceled()) {
                    stop(process)
                    throw CanceledException()
                }
                if (System.nanoTime() > deadline) {
                    stop(process)
                    throw ConversionException(
                        "timeout",
                        "${File(command[0]).name} did not finish within ${timeout.toMinutes().coerceAtLeast(1)} minutes.",
                    )
                }
            }
        } catch (e: InterruptedException) {
            stop(process)
            Thread.currentThread().interrupt()
            throw CanceledException()
        }
        reader.join(2000)
        return ProcessResult(process.exitValue(), output.text())
    }

    private fun stop(process: Process) {
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
        runCatching { process.waitFor(5, TimeUnit.SECONDS) }
    }

    companion object {
        const val POLL_MS = 100L
        const val OUTPUT_LIMIT = 16 * 1024

        /** The charset command-line programs print in. */
        val CHARSET: Charset = System.getProperty("native.encoding")?.let { runCatching { Charset.forName(it) }.getOrNull() }
            ?: Charset.defaultCharset()
    }
}

/** Reads a stream to its end and keeps the last [limit] characters. */
private class TailReader(private val stream: InputStream, private val limit: Int) : Runnable {
    private val buffer = StringBuilder()

    override fun run() {
        try {
            stream.reader(ProcessRunner.CHARSET).use { reader ->
                val chunk = CharArray(4096)
                while (true) {
                    val n = reader.read(chunk)
                    if (n < 0) break
                    synchronized(buffer) {
                        buffer.append(chunk, 0, n)
                        if (buffer.length > limit * 2) buffer.delete(0, buffer.length - limit)
                    }
                }
            }
        } catch (_: java.io.IOException) {
            // The process was stopped.
        }
    }

    fun text(): String = synchronized(buffer) {
        if (buffer.length > limit) buffer.substring(buffer.length - limit) else buffer.toString()
    }
}

/** The last non-empty lines of a program's output, for an error message. */
fun lastLines(output: String, count: Int = 3, maxLength: Int = 400): String {
    val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val text = lines.takeLast(count).joinToString(" ")
    return if (text.length > maxLength) "…" + text.takeLast(maxLength) else text
}
