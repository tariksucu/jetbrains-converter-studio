package studio.converter.ide.run

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.vfs.LocalFileSystem
import studio.converter.ide.core.CancelCheck
import studio.converter.ide.core.CanceledException
import studio.converter.ide.core.CloudClient
import studio.converter.ide.core.CloudException
import studio.converter.ide.core.ConversionException
import studio.converter.ide.core.KnownFormats
import studio.converter.ide.core.LocalConverter
import studio.converter.ide.core.LocalOptions
import studio.converter.ide.core.LocalRecipe
import studio.converter.ide.core.Naming
import studio.converter.ide.core.ToolSet
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Comparator
import java.util.UUID

/** One file's outcome. */
sealed interface FileOutcome {
    val source: Path

    data class Converted(override val source: Path, val result: Path) : FileOutcome

    data class Failed(override val source: Path, val code: String, val message: String) : FileOutcome
}

/** Asks the user whether to go ahead with a cloud conversion that costs more than the limit. */
fun interface CostQuestion {
    fun goAhead(fileName: String, credits: Long?, maxCredits: Long): Boolean
}

/**
 * The conversions themselves, without any UI: one file at a time, each in a work folder of its
 * own (deleted afterwards), the result moved next to the original and shown to the IDE's file
 * system. Used by [ConversionTask] and by the tests.
 */
class Conversions(private val cancel: CancelCheck, private val progress: (text: String, fraction: Double?) -> Unit = { _, _ -> }) {
    /** Converts [source] on this computer with [recipe]. */
    fun local(source: Path, recipe: LocalRecipe, tools: ToolSet, options: LocalOptions, libreOfficeProfile: Path?): FileOutcome =
        attempt(source) { work ->
            val result = LocalConverter(tools, options, libreOfficeProfile = libreOfficeProfile)
                .convert(source, recipe, work, cancel) { step -> progress(step, null) }
            Naming.moveIntoPlace(result, source.parent, Naming.localResultName(source.fileName.toString(), recipe.to, recipe.compress))
        }

    /**
     * Converts [source] in the cloud: upload, start (with an Idempotency-Key), wait, ask when it
     * costs more than [maxCredits], download, then save next to the original.
     */
    fun cloud(source: Path, to: String, client: CloudClient, maxCredits: Long, question: CostQuestion): FileOutcome =
        attempt(source) { work ->
            val name = source.fileName.toString()
            val size = Files.size(source)
            val uploadId = client.upload(source, name, cancel) { sent, total ->
                progress("Uploading $name to Converter Studio", if (total > 0) sent.toDouble() / total else null)
            }
            progress("Converting $name at Converter Studio", null)
            var view = client.start(uploadId, to, emptyMap(), maxCredits, UUID.randomUUID().toString(), cancel)
            view = client.settle(view, CLOUD_TIMEOUT, cancel) { v -> progress("Converting $name at Converter Studio", v.progress?.let { it / 100.0 }) }
            if (view.status == "quoted") {
                if (!question.goAhead(name, view.credits, maxCredits)) {
                    throw ConversionException("declined", "Not converted: it costs ${view.credits ?: "more"} credits, more than your limit of $maxCredits. Nothing was charged.")
                }
                view = client.settle(client.confirm(view.id, cancel), CLOUD_TIMEOUT, cancel)
            }
            when (view.status) {
                "done" -> {}
                "failed" -> throw CloudException(view.errorCode ?: "failed", view.errorMessage ?: "Converter Studio could not convert this file.")
                "expired" -> throw CloudException("fileExpired", "The result expired before it was downloaded.")
                "canceled" -> throw CloudException("canceled", "The conversion was canceled at Converter Studio.")
                else -> throw CloudException("badResponse", "Converter Studio left the conversion at \"${view.status}\".")
            }
            val url = view.downloadUrl ?: throw CloudException("badResponse", "Converter Studio sent no download link.")
            val download = work.resolve("download")
            progress("Downloading the result of $name", null)
            client.download(url, download, cancel) { got ->
                view.outputSize?.takeIf { it > 0 }?.let { progress("Downloading the result of $name", got.toDouble() / it) }
            }
            LOG.debug("cloud conversion ${view.id}: $size bytes in, ${Files.size(download)} bytes out")
            Naming.moveIntoPlace(download, source.parent, Naming.cloudResultName(name, view.outputName, to))
        }

    /** Runs one conversion in a fresh work folder; a failure becomes a [FileOutcome.Failed] (Cancel stops the batch). */
    private fun attempt(source: Path, body: (work: Path) -> Path): FileOutcome {
        cancel.check()
        val work = Files.createTempDirectory("converter-studio-")
        try {
            val result = body(work)
            // Show the new file in the Project view at once.
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(result)
            return FileOutcome.Converted(source, result)
        } catch (e: CanceledException) {
            throw e
        } catch (e: ConversionException) {
            LOG.info("conversion of a ${KnownFormats.name(Naming.extensionOf(source.fileName.toString()).removePrefix("."))} file failed: ${e.code}")
            return FileOutcome.Failed(source, e.code, e.message ?: e.code)
        } catch (e: java.io.IOException) {
            LOG.info("conversion failed: ${e.javaClass.simpleName}")
            return FileOutcome.Failed(source, "io", e.message ?: "The file could not be read or written.")
        } finally {
            deleteTree(work)
        }
    }

    companion object {
        private val LOG = logger<Conversions>()
        val CLOUD_TIMEOUT: Duration = Duration.ofMinutes(60)

        /** The cancel check of a progress indicator. */
        fun cancelOf(indicator: ProgressIndicator): CancelCheck = CancelCheck { indicator.isCanceled }

        fun deleteTree(path: Path) {
            if (!Files.exists(path)) return
            runCatching {
                Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
            }
        }
    }
}
