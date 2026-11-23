package studio.converter.ide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import javax.imageio.ImageIO

/**
 * Real conversions with the programs installed on this computer (each test is skipped when its
 * program is missing). The inputs are made here, so the test needs no sample files.
 */
class RealToolsTest {
    @get:Rule
    val temp = TemporaryFolder()

    companion object {
        private lateinit var tools: ToolSet

        @JvmStatic
        @BeforeClass
        fun detect() {
            val locator = ToolLocator(Os.current(), System.getenv("PATH"), System::getenv, System.getProperty("user.home"))
            tools = ToolDetector(locator).detect()
            println("Tools found: " + tools.tools.values.joinToString { "${it.label} (${it.path})" })
        }
    }

    private fun need(vararg ids: ToolId) = assumeTrue("needs ${ids.joinToString()}", ids.all { tools.has(it) })

    private fun convert(source: Path, to: String): Path {
        val from = Catalogue.BUILT_IN.formatOfName(source.fileName.toString())!!
        val recipe = LocalRecipes.find(from, to, tools) ?: error("no local recipe $from → $to")
        val work = temp.newFolder().toPath()
        val profile = temp.root.toPath().resolve("lo-profile")
        val result = LocalConverter(tools, libreOfficeProfile = profile).convert(source, recipe, work, CancelCheck.NEVER)
        return Naming.moveIntoPlace(result, source.parent, Naming.localResultName(source.fileName.toString(), to, recipe.compress))
    }

    private fun run(vararg command: String) {
        val result = ProcessRunner().run(command.toList(), Duration.ofMinutes(2))
        assertEquals(result.output, 0, result.exitCode)
    }

    @Test
    fun ffmpegVideoToWebmMp3AndGif() {
        need(ToolId.FFMPEG)
        val ffmpeg = tools[ToolId.FFMPEG]!!
        val video = temp.root.toPath().resolve("clip one.mp4")
        run(
            ffmpeg.path.toString(), "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=duration=1:size=161x121:rate=10",
            "-f", "lavfi", "-i", "sine=duration=1", "-shortest", "-c:v", "mpeg4", "-c:a", "aac", video.toString(),
        )
        for (to in listOf("webm", "mp3", "gif", "mov")) {
            if (LocalRecipes.find("mp4", to, tools) == null) continue
            val out = convert(video, to)
            assertEquals("clip one.$to", out.fileName.toString())
            assertTrue(Files.size(out) > 100)
        }
        // A second conversion to the same name gets a number.
        if (LocalRecipes.find("mp4", "mp3", tools) != null) assertEquals("clip one (2).mp3", convert(video, "mp3").fileName.toString())
    }

    @Test
    fun markdownToDocxAndPdf() {
        need(ToolId.PANDOC, ToolId.LIBREOFFICE)
        val md = temp.root.toPath().resolve("notes.md")
        Files.writeString(md, "# Converter Studio\n\nA *local* test with a table:\n\n| a | b |\n|---|---|\n| 1 | 2 |\n")
        val docx = convert(md, "docx")
        assertTrue(Files.size(docx) > 1000)
        val pdf = convert(md, "pdf")
        assertTrue(String(Files.readAllBytes(pdf).copyOf(5)).startsWith("%PDF-"))
        val html = convert(docx, "html")
        assertTrue(Files.readString(html).contains("Converter Studio"))
    }

    @Test
    fun officeToPdfAndPdfCompression() {
        need(ToolId.LIBREOFFICE)
        val html = temp.root.toPath().resolve("page.html")
        Files.writeString(html, "<html><body><h1>Hello PDF</h1><p>" + "Some text. ".repeat(200) + "</p></body></html>")
        val pdf = convert(html, "pdf")
        assertTrue(String(Files.readAllBytes(pdf).copyOf(5)).startsWith("%PDF-"))
        val csv = temp.root.toPath().resolve("table.csv")
        Files.writeString(csv, "name,value\nAda,1\nAlan,2\n")
        assertTrue(Files.size(convert(csv, "xlsx")) > 1000)
        if (tools.has(ToolId.GHOSTSCRIPT)) {
            // Ghostscript either makes it smaller or says so; it never saves a bigger copy.
            try {
                val smaller = convert(pdf, "pdf")
                assertEquals("page-compressed.pdf", smaller.fileName.toString())
                assertTrue(Files.size(smaller) < Files.size(pdf))
            } catch (e: ConversionException) {
                assertEquals("notSmaller", e.code)
            }
        }
    }

    @Test
    fun textRecognitionOfAnImageAndAPdf() {
        need(ToolId.TESSERACT)
        val png = temp.root.toPath().resolve("scan.png")
        val image = BufferedImage(900, 200, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, 900, 200)
        g.color = Color.BLACK
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 64)
        g.drawString("HELLO WORLD 2026", 30, 120)
        g.dispose()
        ImageIO.write(image, "png", png.toFile())
        assumeTrue("needs Tesseract's English", LocalRecipes.find("png", "txt", tools) != null)
        assertTrue(Files.readString(convert(png, "txt")).uppercase().contains("HELLO WORLD"))
        if (tools.has(ToolId.GHOSTSCRIPT) && tools.has(ToolId.LIBREOFFICE)) {
            // A PDF that holds only the picture of the text: LibreOffice makes it from HTML.
            val html = temp.root.toPath().resolve("picture.html")
            Files.writeString(html, "<html><body><img src=\"scan.png\" width=\"600\"></body></html>")
            val pdf = convert(html, "pdf")
            assertTrue(Files.readString(convert(pdf, "txt")).uppercase().contains("HELLO"))
        }
    }
}
