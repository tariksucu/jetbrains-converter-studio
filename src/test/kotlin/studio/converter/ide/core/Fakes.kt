package studio.converter.ide.core

import java.nio.file.Path
import java.nio.file.Paths

/** Tool sets for the recipe tests: what a computer has, without running anything. */
object Fakes {
    val FFMPEG_ENCODERS = setOf(
        "libx264", "mpeg4", "aac", "libvpx-vp9", "libvpx", "libopus", "libvorbis", "libmp3lame", "flac",
        "pcm_s16le", "pcm_s16be", "wmav2", "gif", "srt", "webvtt", "ass", "ac3",
    )

    fun ffmpeg(encoders: Set<String> = FFMPEG_ENCODERS) = ToolInfo(ToolId.FFMPEG, p("/usr/bin/ffmpeg"), "6.1.1", encoders = encoders)

    fun libreOffice() = ToolInfo(ToolId.LIBREOFFICE, p("/usr/bin/soffice"), "24.2.7.2")

    fun pandoc(
        read: Set<String> = setOf("markdown", "html", "docx", "odt", "epub", "rtf"),
        write: Set<String> = setOf("gfm", "html", "docx", "odt", "epub", "rtf", "plain"),
    ) = ToolInfo(ToolId.PANDOC, p("/usr/bin/pandoc"), "3.1.3", pandocIn = read, pandocOut = write)

    fun magick(
        read: Set<String> = setOf("JPEG", "PNG", "GIF", "BMP", "TIFF", "WEBP", "AVIF", "HEIC", "ICO", "SVG", "DNG", "CR2", "NEF", "ARW"),
        write: Set<String> = setOf("JPEG", "PNG", "GIF", "BMP", "TIFF", "WEBP", "AVIF", "ICO", "PDF"),
        legacy: Boolean = false,
    ) = ToolInfo(ToolId.IMAGEMAGICK, p(if (legacy) "/usr/bin/convert" else "/usr/bin/magick"), "7.1.1-43", magickLegacy = legacy, imageRead = read, imageWrite = write)

    fun ghostscript() = ToolInfo(ToolId.GHOSTSCRIPT, p("/usr/bin/gs"), "10.02.1")

    fun tesseract(languages: Set<String> = setOf("eng", "deu")) = ToolInfo(ToolId.TESSERACT, p("/usr/bin/tesseract"), "5.3.4", languages = languages)

    fun all() = tools(ffmpeg(), libreOffice(), pandoc(), magick(), ghostscript(), tesseract())

    fun tools(vararg infos: ToolInfo) = ToolSet(infos.associateBy { it.id })

    fun p(path: String): Path = Paths.get(path)
}
