package studio.converter.ide.core

import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/** Images with Java's own ImageIO (PNG, JPG, BMP, GIF, TIFF), inside the IDE. */
object ImageConversions {
    /** ImageIO's writer names. */
    private val WRITERS = mapOf("png" to "png", "jpg" to "jpeg", "bmp" to "bmp", "gif" to "gif", "tiff" to "tiff")

    fun convert(input: Path, output: Path, to: String) {
        val writer = WRITERS[to] ?: throw ConversionException("unsupported", "The built-in converter does not write ${KnownFormats.name(to)}.")
        val image = try {
            ImageIO.read(input.toFile())
        } catch (e: java.io.IOException) {
            throw ConversionException("sourceUnreadable", "The image could not be read: ${e.message}", e)
        } ?: throw ConversionException("sourceUnreadable", "Java cannot read this image (an unusual kind of ${KnownFormats.name(Naming.extensionOf(input.fileName.toString()).removePrefix("."))}).")
        val out = if (to == "jpg" || to == "bmp") flatten(image) else image
        if (!ImageIO.write(out, writer, output.toFile())) {
            throw ConversionException("toolFailed", "Java has no ${KnownFormats.name(to)} writer for this image.")
        }
    }

    /** The image on a white background, without transparency (JPG and BMP have none). */
    private fun flatten(image: BufferedImage): BufferedImage {
        if (image.type == BufferedImage.TYPE_INT_RGB || image.type == BufferedImage.TYPE_3BYTE_BGR) return image
        val rgb = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
        val g = rgb.createGraphics()
        try {
            g.color = Color.WHITE
            g.fillRect(0, 0, image.width, image.height)
            g.drawImage(image, 0, 0, null)
        } finally {
            g.dispose()
        }
        return rgb
    }
}

/**
 * Data files with the plugin's own code: JSON ↔ CSV, and JSON or CSV → YAML. A JSON file becomes a
 * table when it is a list of objects (or one object); nested objects become `parent.child`
 * columns, lists are kept as JSON text in their cell.
 *
 * Nothing is dropped or changed on the way: every column gets a name of its own, numbers keep the
 * digits they were written with (9007199254740993 stays exact). The Converter Studio extensions
 * for VS Code, Sublime Text and Neovim follow the same rules and run the same cases
 * (`src/test/resources/data-cases.json`), so a file gives the same result in each.
 */
object DataConversions {
    private val pairs = setOf("json" to "csv", "csv" to "json", "json" to "yaml", "csv" to "yaml")

    /** Files above this size are left to the other tools or the cloud. */
    const val MAX_BYTES = 200L * 1024 * 1024

    fun supports(from: String, to: String): Boolean = (from to to) in pairs

    fun convert(input: Path, output: Path, from: String, to: String) {
        if (Files.size(input) > MAX_BYTES) throw ConversionException("fileTooLarge", "The built-in converter takes files up to 200 MB.")
        val text = try {
            Files.readString(input, StandardCharsets.UTF_8)
        } catch (e: CharacterCodingException) {
            // Read another way, some characters would change in the result.
            throw ConversionException("sourceUnreadable", "The file is not UTF-8 text, so the built-in converter would change some of its characters. Save it as UTF-8 and convert it again.", e)
        }
        Files.writeString(output, convertText(text, from, to), StandardCharsets.UTF_8)
    }

    /** The conversion of a file's text. */
    fun convertText(text: String, from: String, to: String): String = when (from to to) {
        "json" to "csv" -> Csv.write(table(parseJson(text)))
        "csv" to "json" -> Json.writePretty(Csv.toObjects(Csv.parse(text))) + "\n"
        "json" to "yaml" -> Yaml.write(parseJson(text))
        "csv" to "yaml" -> Yaml.write(Csv.toObjects(Csv.parse(text)))
        else -> throw ConversionException("unsupported", "The built-in converter does not turn ${KnownFormats.name(from)} into ${KnownFormats.name(to)}.")
    }

    /** JSON with every number as its text ([JsonNumber]): a Long or a Double would change some. */
    private fun parseJson(text: String): Any? = try {
        Json.parse(text, keepNumberText = true)
    } catch (e: JsonException) {
        throw ConversionException("sourceUnreadable", e.message ?: "Not valid JSON.", e)
    }

    /** Rows and columns from JSON: a list of objects, or one object. */
    fun table(value: Any?): List<List<String>> {
        val rows: List<Map<String, Any?>> = when (value) {
            is Map<*, *> -> listOf(value.asObject()!!)
            is List<*> -> {
                if (value.isEmpty()) throw ConversionException("unsupported", "The JSON list is empty: there is nothing to write.")
                value.mapIndexed { index, item ->
                    item.asObject() ?: throw ConversionException("unsupported", "To become CSV, the JSON must be a list of objects; item ${index + 1} is not an object.")
                }
            }
            else -> throw ConversionException("unsupported", "To become CSV, the JSON must be a list of objects or one object.")
        }
        // A cell is found by its path of keys: a key "a.b" and a key "b" inside "a" are two columns.
        val flat = rows.map { row -> LinkedHashMap<List<String>, String>().also { flatten(emptyList(), row, it) } }
        val paths = LinkedHashSet<List<String>>()
        flat.forEach { paths.addAll(it.keys) }
        val names = distinct(paths.map { it.joinToString(".") })
        return listOf(names) + flat.map { row -> paths.map { row[it] ?: "" } }
    }

    private fun flatten(prefix: List<String>, value: Map<String, Any?>, out: MutableMap<List<String>, String>) {
        for ((key, item) in value) {
            val path = prefix + key
            when (item) {
                is Map<*, *> -> if (item.isEmpty()) out[path] = "{}" else flatten(path, item.asObject()!!, out)
                is List<*> -> out[path] = Json.write(item)
                null -> out[path] = ""
                is String -> out[path] = item
                else -> out[path] = Json.write(item)
            }
        }
    }

    /** Names made different: the first keeps its name, a repeat gets `_2`, `_3`… (the first free). */
    private fun distinct(names: List<String>): List<String> {
        val taken = HashSet(names)
        val seen = HashSet<String>()
        return names.map { name ->
            if (seen.add(name)) {
                name
            } else {
                var n = 2
                while ("${name}_$n" in taken) n++
                "${name}_$n".also { taken.add(it) }
            }
        }
    }
}

/** CSV as RFC 4180 writes it: commas, double quotes, CRLF. */
object Csv {
    private val SEPARATORS = charArrayOf(',', ';', '\t', '|')

    /** A field in double quotes when it holds a comma, a quote or a line break, or starts or ends with a space or tab. */
    private fun field(cell: String): String {
        val quote = cell.any { it == ',' || it == '"' || it == '\n' || it == '\r' } ||
            (cell.isNotEmpty() && (cell.first() == ' ' || cell.first() == '\t' || cell.last() == ' ' || cell.last() == '\t'))
        return if (quote) "\"" + cell.replace("\"", "\"\"") + "\"" else cell
    }

    fun write(rows: List<List<String>>): String = buildString {
        for (row in rows) {
            append(row.joinToString(",") { field(it) })
            append("\r\n")
        }
    }

    /**
     * The field separator of a CSV: comma, semicolon, tab or `|`, whichever its first line uses most
     * outside quotes (the earlier of them on a tie; a comma when there is none).
     */
    fun separator(text: String): Char {
        var at = if (text.isNotEmpty() && text[0].code == 0xFEFF) 1 else 0
        while (at < text.length && (text[at] == '\r' || text[at] == '\n')) at++
        val counts = IntArray(SEPARATORS.size)
        var quoted = false
        while (at < text.length) {
            val c = text[at++]
            if (c == '"') {
                quoted = !quoted
            } else if (!quoted) {
                if (c == '\n' || c == '\r') break
                val index = SEPARATORS.indexOf(c)
                if (index >= 0) counts[index]++
            }
        }
        var best = 0
        for (index in 1 until SEPARATORS.size) if (counts[index] > counts[best]) best = index
        return SEPARATORS[best]
    }

    /** Reads CSV (quoted fields may hold separators, quotes and line breaks). */
    fun parse(text: String, separator: Char = separator(text)): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var at = if (text.isNotEmpty() && text[0].code == 0xFEFF) 1 else 0
        var cellStarted = false
        while (at < text.length) {
            val c = text[at]
            if (quoted) {
                if (c == '"') {
                    if (at + 1 < text.length && text[at + 1] == '"') {
                        cell.append('"')
                        at++
                    } else {
                        quoted = false
                    }
                } else {
                    cell.append(c)
                }
            } else {
                when (c) {
                    '"' -> if (cell.isEmpty()) quoted = true else cell.append(c)
                    separator -> {
                        row.add(cell.toString())
                        cell.clear()
                    }
                    '\r', '\n' -> {
                        if (c == '\r' && at + 1 < text.length && text[at + 1] == '\n') at++
                        row.add(cell.toString())
                        cell.clear()
                        if (!(row.size == 1 && row[0].isEmpty())) rows.add(row)
                        row = ArrayList()
                        cellStarted = false
                        at++
                        continue
                    }
                    else -> cell.append(c)
                }
            }
            cellStarted = true
            at++
        }
        if (quoted) throw ConversionException("sourceUnreadable", "The CSV file ends inside a quoted field.")
        if (cellStarted || cell.isNotEmpty() || row.isNotEmpty()) {
            row.add(cell.toString())
            if (!(row.size == 1 && row[0].isEmpty())) rows.add(row)
        }
        return rows
    }

    /** Spaces, tabs and line breaks around a header name are not part of it. */
    private fun trimName(name: String) = name.trim { it == ' ' || it == '\t' || it == '\r' || it == '\n' }

    /**
     * A name for each of [width] columns, every one different, so that no value is lost:
     * - the first column with a header name keeps it;
     * - any other column (a repeated name, an empty header cell, a cell beyond the header) gets its
     *   header name, or `column<N>` (N counted from 1) when it has none, followed by `_2`, `_3`…:
     *   the first that no column has taken yet.
     * `id,id` gives `id`, `id_2`; a header `column2` with a row `A,B` gives `column2`, `column2_2`.
     */
    fun columnNames(header: List<String>, width: Int): List<String> {
        val names = arrayOfNulls<String>(width)
        val taken = HashSet<String>()
        header.forEachIndexed { index, raw ->
            val name = trimName(raw)
            if (name.isNotEmpty() && taken.add(name)) names[index] = name
        }
        for (index in 0 until width) {
            if (names[index] != null) continue
            val base = trimName(header.getOrElse(index) { "" }).ifEmpty { "column${index + 1}" }
            var name = base
            var n = 2
            while (name in taken) name = "${base}_${n++}"
            names[index] = name
            taken.add(name)
        }
        return names.map { it!! }
    }

    /**
     * The rows after the first as objects keyed by the first row (the header). Every object has every
     * column, in the file's order; a cell a row does not have is empty. Values stay text.
     */
    fun toObjects(rows: List<List<String>>): List<Map<String, Any?>> {
        if (rows.isEmpty()) throw ConversionException("sourceUnreadable", "The CSV file is empty.")
        val names = columnNames(rows[0], rows.maxOf { it.size })
        return rows.drop(1).map { row ->
            val o = LinkedHashMap<String, Any?>()
            names.forEachIndexed { index, name -> o[name] = row.getOrElse(index) { "" } }
            o
        }
    }
}

/** YAML written from JSON values (block style; strings quoted when plain text would be read differently). */
object Yaml {
    private val reserved = setOf("true", "false", "yes", "no", "on", "off", "null", "y", "n", "~")

    fun write(value: Any?): String {
        val out = ArrayList<String>()
        lines(value, "", out)
        return out.joinToString("\n") + "\n"
    }

    private fun isCollection(value: Any?) = (value is Map<*, *> && value.isNotEmpty()) || (value is List<*> && value.isNotEmpty())

    private fun lines(value: Any?, indent: String, out: MutableList<String>) {
        when {
            value is List<*> && value.isNotEmpty() -> for (item in value) {
                if (!isCollection(item)) {
                    out += "$indent- ${scalar(item)}"
                } else {
                    // The first line of the nested block goes after the dash.
                    val inner = ArrayList<String>()
                    lines(item, "$indent  ", inner)
                    out += "$indent- " + inner[0].substring(indent.length + 2)
                    for (index in 1 until inner.size) out += inner[index]
                }
            }
            value is Map<*, *> && value.isNotEmpty() -> for ((key, item) in value) {
                if (isCollection(item)) {
                    out += "$indent${text(key.toString())}:"
                    lines(item, "$indent  ", out)
                } else {
                    out += "$indent${text(key.toString())}: ${scalar(item)}"
                }
            }
            else -> out += "$indent${scalar(value)}"
        }
    }

    private fun scalar(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> value.toString()
        is JsonNumber -> value.text
        is Number -> Json.write(value)
        is Map<*, *> -> "{}"
        is List<*> -> "[]"
        else -> text(value.toString())
    }

    /**
     * What a YAML file must not hold as is: control characters, DEL and the C1 controls, the line
     * and paragraph separators, the byte order mark, U+FFFE and U+FFFF, and lone surrogates.
     */
    private fun unprintable(cp: Int) = cp <= 0x1F || cp in 0x7F..0x9F || cp == 0x2028 || cp == 0x2029 ||
        cp == 0xFEFF || cp == 0xFFFE || cp == 0xFFFF || cp in 0xD800..0xDFFF

    /**
     * Text YAML reads back as the same text without quotes: it starts with a letter, `_`, `/` or a
     * character beyond ASCII, and goes on with letters, digits, spaces and `_ . / + ( ) @ -`.
     */
    private fun plain(s: String): Boolean {
        if (s.isEmpty() || s.endsWith(' ') || s.lowercase() in reserved) return false
        var at = 0
        while (at < s.length) {
            val cp = s.codePointAt(at)
            val ok = cp in 'A'.code..'Z'.code || cp in 'a'.code..'z'.code || cp == '_'.code || cp == '/'.code ||
                (at > 0 && (cp in '0'.code..'9'.code || cp in " .+()@-".map { it.code })) ||
                (cp >= 0xA0 && !unprintable(cp))
            if (!ok) return false
            at += Character.charCount(cp)
        }
        return true
    }

    /** A string as YAML: plain when that reads back the same, else in double quotes. */
    fun text(s: String): String {
        if (plain(s)) return s
        return buildString {
            append('"')
            var at = 0
            while (at < s.length) {
                val cp = s.codePointAt(at)
                when {
                    cp == '\\'.code -> append("\\\\")
                    cp == '"'.code -> append("\\\"")
                    cp == '\n'.code -> append("\\n")
                    cp == '\r'.code -> append("\\r")
                    cp == '\t'.code -> append("\\t")
                    unprintable(cp) -> append("\\u").append(cp.toString(16).padStart(4, '0'))
                    else -> appendCodePoint(cp)
                }
                at += Character.charCount(cp)
            }
            append('"')
        }
    }
}

/** Subtitles with the plugin's own code: SRT ↔ WebVTT. */
object SubtitleConversions {
    private val timing = Regex("^\\s*(\\d+:)?\\d{1,2}:\\d{2}[.,]\\d{3}\\s+-->\\s+(\\d+:)?\\d{1,2}:\\d{2}[.,]\\d{3}")

    fun supports(from: String, to: String): Boolean = (from == "srt" && to == "vtt") || (from == "vtt" && to == "srt")

    fun convert(input: Path, output: Path, from: String, to: String) {
        val text = Files.readString(input, StandardCharsets.UTF_8).removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        val result = when (from to to) {
            "srt" to "vtt" -> srtToVtt(text)
            "vtt" to "srt" -> vttToSrt(text)
            else -> throw ConversionException("unsupported", "The built-in converter does not turn ${KnownFormats.name(from)} into ${KnownFormats.name(to)}.")
        }
        Files.writeString(output, result, StandardCharsets.UTF_8)
    }

    fun srtToVtt(srt: String): String = buildString {
        append("WEBVTT\n\n")
        for (block in blocks(srt)) {
            val lines = block.lines()
            val at = lines.indexOfFirst { timing.containsMatchIn(it) }
            if (at < 0) continue
            append(fullTime(lines[at].replace(',', '.'))).append('\n')
            lines.drop(at + 1).forEach { append(it).append('\n') }
            append('\n')
        }
    }

    fun vttToSrt(vtt: String): String = buildString {
        var n = 0
        for (block in blocks(vtt)) {
            val lines = block.lines()
            val at = lines.indexOfFirst { timing.containsMatchIn(it) }
            // The header, NOTE, STYLE and REGION blocks have no timing line.
            if (at < 0) continue
            val times = timing.find(lines[at])!!.value.trim().replace('.', ',')
            append(++n).append('\n')
            append(fullTime(times)).append('\n')
            // WebVTT's class and voice tags; SRT keeps <b>, <i> and <u>.
            lines.drop(at + 1).forEach { append(it.replace(Regex("</?[cv](\\.[^>\\s]*)?(\\s[^>]*)?>"), "")).append('\n') }
            append('\n')
        }
    }

    private fun blocks(text: String): List<String> = text.split(Regex("\n\\s*\n")).map { it.trim('\n') }.filter { it.isNotBlank() }

    /** `1:02.500` → `00:01:02.500`: SRT needs hours, and so do most VTT readers. */
    private fun fullTime(line: String): String = line.replace(Regex("(?<![\\d:])(\\d{1,2}):(\\d{2})([.,]\\d{3})(?!\\d)")) { m ->
        val (minutes, seconds, fraction) = m.destructured
        "00:${minutes.padStart(2, '0')}:$seconds$fraction"
    }.replace(Regex("(?<!\\d)(\\d):(\\d{2}):(\\d{2})")) { m -> "0${m.value}" }
}
