package studio.converter.ide.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util.Locale

/** The conversion programs the plugin looks for on this computer. */
enum class ToolId(val displayName: String, val purpose: String) {
    FFMPEG("FFmpeg", "video and audio"),
    LIBREOFFICE("LibreOffice", "Office documents, spreadsheets and slides, to and from PDF"),
    PANDOC("Pandoc", "Markdown, HTML, DOCX, ODT, EPUB and RTF"),
    IMAGEMAGICK("ImageMagick", "images (WebP, AVIF, HEIC when built with it, ICO, PDF)"),
    GHOSTSCRIPT("Ghostscript", "smaller PDFs, EPS to PDF, PDF pages for text recognition"),
    TESSERACT("Tesseract", "text recognition (OCR) of images and scanned PDFs"),
}

/** A program found on this computer and what it can do. */
data class ToolInfo(
    val id: ToolId,
    val path: Path,
    val version: String? = null,
    /** ImageMagick 6's `convert` instead of ImageMagick 7's `magick`. */
    val magickLegacy: Boolean = false,
    /** ImageMagick: the formats it reads and writes, in its own upper-case names (`HEIC`, `WEBP`). */
    val imageRead: Set<String> = emptySet(),
    val imageWrite: Set<String> = emptySet(),
    /** FFmpeg: its encoders (`libx264`, `aac`). */
    val encoders: Set<String> = emptySet(),
    /** Pandoc: its input and output formats. */
    val pandocIn: Set<String> = emptySet(),
    val pandocOut: Set<String> = emptySet(),
    /** Tesseract: its installed languages (`eng`, `deu`). */
    val languages: Set<String> = emptySet(),
) {
    /** "FFmpeg 6.1.1". */
    val label: String get() = if (version.isNullOrBlank()) id.displayName else "${id.displayName} $version"
}

/** The programs found, and why the others are missing when there is more to say than "not found". */
class ToolSet(val tools: Map<ToolId, ToolInfo>, val problems: Map<ToolId, String> = emptyMap()) {
    operator fun get(id: ToolId): ToolInfo? = tools[id]

    fun has(id: ToolId): Boolean = tools.containsKey(id)

    companion object {
        val NONE = ToolSet(emptyMap())
    }
}

/** What the locator may ask the file system (replaced in tests). */
interface FileProbe {
    fun isFile(path: Path): Boolean

    fun list(dir: Path): List<Path>

    companion object {
        val DEFAULT = object : FileProbe {
            override fun isFile(path: Path): Boolean = Files.isRegularFile(path) && Files.isExecutable(path)

            override fun list(dir: Path): List<Path> =
                if (!Files.isDirectory(dir)) emptyList() else runCatching { Files.list(dir).use { it.toList() } }.getOrDefault(emptyList())
        }
    }
}

/**
 * Where each program may be: the folders of PATH first, then the folders its installers and the
 * usual package managers use (an IDE started from the Dock or the Start menu often has a shorter
 * PATH than a terminal).
 */
class ToolLocator(
    private val os: Os,
    private val pathVariable: String?,
    private val environment: (String) -> String?,
    private val home: String,
    private val files: FileProbe = FileProbe.DEFAULT,
) {
    /** The program's file names, in order of preference. */
    fun names(tool: ToolId): List<String> = when (os) {
        Os.WINDOWS -> when (tool) {
            ToolId.FFMPEG -> listOf("ffmpeg.exe")
            ToolId.LIBREOFFICE -> listOf("soffice.exe")
            ToolId.PANDOC -> listOf("pandoc.exe")
            // Never "convert.exe": on Windows that name is the system's disk tool.
            ToolId.IMAGEMAGICK -> listOf("magick.exe")
            ToolId.GHOSTSCRIPT -> listOf("gswin64c.exe", "gswin32c.exe")
            ToolId.TESSERACT -> listOf("tesseract.exe")
        }
        else -> when (tool) {
            ToolId.FFMPEG -> listOf("ffmpeg")
            ToolId.LIBREOFFICE -> listOf("soffice", "libreoffice")
            ToolId.PANDOC -> listOf("pandoc")
            ToolId.IMAGEMAGICK -> listOf("magick", "convert")
            ToolId.GHOSTSCRIPT -> listOf("gs")
            ToolId.TESSERACT -> listOf("tesseract")
        }
    }

    /** Folders to look in after PATH. */
    fun usualFolders(tool: ToolId): List<Path> {
        val out = ArrayList<Path>()
        fun add(vararg parts: String?) {
            if (parts.any { it.isNullOrBlank() }) return
            runCatching { Paths.get(parts[0]!!, *parts.drop(1).map { it!! }.toTypedArray()) }.getOrNull()?.let(out::add)
        }
        fun addMatching(parent: String?, prefix: String, vararg rest: String) {
            if (parent.isNullOrBlank()) return
            val dir = runCatching { Paths.get(parent) }.getOrNull() ?: return
            files.list(dir)
                .filter { it.fileName.toString().lowercase(Locale.ROOT).startsWith(prefix.lowercase(Locale.ROOT)) }
                .sortedWith(compareByDescending<Path, String>(NaturalOrder) { it.fileName.toString() })
                .forEach { found -> out.add(rest.fold(found) { p, part -> p.resolve(part) }) }
        }
        when (os) {
            Os.WINDOWS -> {
                val programFiles = environment("ProgramFiles") ?: "C:\\Program Files"
                val programFilesX86 = environment("ProgramFiles(x86)")
                val localAppData = environment("LOCALAPPDATA")
                val programData = environment("ProgramData") ?: "C:\\ProgramData"
                when (tool) {
                    ToolId.FFMPEG -> {
                        add(programFiles, "ffmpeg", "bin")
                        add("C:\\ffmpeg", "bin")
                    }
                    ToolId.LIBREOFFICE -> {
                        add(programFiles, "LibreOffice", "program")
                        add(programFilesX86, "LibreOffice", "program")
                    }
                    ToolId.PANDOC -> {
                        add(localAppData, "Pandoc")
                        add(programFiles, "Pandoc")
                    }
                    ToolId.IMAGEMAGICK -> addMatching(programFiles, "ImageMagick-")
                    ToolId.GHOSTSCRIPT -> {
                        addMatching(Paths.get(programFiles, "gs").toString(), "gs", "bin")
                        if (programFilesX86 != null) addMatching(Paths.get(programFilesX86, "gs").toString(), "gs", "bin")
                    }
                    ToolId.TESSERACT -> {
                        add(programFiles, "Tesseract-OCR")
                        add(localAppData, "Programs", "Tesseract-OCR")
                    }
                }
                // winget, Chocolatey and Scoop put links to the programs they install here.
                add(localAppData, "Microsoft", "WinGet", "Links")
                add(environment("ChocolateyInstall") ?: Paths.get(programData, "chocolatey").toString(), "bin")
                add(home, "scoop", "shims")
            }
            Os.MAC -> {
                if (tool == ToolId.LIBREOFFICE) {
                    add("/Applications/LibreOffice.app/Contents/MacOS")
                    add(home, "Applications", "LibreOffice.app", "Contents", "MacOS")
                }
                add("/opt/homebrew/bin")
                add("/usr/local/bin")
                add("/opt/local/bin")
                add(home, ".local", "bin")
            }
            Os.LINUX -> {
                if (tool == ToolId.LIBREOFFICE) {
                    addMatching("/opt", "libreoffice", "program")
                    add("/usr/lib/libreoffice/program")
                }
                add("/usr/bin")
                add("/usr/local/bin")
                add("/snap/bin")
                add(home, ".local", "bin")
                add(home, "bin")
            }
        }
        return out
    }

    /** Every place the program may be, PATH first, without repeats. */
    fun candidates(tool: ToolId): List<Path> {
        val separator = if (os == Os.WINDOWS) ';' else ':'
        val folders = pathVariable.orEmpty().split(separator)
            .map { it.trim().removeSurrounding("\"") }
            .filter { it.isNotEmpty() }
            .mapNotNull { runCatching { Paths.get(it) }.getOrNull() } + usualFolders(tool)
        val out = LinkedHashSet<Path>()
        for (name in names(tool)) for (folder in folders) out.add(folder.resolve(name))
        return out.toList()
    }

    /** The first candidate that is an executable file. */
    fun find(tool: ToolId): Path? = candidates(tool).firstOrNull { files.isFile(it) }
}

/** Compares names with numbers in them as people do: `gs9.56` before `gs10.02`. */
internal object NaturalOrder : Comparator<String> {
    private val part = Regex("\\d+|\\D+")

    override fun compare(a: String, b: String): Int {
        val x = part.findAll(a).map { it.value }.toList()
        val y = part.findAll(b).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]
            val q = y[i]
            val c = if (p[0].isDigit() && q[0].isDigit()) {
                p.trimStart('0').length.compareTo(q.trimStart('0').length).takeIf { it != 0 } ?: p.trimStart('0').compareTo(q.trimStart('0'))
            } else {
                p.compareTo(q, ignoreCase = true)
            }
            if (c != 0) return c
        }
        return x.size.compareTo(y.size)
    }
}

/**
 * Finds the programs and asks each one what it can do (its version, ImageMagick's formats,
 * FFmpeg's encoders, Pandoc's formats, Tesseract's languages). Each question has a time limit.
 */
class ToolDetector(
    private val locator: ToolLocator,
    private val runner: ProcessRunner = ProcessRunner(),
    private val timeout: Duration = Duration.ofSeconds(20),
) {
    /** Detects every tool; [overrides] are paths the user typed, which replace the search. */
    fun detect(overrides: Map<ToolId, String> = emptyMap(), cancel: CancelCheck = CancelCheck.NEVER): ToolSet {
        val found = LinkedHashMap<ToolId, ToolInfo>()
        val problems = LinkedHashMap<ToolId, String>()
        for (tool in ToolId.values()) {
            cancel.check()
            val override = overrides[tool]?.trim().orEmpty()
            val path = if (override.isNotEmpty()) {
                val p = runCatching { Paths.get(override) }.getOrNull()
                if (p == null || !Files.isRegularFile(p)) {
                    problems[tool] = "Not found at $override."
                    continue
                }
                p
            } else {
                locator.find(tool) ?: continue
            }
            try {
                val info = probe(tool, path, cancel)
                if (info != null) found[tool] = info else problems[tool] = "${path.fileName} at ${path.parent} is not ${tool.displayName}."
            } catch (e: CanceledException) {
                throw e
            } catch (e: ConversionException) {
                problems[tool] = "${tool.displayName} at $path did not answer: ${e.message}"
            }
        }
        return ToolSet(found, problems)
    }

    /** Asks one program what it is; null if it is not the expected program. */
    fun probe(tool: ToolId, path: Path, cancel: CancelCheck = CancelCheck.NEVER): ToolInfo? {
        fun ask(vararg args: String): String = runner.run(listOf(path.toString()) + args, timeout, cancel).output
        return when (tool) {
            ToolId.FFMPEG -> {
                val version = ask("-hide_banner", "-version")
                if (!version.contains("ffmpeg", ignoreCase = true)) return null
                ToolInfo(
                    tool,
                    path,
                    Regex("ffmpeg version (\\S+)").find(version)?.groupValues?.get(1),
                    encoders = ToolOutput.ffmpegEncoders(ask("-hide_banner", "-encoders")),
                )
            }
            ToolId.LIBREOFFICE -> {
                // soffice.exe prints nothing on Windows: the file is enough there.
                val version = runCatching { ask("--version") }.getOrDefault("")
                ToolInfo(tool, path, Regex("LibreOffice (\\d[\\w.]*)").find(version)?.groupValues?.get(1))
            }
            ToolId.PANDOC -> {
                val version = ask("--version")
                if (!version.contains("pandoc", ignoreCase = true)) return null
                ToolInfo(
                    tool,
                    path,
                    Regex("pandoc(?:\\.exe)? (\\d[\\w.]*)").find(version)?.groupValues?.get(1),
                    pandocIn = ToolOutput.words(ask("--list-input-formats")),
                    pandocOut = ToolOutput.words(ask("--list-output-formats")),
                )
            }
            ToolId.IMAGEMAGICK -> {
                val version = ask("-version")
                if (!version.contains("ImageMagick")) return null
                val formats = ToolOutput.magickFormats(ask("-list", "format"))
                ToolInfo(
                    tool,
                    path,
                    Regex("ImageMagick (\\S+)").find(version)?.groupValues?.get(1),
                    magickLegacy = path.fileName.toString().lowercase(Locale.ROOT).startsWith("convert"),
                    imageRead = formats.first,
                    imageWrite = formats.second,
                )
            }
            ToolId.GHOSTSCRIPT -> {
                val version = ask("--version").trim()
                if (!Regex("^\\d+\\.\\d+").containsMatchIn(version)) return null
                ToolInfo(tool, path, version.lines().first().trim())
            }
            ToolId.TESSERACT -> {
                val version = ask("--version")
                if (!version.contains("tesseract", ignoreCase = true)) return null
                ToolInfo(
                    tool,
                    path,
                    Regex("tesseract v?(\\d[\\w.]*)").find(version)?.groupValues?.get(1),
                    languages = ToolOutput.tesseractLanguages(ask("--list-langs")),
                )
            }
        }
    }
}

/** Readers of the programs' own listings. */
object ToolOutput {
    private val encoderLine = Regex("^\\s*[VAS][.A-Z]{5}\\s+(\\S+)\\s")
    private val magickLine = Regex("^\\s*([A-Z0-9][A-Z0-9-]*)\\*?\\s+\\S+\\s+([r-])([w-])([+-])")
    private val word = Regex("^[A-Za-z0-9_+-]+$")

    /** FFmpeg's `-encoders`: the names in the second column. */
    fun ffmpegEncoders(output: String): Set<String> =
        output.lines().mapNotNull { encoderLine.find(it)?.groupValues?.get(1) }.filter { it != "=" }.toSet()

    /** ImageMagick's `-list format`: the formats it reads and the ones it writes. */
    fun magickFormats(output: String): Pair<Set<String>, Set<String>> {
        val read = HashSet<String>()
        val write = HashSet<String>()
        for (line in output.lines()) {
            val m = magickLine.find(line) ?: continue
            val name = m.groupValues[1]
            if (m.groupValues[2] == "r") read.add(name)
            if (m.groupValues[3] == "w") write.add(name)
        }
        return read to write
    }

    /** One word per line (Pandoc's format lists). */
    fun words(output: String): Set<String> = output.lines().map { it.trim() }.filter { word.matches(it) }.toSet()

    /** Tesseract's `--list-langs`: the lines after the heading. */
    fun tesseractLanguages(output: String): Set<String> =
        output.lines().drop(1).map { it.trim() }.filter { word.matches(it) }.toSet()
}
