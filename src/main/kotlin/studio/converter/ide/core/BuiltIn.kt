package studio.converter.ide.core

import java.awt.Color
import java.awt.image.BufferedImage
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
 */
object DataConversions {
    private val pairs = setOf("json" to "csv", "csv" to "json", "json" to "yaml", "csv" to "yaml")

    /** Files above this size are left to the other tools or the cloud. */
    const val MAX_BYTES = 200L * 1024 * 1024

    fun supports(from: String, to: String): Boolean = (from to to) in pairs

    fun convert(input: Path, output: Path, from: String, to: String) {
        if (Files.size(input) > MAX_BYTES) throw ConversionException("fileTooLarge", "The built-in converter takes files up to 200 MB.")
        val text = Files.readString(input, StandardCharsets.UTF_8)
        val result = when (from to to) {
            "json" to "csv" -> Csv.write(table(parseJson(text)))
            "csv" to "json" -> Json.writePretty(Csv.toObjects(Csv.parse(text))) + "\n"
            "json" to "yaml" -> Yaml.write(parseJson(text))
            "csv" to "yaml" -> Yaml.write(Csv.toObjects(Csv.parse(text)))
            else -> throw ConversionException("unsupported", "The built-in converter does not turn ${KnownFormats.name(from)} into ${KnownFormats.name(to)}.")
        }
        Files.writeString(output, result, StandardCharsets.UTF_8)
    }

    private fun parseJson(text: String): Any? = try {
        Json.parse(text)
    } catch (e: JsonException) {
        throw ConversionException("sourceUnreadable", e.message ?: "Not valid JSON.", e)
    }

    /** Rows and columns from JSON: a list of objects, or one object. */
    fun table(value: Any?): List<List<String>> {
        val rows: List<Map<String, Any?>> = when (value) {
            is Map<*, *> -> listOf(value.asObject()!!)
            is List<*> -> value.map {
                it.asObject() ?: throw ConversionException("unsupported", "To become CSV, the JSON must be a list of objects; item ${value.indexOf(it) + 1} is not an object.")
            }
            else -> throw ConversionException("unsupported", "To become CSV, the JSON must be a list of objects or one object.")
        }
        val flat = rows.map { row -> LinkedHashMap<String, String>().also { flatten("", row, it) } }
        val columns = LinkedHashSet<String>()
        flat.forEach { columns.addAll(it.keys) }
        return listOf(columns.toList()) + flat.map { row -> columns.map { row[it] ?: "" } }
    }

    private fun flatten(prefix: String, value: Map<String, Any?>, out: MutableMap<String, String>) {
        for ((key, item) in value) {
            val name = if (prefix.isEmpty()) key else "$prefix.$key"
            when (item) {
                is Map<*, *> -> if (item.isEmpty()) out[name] = "{}" else flatten(name, item.asObject()!!, out)
                is List<*> -> out[name] = Json.write(item)
                null -> out[name] = ""
                is String -> out[name] = item
                else -> out[name] = Json.write(item)
            }
        }
    }
}

/** CSV as RFC 4180 writes it: commas, double quotes, CRLF. */
object Csv {
    fun write(rows: List<List<String>>): String = buildString {
        for (row in rows) {
            append(row.joinToString(",") { cell -> if (cell.any { it == ',' || it == '"' || it == '\n' || it == '\r' } || cell != cell.trim()) "\"" + cell.replace("\"", "\"\"") + "\"" else cell })
            append("\r\n")
        }
    }

    /** Reads CSV (quoted fields may hold commas, quotes and line breaks). */
    fun parse(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var at = if (text.startsWith("﻿")) 1 else 0
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
                    ',' -> {
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

    /** The rows after the first as objects keyed by the first row (the header). Values stay text. */
    fun toObjects(rows: List<List<String>>): List<Map<String, Any?>> {
        if (rows.isEmpty()) return emptyList()
        val header = rows[0].mapIndexed { index, name -> name.ifEmpty { "column${index + 1}" } }
        return rows.drop(1).map { row ->
            val o = LinkedHashMap<String, Any?>()
            header.forEachIndexed { index, name -> o[name] = row.getOrElse(index) { "" } }
            for (index in header.size until row.size) o["column${index + 1}"] = row[index]
            o
        }
    }
}

/** YAML written from JSON values (block style; strings quoted when plain text would be read differently). */
object Yaml {
    private val plain = Regex("^[A-Za-z_][A-Za-z0-9_ ./-]*$")
    private val reserved = setOf("true", "false", "yes", "no", "on", "off", "null", "y", "n", "~")

    fun write(value: Any?): String = buildString {
        when {
            value is Map<*, *> && value.isNotEmpty() -> map(this, value, 0)
            value is List<*> && value.isNotEmpty() -> list(this, value, 0)
            else -> append(scalar(value)).append('\n')
        }
    }

    private fun map(out: StringBuilder, value: Map<*, *>, indent: Int) {
        for ((key, item) in value) {
            out.append(" ".repeat(indent)).append(text(key.toString())).append(':')
            child(out, item, indent)
        }
    }

    private fun list(out: StringBuilder, value: List<*>, indent: Int) {
        for (item in value) {
            out.append(" ".repeat(indent)).append('-')
            child(out, item, indent)
        }
    }

    private fun child(out: StringBuilder, item: Any?, indent: Int) {
        when {
            item is Map<*, *> && item.isNotEmpty() -> {
                out.append('\n')
                map(out, item, indent + 2)
            }
            item is List<*> && item.isNotEmpty() -> {
                out.append('\n')
                list(out, item, indent + 2)
            }
            else -> out.append(' ').append(scalar(item)).append('\n')
        }
    }

    private fun scalar(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> value.toString()
        is Number -> Json.write(value)
        is Map<*, *> -> "{}"
        is List<*> -> "[]"
        else -> text(value.toString())
    }

    /** A string as YAML: plain when safe, else JSON's double-quoted form (which YAML reads too). */
    fun text(s: String): String =
        if (plain.matches(s) && s.lowercase() !in reserved && s == s.trim() && !s.contains(": ") && !s.contains(" #")) s else Json.quote(s)
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
