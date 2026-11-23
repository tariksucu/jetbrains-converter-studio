package studio.converter.ide.core

import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * Names of results. A result goes next to its original: `photo.heic` → `photo.jpg`; a smaller copy
 * in the same format is `report-compressed.pdf`; a name that is taken gets a number,
 * `photo (2).jpg`, so nothing is ever overwritten.
 */
object Naming {
    private val doubleExtensions = listOf(".tar.gz", ".tar.bz2", ".tar.xz")

    /** The extension of a name (`.jpg`, `.tar.gz`) or `""`. */
    fun extensionOf(name: String): String {
        val lower = name.lowercase(Locale.ROOT)
        for (double in doubleExtensions) if (lower.endsWith(double) && lower.length > double.length) return name.takeLast(double.length)
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(dot) else ""
    }

    /** A name without its extension. */
    fun stemOf(name: String): String = name.substring(0, name.length - extensionOf(name).length)

    /** A safe file name: no folders, no control or reserved characters, not `.` or `..`. */
    fun safeName(name: String): String {
        val base = name.split('/', '\\').last()
        val clean = base.replace(Regex("[\\u0000-\\u001f\\u007f<>:\"|?*]"), "_").trim().trimEnd('.')
        return if (clean.isEmpty() || clean == "." || clean == "..") "" else clean
    }

    /** The name a local result gets: the original's stem with the target's extension. */
    fun localResultName(original: String, to: String, sameFormat: Boolean): String {
        val stem = stemOf(original).ifEmpty { "converted" }
        val extension = KnownFormats.extension(to)
        return if (sameFormat) "$stem-compressed.$extension" else "$stem.$extension"
    }

    /** The name a cloud result gets: the server's name when it is a usable file name. */
    fun cloudResultName(original: String, outputName: String?, to: String): String {
        val fromServer = outputName?.let(::safeName).orEmpty()
        if (fromServer.isNotEmpty() && extensionOf(fromServer).isNotEmpty()) return fromServer
        return localResultName(original, to, sameFormat = false)
    }

    /** The n-th other name for a taken one (n ≥ 2): `photo (2).jpg`. */
    fun numbered(name: String, n: Int): String {
        val extension = extensionOf(name)
        return "${name.substring(0, name.length - extension.length)} ($n)$extension"
    }

    /** The first of `name`, `name (2)`, `name (3)`… that [taken] says is free. */
    fun freeName(name: String, taken: (String) -> Boolean): String {
        if (!taken(name)) return name
        for (n in 2..999) {
            val candidate = numbered(name, n)
            if (!taken(candidate)) return candidate
        }
        throw ConversionException("writeFailed", "No free name for $name.")
    }

    /**
     * Moves a finished result from the work folder into [folder] under [name] or the first free
     * numbered name, never replacing a file (the move fails if the name was taken meanwhile, and
     * the next number is tried). Returns the result's path.
     */
    fun moveIntoPlace(result: Path, folder: Path, name: String): Path {
        var attempt = 0
        while (true) {
            val free = freeName(name) { Files.exists(folder.resolve(it)) }
            val target = folder.resolve(free)
            try {
                return Files.move(result, target)
            } catch (e: FileAlreadyExistsException) {
                if (++attempt > 20) throw ConversionException("writeFailed", "Could not save $name in $folder.", e)
            } catch (e: IOException) {
                throw ConversionException("writeFailed", "Could not save $free in $folder: ${e.message}", e)
            }
        }
    }
}
