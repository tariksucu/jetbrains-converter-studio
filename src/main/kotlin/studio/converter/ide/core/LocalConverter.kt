package studio.converter.ide.core

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Runs a [LocalRecipe] on one file. Everything happens in a work folder of its own; the caller
 * moves the result next to the original ([Naming.moveIntoPlace]) and deletes the folder. The
 * original is only read.
 */
class LocalConverter(
    private val tools: ToolSet,
    private val options: LocalOptions = LocalOptions(),
    private val runner: ProcessRunner = ProcessRunner(),
    /** LibreOffice's profile folder for the plugin; a fresh one in the work folder when null. */
    private val libreOfficeProfile: Path? = null,
) {
    /** Converts [source]; returns the result, a file inside [workDir]. */
    fun convert(source: Path, recipe: LocalRecipe, workDir: Path, cancel: CancelCheck, progress: (String) -> Unit = {}): Path {
        cancel.check()
        val output = workDir.resolve("output.${KnownFormats.extension(recipe.to)}")
        when (recipe.engine) {
            Engine.BUILT_IN_IMAGE -> ImageConversions.convert(source, output, recipe.to)
            Engine.BUILT_IN_DATA -> DataConversions.convert(source, output, recipe.from, recipe.to)
            Engine.BUILT_IN_SUBTITLE -> SubtitleConversions.convert(source, output, recipe.from, recipe.to)
            Engine.FFMPEG -> run(Commands.ffmpeg(tool(ToolId.FFMPEG), source, output, recipe.to), ToolId.FFMPEG, FFMPEG_TIMEOUT, cancel)
            Engine.LIBREOFFICE -> return libreOffice(source, recipe.to, workDir, cancel)
            Engine.PANDOC -> run(Commands.pandoc(tool(ToolId.PANDOC), source, output, recipe.from, recipe.to), ToolId.PANDOC, TIMEOUT, cancel)
            Engine.PANDOC_LIBREOFFICE -> {
                val docx = workDir.resolve("intermediate.docx")
                progress("Pandoc: ${KnownFormats.name(recipe.from)} to DOCX")
                run(Commands.pandoc(tool(ToolId.PANDOC), source, docx, recipe.from, "docx"), ToolId.PANDOC, TIMEOUT, cancel)
                needFile(docx, ToolId.PANDOC, "")
                progress("LibreOffice: DOCX to ${KnownFormats.name(recipe.to)}")
                return libreOffice(docx, recipe.to, workDir, cancel)
            }
            Engine.IMAGEMAGICK -> {
                // A copy under a plain name: ImageMagick reads "[…]" and some characters in names.
                val input = workDir.resolve("input${Naming.extensionOf(source.fileName.toString()).lowercase()}")
                Files.copy(source, input)
                run(Commands.imageMagick(tool(ToolId.IMAGEMAGICK), input, output, recipe.to), ToolId.IMAGEMAGICK, TIMEOUT, cancel)
            }
            Engine.GHOSTSCRIPT -> {
                val gs = tool(ToolId.GHOSTSCRIPT)
                val command = if (recipe.from == "eps") Commands.ghostscriptEps(gs, source, output) else Commands.ghostscriptCompress(gs, source, output)
                run(command, ToolId.GHOSTSCRIPT, LONG_TIMEOUT, cancel)
                if (recipe.compress) {
                    needFile(output, ToolId.GHOSTSCRIPT, "")
                    if (Files.size(output) >= Files.size(source)) {
                        throw ConversionException("notSmaller", "Ghostscript could not make this PDF smaller, so nothing was saved.")
                    }
                }
            }
            Engine.TESSERACT -> ocrImage(source, output, cancel)
            Engine.GHOSTSCRIPT_TESSERACT -> ocrPdf(source, output, workDir, cancel, progress)
        }
        needFile(output, recipe.engine.tools.lastOrNull(), "")
        return output
    }

    private fun libreOffice(source: Path, to: String, workDir: Path, cancel: CancelCheck): Path {
        val outDir = Files.createDirectories(workDir.resolve("libreoffice-out"))
        val profile = libreOfficeProfile ?: workDir.resolve("libreoffice-profile")
        // One LibreOffice at a time per profile: a second one would hand its work to the first and
        // end at once, before the file exists.
        while (!OFFICE_LOCK.tryLock(200, TimeUnit.MILLISECONDS)) cancel.check()
        val result = try {
            run(Commands.libreOffice(tool(ToolId.LIBREOFFICE), source, outDir, to, profile), ToolId.LIBREOFFICE, LONG_TIMEOUT, cancel)
        } finally {
            OFFICE_LOCK.unlock()
        }
        // LibreOffice ends with 0 even when it could not open the file: the file it made says it.
        val made = Files.list(outDir).use { files -> files.filter { Files.isRegularFile(it) }.toList() }
        return made.singleOrNull() ?: throw ConversionException(
            "toolFailed",
            "LibreOffice made no ${KnownFormats.name(to)} file. ${lastLines(result.output)}".trim(),
        )
    }

    private fun ocrImage(source: Path, output: Path, cancel: CancelCheck) {
        val base = output.resolveSibling(Naming.stemOf(output.fileName.toString()))
        run(Commands.tesseract(tool(ToolId.TESSERACT), source, base, languages()), ToolId.TESSERACT, TIMEOUT, cancel)
    }

    private fun ocrPdf(source: Path, output: Path, workDir: Path, cancel: CancelCheck, progress: (String) -> Unit) {
        val pages = Files.createDirectories(workDir.resolve("pages"))
        progress("Ghostscript: rendering the pages")
        run(Commands.ghostscriptPages(tool(ToolId.GHOSTSCRIPT), source, pages), ToolId.GHOSTSCRIPT, LONG_TIMEOUT, cancel)
        val images = Files.list(pages).use { files -> files.filter { it.fileName.toString().endsWith(".png") }.sorted().toList() }
        if (images.isEmpty()) throw ConversionException("toolFailed", "Ghostscript found no pages in this PDF.")
        val text = StringBuilder()
        images.forEachIndexed { index, image ->
            cancel.check()
            progress("Tesseract: page ${index + 1} of ${images.size}")
            val base = pages.resolve("text-${index + 1}")
            run(Commands.tesseract(tool(ToolId.TESSERACT), image, base, languages()), ToolId.TESSERACT, TIMEOUT, cancel)
            val page = pages.resolve("text-${index + 1}.txt")
            if (Files.exists(page)) {
                if (text.isNotEmpty()) text.append("\n\n")
                text.append(Files.readString(page, StandardCharsets.UTF_8).trimEnd())
            }
            Files.deleteIfExists(image)
        }
        Files.writeString(output, text.append('\n').toString(), StandardCharsets.UTF_8)
    }

    private fun languages(): String = OcrLanguages.parse(options.ocrLanguages)?.joinToString("+")
        ?: throw ConversionException("badSetting", "The OCR language setting \"${options.ocrLanguages}\" is not valid (example: eng+deu).")

    private fun tool(id: ToolId): ToolInfo = tools[id] ?: throw ConversionException("toolMissing", "${id.displayName} was not found on this computer.")

    private fun run(command: List<String>, tool: ToolId, timeout: Duration, cancel: CancelCheck): ProcessResult {
        val result = runner.run(command, timeout, cancel)
        if (result.exitCode != 0) {
            throw ConversionException("toolFailed", "${tool.displayName} stopped with an error (exit code ${result.exitCode}). ${lastLines(result.output)}".trim())
        }
        return result
    }

    private fun needFile(path: Path, tool: ToolId?, output: String) {
        if (!Files.isRegularFile(path) || Files.size(path) == 0L) {
            val who = tool?.displayName ?: "The converter"
            throw ConversionException("toolFailed", "$who made no file. ${lastLines(output)}".trim())
        }
    }

    companion object {
        private val OFFICE_LOCK = ReentrantLock()
        val TIMEOUT: Duration = Duration.ofMinutes(10)
        val LONG_TIMEOUT: Duration = Duration.ofMinutes(30)
        val FFMPEG_TIMEOUT: Duration = Duration.ofHours(3)
    }
}
