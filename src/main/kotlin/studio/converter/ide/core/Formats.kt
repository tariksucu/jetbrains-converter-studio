package studio.converter.ide.core

import java.util.Locale

/** A file format: the API's id (`jpg`, `docx`, `tgz`), its name, kind and file extensions. */
data class FormatInfo(
    val id: String,
    val name: String,
    val category: String,
    /** Extensions without the dot, lower case. */
    val extensions: List<String>,
    /** What Converter Studio's cloud turns it into (the API's `to`), most used first; empty when not known. */
    val to: List<String> = emptyList(),
)

/**
 * The formats the plugin recognizes by their file extension, with Converter Studio's ids and names.
 * Only names and extensions: which conversions the cloud offers always comes from the API
 * (`GET /v1/formats`), and which ones this computer can do from the tools found on it.
 */
object KnownFormats {
    val ALL: List<FormatInfo> = listOf(
        f("7z", "7Z", "archive", "7z"),
        f("rar", "RAR", "archive", "rar"),
        f("tar", "TAR", "archive", "tar"),
        f("tgz", "TAR.GZ", "archive", "tar.gz", "tgz"),
        f("zip", "ZIP", "archive", "zip"),
        f("aac", "AAC", "audio", "aac"),
        f("aiff", "AIFF", "audio", "aiff", "aif"),
        f("flac", "FLAC", "audio", "flac"),
        f("m4a", "M4A", "audio", "m4a"),
        f("mp3", "MP3", "audio", "mp3"),
        f("ogg", "OGG", "audio", "ogg", "oga"),
        f("opus", "Opus", "audio", "opus"),
        f("wav", "WAV", "audio", "wav"),
        f("wma", "WMA", "audio", "wma"),
        f("json", "JSON", "data", "json"),
        f("xml", "XML", "data", "xml"),
        f("yaml", "YAML", "data", "yaml", "yml"),
        f("doc", "DOC", "document", "doc"),
        f("docx", "DOCX", "document", "docx"),
        f("html", "HTML", "document", "html", "htm"),
        f("md", "Markdown", "document", "md", "markdown"),
        f("odt", "ODT", "document", "odt"),
        f("pdf", "PDF", "document", "pdf"),
        f("rtf", "RTF", "document", "rtf"),
        f("txt", "TXT", "document", "txt", "text"),
        f("azw3", "AZW3", "ebook", "azw3"),
        f("epub", "EPUB", "ebook", "epub"),
        f("fb2", "FB2", "ebook", "fb2"),
        f("mobi", "MOBI", "ebook", "mobi"),
        f("otf", "OTF", "font", "otf"),
        f("ttf", "TTF", "font", "ttf"),
        f("woff", "WOFF", "font", "woff"),
        f("woff2", "WOFF2", "font", "woff2"),
        f("arw", "ARW", "image", "arw"),
        f("avif", "AVIF", "image", "avif"),
        f("bmp", "BMP", "image", "bmp"),
        f("cr2", "CR2", "image", "cr2"),
        f("dng", "DNG", "image", "dng"),
        f("gif", "GIF", "image", "gif"),
        f("heic", "HEIC", "image", "heic", "heif"),
        f("ico", "ICO", "image", "ico"),
        f("jpg", "JPG", "image", "jpg", "jpeg", "jpe", "jfif"),
        f("nef", "NEF", "image", "nef"),
        f("png", "PNG", "image", "png"),
        f("tiff", "TIFF", "image", "tiff", "tif"),
        f("webp", "WebP", "image", "webp"),
        f("odp", "ODP", "presentation", "odp"),
        f("ppt", "PPT", "presentation", "ppt"),
        f("pptx", "PPTX", "presentation", "pptx"),
        f("csv", "CSV", "spreadsheet", "csv"),
        f("ods", "ODS", "spreadsheet", "ods"),
        f("xls", "XLS", "spreadsheet", "xls"),
        f("xlsx", "XLSX", "spreadsheet", "xlsx"),
        f("ass", "ASS", "subtitle", "ass", "ssa"),
        f("srt", "SRT", "subtitle", "srt"),
        f("vtt", "VTT", "subtitle", "vtt"),
        f("ai", "AI", "vector", "ai"),
        f("eps", "EPS", "vector", "eps"),
        f("svg", "SVG", "vector", "svg"),
        f("3gp", "3GP", "video", "3gp", "3g2"),
        f("avi", "AVI", "video", "avi"),
        f("flv", "FLV", "video", "flv"),
        f("mkv", "MKV", "video", "mkv"),
        f("mov", "MOV", "video", "mov", "qt"),
        f("mp4", "MP4", "video", "mp4", "m4v"),
        f("webm", "WebM", "video", "webm"),
        f("wmv", "WMV", "video", "wmv"),
    )

    private val byId = ALL.associateBy { it.id }

    operator fun get(id: String): FormatInfo? = byId[id]

    /** A format's name (`WebP`), or its id in capitals. */
    fun name(id: String): String = byId[id]?.name ?: id.uppercase(Locale.ROOT)

    /** The file extension a result in this format gets (`tar.gz` for `tgz`). */
    fun extension(id: String): String = if (id == "tgz") "tar.gz" else id

    private fun f(id: String, name: String, category: String, vararg extensions: String) =
        FormatInfo(id, name, category, extensions.toList())
}

/**
 * The formats the plugin knows: the API's catalogue when it has been read ([fromApi]), else the
 * built-in list of names and extensions (then nothing is known about the cloud's conversions).
 */
class Catalogue(val formats: List<FormatInfo>, val fromApi: Boolean) {
    private val byId = formats.associateBy { it.id }

    operator fun get(id: String): FormatInfo? = byId[id]

    /** A format's name, from the catalogue or the built-in list. */
    fun name(id: String): String = byId[id]?.name ?: KnownFormats.name(id)

    /** The format of a file name by its extension (`archive.tar.gz` → `tgz`), or null. */
    fun formatOfName(fileName: String): String? {
        val lower = fileName.lowercase(Locale.ROOT)
        var best: Pair<String, Int>? = null
        for (format in formats) {
            for (extension in format.extensions) {
                val suffix = ".$extension"
                // A name that is only the extension (".png") is a hidden file without one.
                if (lower.endsWith(suffix) && lower.length > suffix.length && (best == null || suffix.length > best.second)) {
                    best = format.id to suffix.length
                }
            }
        }
        return best?.first
    }

    /** What the cloud turns a format into, when the catalogue came from the API. */
    fun cloudTargets(from: String): List<String> = if (fromApi) byId[from]?.to.orEmpty() else emptyList()

    companion object {
        /** The built-in list: names and extensions only. */
        val BUILT_IN = Catalogue(KnownFormats.ALL, fromApi = false)

        /** The API's `GET /v1/formats` answer. */
        fun fromApi(json: String): Catalogue {
            val formats = Json.parse(json).asObject()?.get("formats").asArray()
                ?: throw JsonException("The formats list has no 'formats'.")
            val list = formats.mapNotNull { item ->
                val o = item.asObject() ?: return@mapNotNull null
                val id = o.string("id") ?: return@mapNotNull null
                FormatInfo(
                    id = id,
                    name = o.string("name") ?: KnownFormats.name(id),
                    category = o.string("category") ?: "",
                    extensions = o.strings("extensions").map { it.lowercase(Locale.ROOT) },
                    to = o.strings("to"),
                )
            }
            return Catalogue(list, fromApi = true)
        }
    }
}
