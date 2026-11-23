package studio.converter.ide.core

import java.nio.file.Path

/**
 * The argument lists of the local recipes. Each function only builds a list; [LocalConverter]
 * runs it without a shell, so no file name is ever read as a command or an option (paths are
 * absolute, so none starts with `-`).
 */
object Commands {
    /** FFmpeg: [input] → [output], whose extension names the container. */
    fun ffmpeg(tool: ToolInfo, input: Path, output: Path, to: String): List<String> {
        val base = listOf(tool.path.toString(), "-hide_banner", "-nostdin", "-loglevel", "error", "-y", "-i", input.toString())
        if (to in setOf("srt", "vtt", "ass")) return base + listOf("-map", "0:s:0", output.toString())
        val codecs = FfmpegArgs.codecs(to, tool.encoders)
            ?: throw ConversionException("toolMissing", "This FFmpeg has no encoder for ${KnownFormats.name(to)}.")
        return base + codecs + output.toString()
    }

    /**
     * LibreOffice: converts [input] into [outDir] (it names the file itself). [profile] is a
     * LibreOffice profile folder of the plugin's own, so a LibreOffice window that is already open
     * does not swallow the request.
     */
    fun libreOffice(tool: ToolInfo, input: Path, outDir: Path, to: String, profile: Path): List<String> = listOf(
        tool.path.toString(),
        // A file: URL; without a trailing slash, whether the folder exists yet or not.
        "-env:UserInstallation=${profile.toUri().toString().trimEnd('/')}",
        "--headless",
        "--invisible",
        "--norestore",
        "--nologo",
        "--nodefault",
        "--nolockcheck",
        "--convert-to",
        libreOfficeFilter(input, to),
        "--outdir",
        outDir.toString(),
        input.toString(),
    )

    /** LibreOffice's `--convert-to` value: the extension, with a filter where the default is not right. */
    fun libreOfficeFilter(input: Path, to: String): String {
        val from = Naming.extensionOf(input.fileName.toString()).removePrefix(".").lowercase()
        return when {
            to == "txt" -> "txt:Text (encoded):UTF8"
            to == "csv" -> "csv:Text - txt - csv (StarCalc):44,34,76,1"
            to == "pdf" && (from == "html" || from == "htm") -> "pdf:writer_web_pdf_Export"
            to == "html" -> "html:XHTML Writer File:UTF8"
            else -> to
        }
    }

    /** Pandoc: [input] ([from]) → [output] ([to]). */
    fun pandoc(tool: ToolInfo, input: Path, output: Path, from: String, to: String): List<String> {
        val reader = LocalRecipes.PANDOC_READ[from] ?: throw ConversionException("toolMissing", "Pandoc does not read ${KnownFormats.name(from)}.")
        val writer = LocalRecipes.PANDOC_WRITE[to] ?: throw ConversionException("toolMissing", "Pandoc does not write ${KnownFormats.name(to)}.")
        val args = mutableListOf(tool.path.toString(), "--from", reader, "--to", writer)
        if (to in setOf("html", "rtf", "epub")) args += "--standalone"
        if (to == "html") args += listOf("--metadata", "pagetitle=${Naming.stemOf(input.fileName.toString()).ifEmpty { "Document" }}")
        args += listOf("--output", output.toString(), input.toString())
        return args
    }

    /**
     * ImageMagick: [input] → [output]. Formats without animation take the first frame only; a
     * format without transparency gets a white background; photos are turned upright. [input] is
     * a copy with a plain name in the work folder: ImageMagick reads `[…]` in a name as a frame
     * number. ImageMagick 7's `magick` and ImageMagick 6's `convert` take the same arguments here.
     */
    fun imageMagick(tool: ToolInfo, input: Path, output: Path, to: String): List<String> {
        val firstFrameOnly = to in setOf("jpg", "png", "bmp", "ico", "avif")
        val args = mutableListOf(tool.path.toString())
        args += if (firstFrameOnly) "$input[0]" else input.toString()
        args += "-auto-orient"
        when (to) {
            "jpg" -> args += listOf("-background", "white", "-alpha", "remove", "-alpha", "off", "-quality", "90")
            "bmp" -> args += listOf("-background", "white", "-alpha", "remove", "-alpha", "off")
            "webp" -> args += listOf("-quality", "80")
            "avif" -> args += listOf("-quality", "60")
            "ico" -> args += listOf("-background", "none", "-define", "icon:auto-resize=256,128,64,48,32,16")
        }
        args += "${LocalRecipes.MAGICK_NAMES[to] ?: to.uppercase()}:$output"
        return args
    }

    /** Ghostscript: a smaller copy of a PDF (images at 150 dpi, like Acrobat's "ebook"). */
    fun ghostscriptCompress(tool: ToolInfo, input: Path, output: Path): List<String> = listOf(
        tool.path.toString(),
        "-sDEVICE=pdfwrite",
        "-dCompatibilityLevel=1.5",
        "-dPDFSETTINGS=/ebook",
        "-dNOPAUSE",
        "-dBATCH",
        "-dSAFER",
        "-dQUIET",
        "-sOutputFile=${ghostscriptPath(output)}",
        input.toString(),
    )

    /** Ghostscript: EPS → PDF, cropped to the drawing. */
    fun ghostscriptEps(tool: ToolInfo, input: Path, output: Path): List<String> = listOf(
        tool.path.toString(),
        "-sDEVICE=pdfwrite",
        "-dEPSCrop",
        "-dNOPAUSE",
        "-dBATCH",
        "-dSAFER",
        "-dQUIET",
        "-sOutputFile=${ghostscriptPath(output)}",
        input.toString(),
    )

    /** Ghostscript: every page of a PDF as a 300 dpi PNG in [outDir] (`page-0001.png`…). */
    fun ghostscriptPages(tool: ToolInfo, input: Path, outDir: Path): List<String> = listOf(
        tool.path.toString(),
        "-sDEVICE=png16m",
        "-r300",
        "-dNOPAUSE",
        "-dBATCH",
        "-dSAFER",
        "-dQUIET",
        "-sOutputFile=${ghostscriptPath(outDir)}${outDir.fileSystem.separator}page-%04d.png",
        input.toString(),
    )

    /** Tesseract: the text of an image into `[outBase].txt`. */
    fun tesseract(tool: ToolInfo, input: Path, outBase: Path, languages: String): List<String> =
        listOf(tool.path.toString(), input.toString(), outBase.toString(), "-l", languages)

    /** Ghostscript reads `%` in an output name as a page number: a literal one is doubled. */
    private fun ghostscriptPath(path: Path): String = path.toString().replace("%", "%%")
}

/** FFmpeg's codec arguments per target. */
object FfmpegArgs {
    /** The arguments for [to] with these encoders, or null if one it needs is missing. */
    fun codecs(to: String, encoders: Set<String>): List<String>? {
        fun has(name: String) = name in encoders
        val h264 = if (has("libx264")) listOf("-c:v", "libx264", "-preset", "medium", "-crf", "23") else if (has("mpeg4")) listOf("-c:v", "mpeg4", "-q:v", "3") else null
        val evenSize = listOf("-pix_fmt", "yuv420p", "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2")
        val streams = listOf("-map", "0:v:0", "-map", "0:a:0?")
        return when (to) {
            "mp4", "mov" -> {
                if (h264 == null || !has("aac")) null else streams + h264 + evenSize + listOf("-c:a", "aac", "-b:a", "160k", "-movflags", "+faststart")
            }
            "mkv" -> if (h264 == null || !has("aac")) null else streams + h264 + evenSize + listOf("-c:a", "aac", "-b:a", "160k")
            "webm" -> {
                val video = when {
                    has("libvpx-vp9") -> listOf("-c:v", "libvpx-vp9", "-b:v", "0", "-crf", "33", "-row-mt", "1")
                    has("libvpx") -> listOf("-c:v", "libvpx", "-b:v", "1M", "-crf", "10")
                    else -> return null
                }
                val audio = when {
                    has("libopus") -> listOf("-c:a", "libopus", "-b:a", "128k")
                    has("libvorbis") -> listOf("-c:a", "libvorbis", "-q:a", "4")
                    else -> return null
                }
                streams + video + listOf("-pix_fmt", "yuv420p") + audio
            }
            "avi" -> {
                if (!has("mpeg4")) return null
                val audio = when {
                    has("libmp3lame") -> listOf("-c:a", "libmp3lame", "-q:a", "4")
                    has("ac3") -> listOf("-c:a", "ac3", "-b:a", "192k")
                    else -> return null
                }
                streams + listOf("-c:v", "mpeg4", "-q:v", "4") + audio
            }
            "gif" -> if (!has("gif")) {
                null
            } else {
                listOf(
                    "-an",
                    "-vf",
                    "fps=12,scale=min(640\\,iw):-1:flags=lanczos,split[a][b];[a]palettegen[p];[b][p]paletteuse",
                    "-loop",
                    "0",
                )
            }
            else -> audio(to, encoders)?.let { listOf("-vn", "-map", "0:a:0") + it }
        }
    }

    private fun audio(to: String, encoders: Set<String>): List<String>? {
        fun needs(encoder: String, vararg args: String) = if (encoder in encoders) listOf("-c:a", encoder) + args else null
        return when (to) {
            "mp3" -> needs("libmp3lame", "-q:a", "2")
            "m4a" -> needs("aac", "-b:a", "192k", "-movflags", "+faststart")
            "aac" -> needs("aac", "-b:a", "192k", "-f", "adts")
            "wav" -> needs("pcm_s16le")
            "flac" -> needs("flac")
            "ogg" -> needs("libvorbis", "-q:a", "5")
            "opus" -> needs("libopus", "-b:a", "128k")
            "aiff" -> needs("pcm_s16be")
            "wma" -> needs("wmav2", "-b:a", "192k")
            else -> null
        }
    }
}
