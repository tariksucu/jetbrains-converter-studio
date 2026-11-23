package studio.converter.ide.core

/** Who does a conversion on this computer. */
enum class Engine(val tools: List<ToolId>, private val builtInLabel: String? = null) {
    /** Java's own image reader and writer, inside the IDE. */
    BUILT_IN_IMAGE(emptyList(), "built in"),
    /** The plugin's own JSON, CSV and YAML code. */
    BUILT_IN_DATA(emptyList(), "built in"),
    /** The plugin's own SRT and VTT code. */
    BUILT_IN_SUBTITLE(emptyList(), "built in"),
    FFMPEG(listOf(ToolId.FFMPEG)),
    LIBREOFFICE(listOf(ToolId.LIBREOFFICE)),
    PANDOC(listOf(ToolId.PANDOC)),
    /** Pandoc to DOCX, then LibreOffice to PDF. */
    PANDOC_LIBREOFFICE(listOf(ToolId.PANDOC, ToolId.LIBREOFFICE)),
    IMAGEMAGICK(listOf(ToolId.IMAGEMAGICK)),
    GHOSTSCRIPT(listOf(ToolId.GHOSTSCRIPT)),
    TESSERACT(listOf(ToolId.TESSERACT)),
    /** Ghostscript renders the pages, Tesseract reads them. */
    GHOSTSCRIPT_TESSERACT(listOf(ToolId.GHOSTSCRIPT, ToolId.TESSERACT));

    /** "FFmpeg", "Pandoc + LibreOffice", "built in". */
    val label: String get() = builtInLabel ?: tools.joinToString(" + ") { it.displayName }
}

/** One way to turn a format into another on this computer. */
data class LocalRecipe(val from: String, val to: String, val engine: Engine) {
    /** A same-format conversion (only PDF): a smaller copy. */
    val compress: Boolean get() = from == to

    /** What the dialog shows beside "On this computer". */
    val label: String get() = engine.label
}

/** Settings the local conversions use. */
data class LocalOptions(
    /** Tesseract's languages, `eng` or `eng+deu`. */
    val ocrLanguages: String = "eng",
)

/**
 * Our own recipes: which formats this computer can convert, given the tools found. These are
 * simple, public recipes (MIT); each runs one program (or two) with a fixed argument list.
 */
object LocalRecipes {
    /** Targets in the order the dialog lists them. */
    val ORDER = listOf(
        "jpg", "png", "webp", "avif", "pdf", "docx", "txt", "mp4", "webm", "mp3", "gif", "html", "md", "odt", "rtf",
        "epub", "xlsx", "ods", "csv", "json", "yaml", "pptx", "odp", "doc", "xls", "ppt", "tiff", "bmp", "ico",
        "mov", "mkv", "avi", "m4a", "wav", "flac", "aac", "ogg", "opus", "aiff", "wma", "srt", "vtt", "ass",
    )

    /** ImageMagick's names of our formats. */
    val MAGICK_NAMES = mapOf(
        "jpg" to "JPEG", "png" to "PNG", "gif" to "GIF", "bmp" to "BMP", "tiff" to "TIFF", "webp" to "WEBP",
        "avif" to "AVIF", "heic" to "HEIC", "ico" to "ICO", "svg" to "SVG", "cr2" to "CR2", "nef" to "NEF",
        "arw" to "ARW", "dng" to "DNG", "pdf" to "PDF",
    )
    private val MAGICK_IN = setOf("jpg", "png", "gif", "bmp", "tiff", "webp", "avif", "heic", "ico", "svg", "cr2", "nef", "arw", "dng")
    private val MAGICK_OUT = setOf("jpg", "png", "gif", "bmp", "tiff", "webp", "avif", "ico", "pdf")

    /** What Java's ImageIO reads and writes without plug-ins. */
    val IMAGEIO = setOf("png", "jpg", "bmp", "gif", "tiff")

    val VIDEO = setOf("3gp", "avi", "flv", "mkv", "mov", "mp4", "webm", "wmv")
    val AUDIO = setOf("aac", "aiff", "flac", "m4a", "mp3", "ogg", "opus", "wav", "wma")
    private val VIDEO_OUT = setOf("mp4", "webm", "mov", "mkv", "avi", "gif")
    private val SUBTITLES = setOf("srt", "vtt", "ass")

    private val WRITER_IN = setOf("doc", "docx", "odt", "rtf", "txt", "html")
    private val WRITER_OUT = setOf("pdf", "docx", "doc", "odt", "rtf", "txt", "html")
    private val CALC_IN = setOf("xls", "xlsx", "ods", "csv")
    private val CALC_OUT = setOf("pdf", "xlsx", "xls", "ods", "csv")
    private val IMPRESS_IN = setOf("ppt", "pptx", "odp")
    private val IMPRESS_OUT = setOf("pdf", "pptx", "ppt", "odp")

    /** Pandoc's names of our formats, for reading and for writing. */
    val PANDOC_READ = mapOf("md" to "markdown", "html" to "html", "docx" to "docx", "odt" to "odt", "epub" to "epub", "rtf" to "rtf")
    val PANDOC_WRITE = mapOf(
        "md" to "gfm", "html" to "html", "docx" to "docx", "odt" to "odt", "epub" to "epub", "rtf" to "rtf", "txt" to "plain",
    )

    /** Images Tesseract reads. */
    private val OCR_IN = setOf("png", "jpg", "tiff", "bmp", "gif", "webp")

    /** The best local recipe from [from] to [to], or null if this computer cannot do it. */
    fun find(from: String, to: String, tools: ToolSet, options: LocalOptions = LocalOptions()): LocalRecipe? {
        val engine = engine(from, to, tools, options) ?: return null
        return LocalRecipe(from, to, engine)
    }

    /** Every local recipe for a format, in [ORDER]. */
    fun targets(from: String, tools: ToolSet, options: LocalOptions = LocalOptions()): List<LocalRecipe> =
        ORDER.mapNotNull { find(from, it, tools, options) }

    private fun engine(from: String, to: String, tools: ToolSet, options: LocalOptions): Engine? {
        val magick = tools[ToolId.IMAGEMAGICK]
        val ffmpeg = tools[ToolId.FFMPEG]
        val pandoc = tools[ToolId.PANDOC]
        val office = tools.has(ToolId.LIBREOFFICE)
        if (from == to) {
            // A smaller PDF is the only same-format conversion done here.
            return if (from == "pdf" && tools.has(ToolId.GHOSTSCRIPT)) Engine.GHOSTSCRIPT else null
        }
        // Text recognition.
        if (to == "txt" && from in OCR_IN) return if (tesseractReady(tools, options)) Engine.TESSERACT else null
        if (to == "txt" && from == "pdf") {
            return if (tools.has(ToolId.GHOSTSCRIPT) && tesseractReady(tools, options)) Engine.GHOSTSCRIPT_TESSERACT else null
        }
        if (from == "eps" && to == "pdf") return if (tools.has(ToolId.GHOSTSCRIPT)) Engine.GHOSTSCRIPT else null
        // Images.
        if (from in MAGICK_IN && to in MAGICK_OUT) {
            if (magick != null && MAGICK_NAMES[from] in magick.imageRead && MAGICK_NAMES[to] in magick.imageWrite) return Engine.IMAGEMAGICK
            if (from in IMAGEIO && to in IMAGEIO) return Engine.BUILT_IN_IMAGE
            return null
        }
        // Data.
        if (DataConversions.supports(from, to)) return Engine.BUILT_IN_DATA
        // Subtitles.
        if (from in SUBTITLES && to in SUBTITLES) {
            if (SubtitleConversions.supports(from, to)) return Engine.BUILT_IN_SUBTITLE
            return if (ffmpeg != null && subtitleEncoder(to) in ffmpeg.encoders) Engine.FFMPEG else null
        }
        // Video and audio.
        if (from in VIDEO || from in AUDIO || (from == "gif" && (to == "mp4" || to == "webm"))) {
            if (ffmpeg == null) return null
            val video = from in VIDEO || from == "gif"
            if (to in VIDEO_OUT && !video) return null
            if (to !in VIDEO_OUT && to !in AUDIO) return null
            if (from == "gif" && to !in setOf("mp4", "webm")) return null
            return if (FfmpegArgs.codecs(to, ffmpeg.encoders) != null) Engine.FFMPEG else null
        }
        // Documents, spreadsheets, slides.
        val pandocCan = pandoc != null && PANDOC_READ[from]?.let { it in pandoc.pandocIn } == true &&
            PANDOC_WRITE[to]?.let { it in pandoc.pandocOut } == true
        if (from in setOf("md", "epub") || to in setOf("md", "epub")) {
            if (pandocCan) return Engine.PANDOC
            if (to == "pdf" && from in setOf("md", "epub") && office && pandoc != null &&
                PANDOC_READ[from] in pandoc.pandocIn && "docx" in pandoc.pandocOut
            ) {
                return Engine.PANDOC_LIBREOFFICE
            }
            return null
        }
        if (office) {
            if (from in WRITER_IN && to in WRITER_OUT) return Engine.LIBREOFFICE
            if (from in CALC_IN && to in CALC_OUT) return Engine.LIBREOFFICE
            if (from in IMPRESS_IN && to in IMPRESS_OUT) return Engine.LIBREOFFICE
        }
        if (pandocCan) return Engine.PANDOC
        return null
    }

    private fun tesseractReady(tools: ToolSet, options: LocalOptions): Boolean {
        val tesseract = tools[ToolId.TESSERACT] ?: return false
        val wanted = OcrLanguages.parse(options.ocrLanguages) ?: return false
        // An empty list means Tesseract did not say: let it try.
        return tesseract.languages.isEmpty() || tesseract.languages.containsAll(wanted)
    }

    /** FFmpeg's encoder for a subtitle format. */
    fun subtitleEncoder(to: String): String = when (to) {
        "srt" -> "srt"
        "vtt" -> "webvtt"
        else -> "ass"
    }
}

/** Tesseract's language setting: `eng` or `eng+deu`. */
object OcrLanguages {
    private val pattern = Regex("^[A-Za-z_]+(\\+[A-Za-z_]+)*$")

    /** The languages, or null if the text is not a valid setting. */
    fun parse(text: String): List<String>? {
        val trimmed = text.trim().ifEmpty { "eng" }
        return if (pattern.matches(trimmed)) trimmed.split('+') else null
    }
}
