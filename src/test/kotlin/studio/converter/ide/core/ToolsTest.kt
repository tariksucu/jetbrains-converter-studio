package studio.converter.ide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path
import java.nio.file.Paths

/** Finding the programs (PATH first, then the usual install folders) and reading their listings. */
class ToolsTest {
    /** A file system with these files only. */
    private class FakeFiles(vararg files: String) : FileProbe {
        val set = files.map { Paths.get(it) }.toSet()

        override fun isFile(path: Path) = path in set

        override fun list(dir: Path): List<Path> = set.mapNotNull { file ->
            generateSequence(file) { it.parent }.firstOrNull { it.parent == dir }
        }.distinct()
    }

    @Test
    fun pathComesFirstThenUsualFolders() {
        val files = FakeFiles("/opt/tools/ffmpeg", "/usr/bin/ffmpeg")
        val locator = ToolLocator(Os.LINUX, "/opt/tools:/usr/bin", { null }, "/home/me", files)
        assertEquals(Paths.get("/opt/tools/ffmpeg"), locator.find(ToolId.FFMPEG))
        val noPath = ToolLocator(Os.LINUX, "", { null }, "/home/me", files)
        assertEquals(Paths.get("/usr/bin/ffmpeg"), noPath.find(ToolId.FFMPEG))
        assertNull(noPath.find(ToolId.PANDOC))
    }

    @Test
    fun macHomebrewAndLibreOfficeApp() {
        val files = FakeFiles("/opt/homebrew/bin/magick", "/Applications/LibreOffice.app/Contents/MacOS/soffice")
        // A Dock-started IDE has a short PATH.
        val locator = ToolLocator(Os.MAC, "/usr/bin:/bin", { null }, "/Users/me", files)
        assertEquals(Paths.get("/opt/homebrew/bin/magick"), locator.find(ToolId.IMAGEMAGICK))
        assertEquals(Paths.get("/Applications/LibreOffice.app/Contents/MacOS/soffice"), locator.find(ToolId.LIBREOFFICE))
    }

    @Test
    fun linuxImageMagickSixAndOptLibreOffice() {
        val files = FakeFiles("/usr/bin/convert", "/opt/libreoffice24.8/program/soffice", "/opt/libreoffice7.6/program/soffice")
        val locator = ToolLocator(Os.LINUX, "/usr/local/bin", { null }, "/home/me", files)
        assertEquals(Paths.get("/usr/bin/convert"), locator.find(ToolId.IMAGEMAGICK))
        // The newest version first.
        assertEquals(Paths.get("/opt/libreoffice24.8/program/soffice"), locator.find(ToolId.LIBREOFFICE))
    }

    @Test
    fun windowsNeverTakesTheSystemConvertExe() {
        val env = mapOf("ProgramFiles" to "C:\\Program Files", "LOCALAPPDATA" to "C:\\Users\\me\\AppData\\Local")
        val locator = ToolLocator(Os.WINDOWS, "C:\\Windows\\System32", env::get, "C:\\Users\\me", FakeFiles())
        assertEquals(listOf("magick.exe"), locator.names(ToolId.IMAGEMAGICK))
        assertEquals(listOf("gswin64c.exe", "gswin32c.exe"), locator.names(ToolId.GHOSTSCRIPT))
        assertFalse(locator.candidates(ToolId.IMAGEMAGICK).any { it.fileName.toString().equals("convert.exe", true) })
    }

    @Test
    fun naturalOrderSortsVersions() {
        val names = listOf("gs9.56.1", "gs10.02.1", "gs10.4.0")
        assertEquals(listOf("gs10.4.0", "gs10.02.1", "gs9.56.1").first(), names.sortedWith(NaturalOrder.reversed()).first())
        assertTrue(NaturalOrder.compare("ImageMagick-7.1.1-Q16", "ImageMagick-7.0.10-Q16") > 0)
        assertTrue(NaturalOrder.compare("libreoffice7.6", "libreoffice24.8") < 0)
    }

    @Test
    fun readsFfmpegEncoders() {
        val output = """
            Encoders:
             V..... = Video
             A..... = Audio
             ------
             V....D libx264              libx264 H.264 / AVC / MPEG-4 AVC (codec h264)
             V.S... mpeg4                MPEG-4 part 2
             A....D aac                  AAC (Advanced Audio Coding)
             A....D libmp3lame           libmp3lame MP3 (MPEG audio layer 3) (codec mp3)
             S..... webvtt               WebVTT subtitle
        """.trimIndent()
        assertEquals(setOf("libx264", "mpeg4", "aac", "libmp3lame", "webvtt"), ToolOutput.ffmpegEncoders(output))
    }

    @Test
    fun readsImageMagickFormats() {
        val output = """
               Format  Module    Mode  Description
            -------------------------------------------------------------------------------
                  3FR  DNG       r--   Hasselblad CFV/H3D39II Raw Format
                 AVIF  HEIC      rw+   AV1 Image File Format (1.17.6)
                 HEIC  HEIC      rw+   High Efficiency Image Format (1.17.6)
                 JPEG* JPEG      rw-   Joint Photographic Experts Group JFIF format (80)
                  PDF* PDF       rw+   Portable Document Format
                  SVG  SVG       rw+   Scalable Vector Graphics (RSVG 2.58.0)
                 WEBP* WEBP      rw+   WebP Image Format (libwebp 1.3.2 [020F])
        """.trimIndent()
        val (read, write) = ToolOutput.magickFormats(output)
        assertTrue(read.containsAll(listOf("3FR", "AVIF", "HEIC", "JPEG", "PDF", "SVG", "WEBP")))
        assertFalse("3FR" in write)
        assertTrue(write.containsAll(listOf("AVIF", "HEIC", "JPEG", "WEBP")))
        assertFalse("Format" in read)
    }

    @Test
    fun readsTesseractLanguagesAndPandocFormats() {
        val langs = "List of available languages in \"/usr/share/tesseract-ocr/5/tessdata/\" (3):\neng\nosd\nchi_sim\n"
        assertEquals(setOf("eng", "osd", "chi_sim"), ToolOutput.tesseractLanguages(langs))
        assertEquals(setOf("markdown", "gfm", "docx"), ToolOutput.words("markdown\ngfm\ndocx\n"))
    }
}
