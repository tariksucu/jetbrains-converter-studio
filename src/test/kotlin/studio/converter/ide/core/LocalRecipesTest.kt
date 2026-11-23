package studio.converter.ide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which program (or the plugin's own code) converts what on this computer. */
class LocalRecipesTest {
    private fun engine(from: String, to: String, tools: ToolSet = Fakes.all(), options: LocalOptions = LocalOptions()) =
        LocalRecipes.find(from, to, tools, options)?.engine

    @Test
    fun imagesPreferImageMagickAndFallBackToJava() {
        assertEquals(Engine.IMAGEMAGICK, engine("png", "jpg"))
        assertEquals(Engine.BUILT_IN_IMAGE, engine("png", "jpg", ToolSet.NONE))
        assertEquals(Engine.BUILT_IN_IMAGE, engine("tiff", "gif", Fakes.tools(Fakes.ffmpeg())))
        // Java has no WebP or HEIC: without ImageMagick these are not done here.
        assertNull(engine("png", "webp", ToolSet.NONE))
        assertNull(engine("heic", "jpg", ToolSet.NONE))
    }

    @Test
    fun heicNeedsAnImageMagickBuiltWithIt() {
        assertEquals(Engine.IMAGEMAGICK, engine("heic", "jpg"))
        val withoutHeic = Fakes.tools(Fakes.magick(read = setOf("JPEG", "PNG")))
        assertNull(engine("heic", "jpg", withoutHeic))
        assertNull(engine("png", "avif", Fakes.tools(Fakes.magick(write = setOf("JPEG", "PNG")))))
        assertEquals(Engine.IMAGEMAGICK, engine("svg", "png"))
        assertEquals(Engine.IMAGEMAGICK, engine("jpg", "pdf"))
    }

    @Test
    fun videoAndAudioNeedFfmpegWithTheRightEncoders() {
        assertEquals(Engine.FFMPEG, engine("mov", "mp4"))
        assertEquals(Engine.FFMPEG, engine("mp4", "mp3"))
        assertEquals(Engine.FFMPEG, engine("gif", "mp4"))
        assertEquals(Engine.FFMPEG, engine("mkv", "gif"))
        assertNull(engine("mp4", "mp4"))
        // Audio does not become video.
        assertNull(engine("mp3", "mp4"))
        assertNull(engine("mov", "mp4", ToolSet.NONE))
        val noLame = Fakes.tools(Fakes.ffmpeg(Fakes.FFMPEG_ENCODERS - "libmp3lame"))
        assertNull(engine("wav", "mp3", noLame))
        assertEquals(Engine.FFMPEG, engine("wav", "flac", noLame))
        val noVpx = Fakes.tools(Fakes.ffmpeg(Fakes.FFMPEG_ENCODERS - "libvpx-vp9" - "libvpx"))
        assertNull(engine("mp4", "webm", noVpx))
    }

    @Test
    fun documentsGoToLibreOfficeAndMarkdownToPandoc() {
        assertEquals(Engine.LIBREOFFICE, engine("docx", "pdf"))
        assertEquals(Engine.LIBREOFFICE, engine("xlsx", "csv"))
        assertEquals(Engine.LIBREOFFICE, engine("pptx", "pdf"))
        assertEquals(Engine.PANDOC, engine("md", "docx"))
        assertEquals(Engine.PANDOC, engine("docx", "md"))
        assertEquals(Engine.PANDOC, engine("md", "html"))
        assertEquals(Engine.PANDOC_LIBREOFFICE, engine("md", "pdf"))
        assertEquals(Engine.PANDOC_LIBREOFFICE, engine("epub", "pdf"))
        // Without LibreOffice, Pandoc still does what it can.
        val pandocOnly = Fakes.tools(Fakes.pandoc())
        assertEquals(Engine.PANDOC, engine("docx", "odt", pandocOnly))
        assertNull(engine("md", "pdf", pandocOnly))
        assertNull(engine("xlsx", "pdf", pandocOnly))
        // An older Pandoc without the RTF reader.
        assertNull(engine("rtf", "md", Fakes.tools(Fakes.pandoc(read = setOf("markdown", "docx")))))
    }

    @Test
    fun pdfCompressionAndTextRecognition() {
        assertEquals(Engine.GHOSTSCRIPT, engine("pdf", "pdf"))
        assertTrue(LocalRecipes.find("pdf", "pdf", Fakes.all())!!.compress)
        assertEquals(Engine.GHOSTSCRIPT, engine("eps", "pdf"))
        assertEquals(Engine.TESSERACT, engine("png", "txt"))
        assertEquals(Engine.GHOSTSCRIPT_TESSERACT, engine("pdf", "txt"))
        assertNull(engine("pdf", "txt", Fakes.tools(Fakes.tesseract())))
        // A language that is not installed, and a setting that is not a language list.
        assertNull(engine("png", "txt", options = LocalOptions("fra")))
        assertEquals(Engine.TESSERACT, engine("png", "txt", options = LocalOptions("eng+deu")))
        assertNull(engine("png", "txt", options = LocalOptions("eng; rm -rf")))
    }

    @Test
    fun dataAndSubtitlesAreBuiltIn() {
        assertEquals(Engine.BUILT_IN_DATA, engine("json", "csv", ToolSet.NONE))
        assertEquals(Engine.BUILT_IN_DATA, engine("csv", "json", ToolSet.NONE))
        assertEquals(Engine.BUILT_IN_DATA, engine("json", "yaml", ToolSet.NONE))
        assertNull(engine("yaml", "json", ToolSet.NONE))
        assertEquals(Engine.BUILT_IN_SUBTITLE, engine("srt", "vtt", ToolSet.NONE))
        assertEquals(Engine.FFMPEG, engine("ass", "srt"))
        assertNull(engine("ass", "srt", ToolSet.NONE))
        // CSV to a spreadsheet goes to LibreOffice.
        assertEquals(Engine.LIBREOFFICE, engine("csv", "xlsx"))
    }

    @Test
    fun targetsFollowTheListOrder() {
        val targets = LocalRecipes.targets("png", Fakes.all()).map { it.to }
        assertEquals(listOf("jpg", "webp", "avif", "pdf", "txt", "gif", "tiff", "bmp", "ico"), targets)
        assertEquals(listOf("jpg", "gif", "tiff", "bmp"), LocalRecipes.targets("png", ToolSet.NONE).map { it.to })
        assertEquals("Pandoc + LibreOffice", LocalRecipes.find("md", "pdf", Fakes.all())!!.label)
        assertEquals("built in", LocalRecipes.find("json", "csv", ToolSet.NONE)!!.label)
    }

    @Test
    fun ocrLanguageSetting() {
        assertEquals(listOf("eng"), OcrLanguages.parse(""))
        assertEquals(listOf("eng", "deu"), OcrLanguages.parse(" eng+deu "))
        assertEquals(listOf("chi_sim"), OcrLanguages.parse("chi_sim"))
        assertNull(OcrLanguages.parse("eng deu"))
        assertNull(OcrLanguages.parse("../eng"))
    }
}
