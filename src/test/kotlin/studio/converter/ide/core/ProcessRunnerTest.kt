package studio.converter.ide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Duration

/** Programs run with argument lists, a time limit and Cancel (on Linux and macOS). */
class ProcessRunnerTest {
    private val unix = Os.current() != Os.WINDOWS

    @Test
    fun argumentsArePassedAsTheyAre() {
        assumeTrue(unix && Files.isExecutable(Paths.get("/bin/echo")))
        val tricky = "a b; echo pwned \$(id) `id` \"q\" 'r' *"
        val result = ProcessRunner().run(listOf("/bin/echo", tricky), Duration.ofSeconds(10))
        assertEquals(0, result.exitCode)
        assertEquals(tricky, result.output.trim())
    }

    @Test
    fun cancelStopsTheProgram() {
        assumeTrue(unix && Files.isExecutable(Paths.get("/bin/sleep")))
        val started = System.nanoTime()
        val deadline = started + 300_000_000
        assertThrows(CanceledException::class.java) {
            ProcessRunner().run(listOf("/bin/sleep", "30"), Duration.ofMinutes(1), CancelCheck { System.nanoTime() > deadline })
        }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5000)
    }

    @Test
    fun aTimeOutStopsTheProgram() {
        assumeTrue(unix && Files.isExecutable(Paths.get("/bin/sleep")))
        val e = assertThrows(ConversionException::class.java) { ProcessRunner().run(listOf("/bin/sleep", "30"), Duration.ofMillis(300)) }
        assertEquals("timeout", e.code)
    }

    @Test
    fun aMissingProgramIsReported() {
        val e = assertThrows(ConversionException::class.java) {
            ProcessRunner().run(listOf("/no/such/program-${System.nanoTime()}"), Duration.ofSeconds(5))
        }
        assertEquals("toolFailed", e.code)
    }

    @Test
    fun lastLinesOfOutput() {
        assertEquals("b c", lastLines("a\n\nb\nc\n", count = 2))
    }
}
