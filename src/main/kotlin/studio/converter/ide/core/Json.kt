package studio.converter.ide.core

/**
 * A small JSON reader and writer. The plugin ships no libraries of its own, so the API's answers
 * and the plugin's data conversions (JSON to CSV or YAML) are read with this.
 *
 * Objects become [Map]s (keys in document order), arrays [List]s, numbers [Long] when they are
 * whole and fit, otherwise [Double].
 */
object Json {
    /** Parses a whole JSON document. */
    fun parse(text: String): Any? = Parser(text).document()

    /** Writes a value (maps, iterables, strings, numbers, booleans, null) as compact JSON. */
    fun write(value: Any?): String = StringBuilder().also { write(it, value, null, 0) }.toString()

    /** Writes a value as JSON indented with two spaces. */
    fun writePretty(value: Any?): String = StringBuilder().also { write(it, value, "  ", 0) }.toString()

    /** A JSON string literal. */
    fun quote(text: String): String = StringBuilder().also { quote(it, text) }.toString()

    private fun write(out: StringBuilder, value: Any?, indent: String?, depth: Int) {
        when (value) {
            null -> out.append("null")
            is String -> quote(out, value)
            is Boolean -> out.append(value)
            is Double, is Float -> {
                val d = (value as Number).toDouble()
                require(d.isFinite()) { "JSON has no NaN or infinity" }
                if (d == Math.rint(d) && Math.abs(d) < 1e15) out.append(d.toLong()) else out.append(d)
            }
            is Number -> out.append(value.toString())
            is Map<*, *> -> {
                if (value.isEmpty()) {
                    out.append("{}")
                    return
                }
                out.append('{')
                var first = true
                for ((key, item) in value) {
                    if (!first) out.append(',')
                    first = false
                    newline(out, indent, depth + 1)
                    quote(out, key.toString())
                    out.append(if (indent == null) ":" else ": ")
                    write(out, item, indent, depth + 1)
                }
                newline(out, indent, depth)
                out.append('}')
            }
            is Iterable<*> -> {
                val items = value.toList()
                if (items.isEmpty()) {
                    out.append("[]")
                    return
                }
                out.append('[')
                items.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    newline(out, indent, depth + 1)
                    write(out, item, indent, depth + 1)
                }
                newline(out, indent, depth)
                out.append(']')
            }
            else -> quote(out, value.toString())
        }
    }

    private fun newline(out: StringBuilder, indent: String?, depth: Int) {
        if (indent == null) return
        out.append('\n')
        repeat(depth) { out.append(indent) }
    }

    private fun quote(out: StringBuilder, text: String) {
        out.append('"')
        for (c in text) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' || c == ' ' || c == ' ' -> out.append(String.format("\\u%04x", c.code))
                else -> out.append(c)
            }
        }
        out.append('"')
    }
}

/** A document that is not JSON, or JSON of another shape than expected. */
class JsonException(message: String) : RuntimeException(message)

private class Parser(private val text: String) {
    private var at = 0

    fun document(): Any? {
        // A UTF-8 byte order mark read as text.
        if (text.startsWith("﻿")) at = 1
        skipSpace()
        val value = value(0)
        skipSpace()
        if (at != text.length) fail("unexpected text after the value")
        return value
    }

    private fun value(depth: Int): Any? {
        if (depth > MAX_DEPTH) fail("nested too deeply")
        if (at >= text.length) fail("unexpected end")
        return when (val c = text[at]) {
            '{' -> obj(depth)
            '[' -> array(depth)
            '"' -> string()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> if (c == '-' || c in '0'..'9') number() else fail("unexpected character '$c'")
        }
    }

    private fun obj(depth: Int): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        at++
        skipSpace()
        if (peek() == '}') {
            at++
            return map
        }
        while (true) {
            skipSpace()
            if (peek() != '"') fail("expected a key")
            val key = string()
            skipSpace()
            expect(':')
            skipSpace()
            map[key] = value(depth + 1)
            skipSpace()
            when (peek()) {
                ',' -> at++
                '}' -> {
                    at++
                    return map
                }
                else -> fail("expected ',' or '}'")
            }
        }
    }

    private fun array(depth: Int): List<Any?> {
        val list = ArrayList<Any?>()
        at++
        skipSpace()
        if (peek() == ']') {
            at++
            return list
        }
        while (true) {
            skipSpace()
            list.add(value(depth + 1))
            skipSpace()
            when (peek()) {
                ',' -> at++
                ']' -> {
                    at++
                    return list
                }
                else -> fail("expected ',' or ']'")
            }
        }
    }

    private fun string(): String {
        at++
        val out = StringBuilder()
        while (true) {
            if (at >= text.length) fail("unterminated string")
            val c = text[at++]
            when {
                c == '"' -> return out.toString()
                c == '\\' -> {
                    if (at >= text.length) fail("unterminated string")
                    when (val e = text[at++]) {
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        '/' -> out.append('/')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (at + 4 > text.length) fail("bad \\u escape")
                            val hex = text.substring(at, at + 4)
                            out.append(hex.toIntOrNull(16)?.toChar() ?: fail("bad \\u escape"))
                            at += 4
                        }
                        else -> fail("bad escape '\\$e'")
                    }
                }
                c < ' ' -> fail("control character in a string")
                else -> out.append(c)
            }
        }
    }

    private fun number(): Number {
        val start = at
        if (peek() == '-') at++
        while (at < text.length && text[at] in '0'..'9') at++
        var whole = true
        if (at < text.length && text[at] == '.') {
            whole = false
            at++
            while (at < text.length && text[at] in '0'..'9') at++
        }
        if (at < text.length && (text[at] == 'e' || text[at] == 'E')) {
            whole = false
            at++
            if (at < text.length && (text[at] == '+' || text[at] == '-')) at++
            while (at < text.length && text[at] in '0'..'9') at++
        }
        val token = text.substring(start, at)
        if (!NUMBER.matches(token)) fail("bad number '$token'")
        if (whole) token.toLongOrNull()?.let { return it }
        return token.toDouble()
    }

    private fun literal(word: String, value: Any?): Any? {
        if (!text.startsWith(word, at)) fail("unexpected text")
        at += word.length
        return value
    }

    private fun peek(): Char = if (at < text.length) text[at] else '\u0000'

    private fun expect(c: Char) {
        if (peek() != c) fail("expected '$c'")
        at++
    }

    private fun skipSpace() {
        while (at < text.length && text[at].let { it == ' ' || it == '\n' || it == '\r' || it == '\t' }) at++
    }

    private fun fail(reason: String): Nothing = throw JsonException("Not valid JSON at character ${at + 1}: $reason.")

    companion object {
        const val MAX_DEPTH = 512
        val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
    }
}

/** The value as a JSON object, or null. */
@Suppress("UNCHECKED_CAST")
fun Any?.asObject(): Map<String, Any?>? = this as? Map<String, Any?>

/** The value as a JSON array, or null. */
fun Any?.asArray(): List<Any?>? = this as? List<Any?>

/** A string member, or null. */
fun Map<String, Any?>.string(key: String): String? = this[key] as? String

/** A numeric member as a Long, or null. */
fun Map<String, Any?>.long(key: String): Long? = (this[key] as? Number)?.toLong()

/** A numeric member as a Double, or null. */
fun Map<String, Any?>.double(key: String): Double? = (this[key] as? Number)?.toDouble()

/** An array member's strings (other items skipped), or an empty list. */
fun Map<String, Any?>.strings(key: String): List<String> = (this[key] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
