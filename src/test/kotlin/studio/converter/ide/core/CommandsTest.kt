package studio.converter.ide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Paths

/** The argument lists of the local recipes, exactly. */
class CommandsTest {
    private val input = Paths.get("/home/me/My Videos/clip; rm -rf ~ $(x).mov")
    private val output = Paths.get("/tmp/work/output.mp4")

    @Test
    fun ffmpegVideoToMp4() {
        val command = Commands.ffmpeg(Fakes.ffmpeg(), input, output, "mp4")
        assertEquals(
            listOf(
                "/usr/bin/ffmpeg", "-hide_banner", "-nostdin", "-loglevel", "error", "-y", "-i", input.toString(),
                "-map", "0:v:0", "-map", "0:a:0?", "-c:v", "libx264", "-preset", "medium", "-crf", "23",
                "-pix_fmt", "yuv420p", "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2",
                "-c:a", "aac", "-b:a", "160k", "-movflags", "+faststart", output.toString(),
            ),
            command,
        )
        // The odd file name is one argument, never split or interpreted.
        assertEquals(1, command.count { it == input.toString() })
    }

    @Test
    fun ffmpegFallsBackToOtherEncoders() {
        val noX264 = FfmpegArgs.codecs("mp4", Fakes.FFMPEG_ENCODERS - "libx264")!!
        assertTrue(noX264.containsAll(listOf("-c:v", "mpeg4")))
        val vp8 = FfmpegArgs.codecs("webm", setOf("libvpx", "libvorbis"))!!
        assertEquals(listOf("-c:v", "libvpx", "-b:v", "1M", "-crf", "10"), vp8.subList(4, 10))
        assertTrue(vp8.containsAll(listOf("-c:a", "libvorbis")))
        assertNull(FfmpegArgs.codecs("webm", setOf("libvpx-vp9")))
        assertNull(FfmpegArgs.codecs("mp3", setOf("aac")))
    }

    @Test
    fun ffmpegAudioAndGif() {
        assertEquals(listOf("-vn", "-map", "0:a:0", "-c:a", "libmp3lame", "-q:a", "2"), FfmpegArgs.codecs("mp3", Fakes.FFMPEG_ENCODERS))
        assertEquals(listOf("-vn", "-map", "0:a:0", "-c:a", "aac", "-b:a", "192k", "-f", "adts"), FfmpegArgs.codecs("aac", Fakes.FFMPEG_ENCODERS))
        val gif = FfmpegArgs.codecs("gif", Fakes.FFMPEG_ENCODERS)!!
        assertEquals("fps=12,scale=min(640\\,iw):-1:flags=lanczos,split[a][b];[a]palettegen[p];[b][p]paletteuse", gif[2])
        val subtitles = Commands.ffmpeg(Fakes.ffmpeg(), Paths.get("/a/b.ass"), Paths.get("/w/output.srt"), "srt")
        assertEquals(listOf("-map", "0:s:0", "/w/output.srt"), subtitles.takeLast(3))
    }

    @Test
    fun libreOfficeUsesItsOwnProfileAndFilters() {
        val profile = Paths.get("/home/me/.cache/JetBrains/IntelliJIdea2026.2/converter-studio/libreoffice-profile")
        val command = Commands.libreOffice(Fakes.libreOffice(), Paths.get("/p/Report Q3.docx"), Paths.get("/w/out"), "pdf", profile)
        assertEquals(
            listOf(
                "/usr/bin/soffice",
                "-env:UserInstallation=file:///home/me/.cache/JetBrains/IntelliJIdea2026.2/converter-studio/libreoffice-profile",
                "--headless", "--invisible", "--norestore", "--nologo", "--nodefault", "--nolockcheck",
                "--convert-to", "pdf", "--outdir", "/w/out", "/p/Report Q3.docx",
            ),
            command,
        )
        assertEquals("txt:Text (encoded):UTF8", Commands.libreOfficeFilter(Paths.get("a.docx"), "txt"))
        assertEquals("csv:Text - txt - csv (StarCalc):44,34,76,1", Commands.libreOfficeFilter(Paths.get("a.xlsx"), "csv"))
        assertEquals("pdf:writer_web_pdf_Export", Commands.libreOfficeFilter(Paths.get("page.HTML"), "pdf"))
        assertEquals("docx", Commands.libreOfficeFilter(Paths.get("a.odt"), "docx"))
    }

    @Test
    fun pandocNamesItsFormats() {
        val command = Commands.pandoc(Fakes.pandoc(), Paths.get("/p/notes.md"), Paths.get("/w/output.html"), "md", "html")
        assertEquals(
            listOf(
                "/usr/bin/pandoc", "--from", "markdown", "--to", "html", "--standalone", "--metadata", "pagetitle=notes",
                "--output", "/w/output.html", "/p/notes.md",
            ),
            command,
        )
        assertEquals(
            listOf("/usr/bin/pandoc", "--from", "docx", "--to", "gfm", "--output", "/w/output.md", "/p/a.docx"),
            Commands.pandoc(Fakes.pandoc(), Paths.get("/p/a.docx"), Paths.get("/w/output.md"), "docx", "md"),
        )
    }

    @Test
    fun imageMagickTakesTheFirstFrameAndNamesTheOutputFormat() {
        val jpg = Commands.imageMagick(Fakes.magick(), Paths.get("/w/input.heic"), Paths.get("/w/output.jpg"), "jpg")
        assertEquals(
            listOf("/usr/bin/magick", "/w/input.heic[0]", "-auto-orient", "-background", "white", "-alpha", "remove", "-alpha", "off", "-quality", "90", "JPEG:/w/output.jpg"),
            jpg,
        )
        val webp = Commands.imageMagick(Fakes.magick(legacy = true), Paths.get("/w/input.gif"), Paths.get("/w/output.webp"), "webp")
        assertEquals(listOf("/usr/bin/convert", "/w/input.gif", "-auto-orient", "-quality", "80", "WEBP:/w/output.webp"), webp)
        val ico = Commands.imageMagick(Fakes.magick(), Paths.get("/w/input.png"), Paths.get("/w/output.ico"), "ico")
        assertTrue(ico.contains("icon:auto-resize=256,128,64,48,32,16"))
    }

    @Test
    fun ghostscriptEscapesPercentSigns() {
        val compress = Commands.ghostscriptCompress(Fakes.ghostscript(), Paths.get("/p/a.pdf"), Paths.get("/tmp/50%/output.pdf"))
        assertEquals("-sOutputFile=/tmp/50%%/output.pdf", compress[8])
        assertEquals("-dPDFSETTINGS=/ebook", compress[3])
        assertEquals("-dSAFER", compress[6])
        val pages = Commands.ghostscriptPages(Fakes.ghostscript(), Paths.get("/p/scan.pdf"), Paths.get("/w/pages"))
        assertEquals("-sOutputFile=/w/pages/page-%04d.png", pages[7])
        assertTrue(Commands.ghostscriptEps(Fakes.ghostscript(), Paths.get("/p/a.eps"), Paths.get("/w/output.pdf")).contains("-dEPSCrop"))
    }

    @Test
    fun tesseractReadsIntoABaseName() {
        assertEquals(
            listOf("/usr/bin/tesseract", "/p/scan.png", "/w/output", "-l", "eng+deu"),
            Commands.tesseract(Fakes.tesseract(), Paths.get("/p/scan.png"), Paths.get("/w/output"), "eng+deu"),
        )
    }
}
