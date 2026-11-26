package studio.converter.ide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO

/** The plugin's own conversions: JSON, CSV, YAML, subtitles and images. */
class BuiltInTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun jsonParsesAndWrites() {
        val value = Json.parse("""{"a":[1,2.5,-3e2,true,null],"b":{"c":"x\"yé\n"},"d":""}""")
        assertEquals(mapOf("a" to listOf(1L, 2.5, -300.0, true, null), "b" to mapOf("c" to "x\"yé\n"), "d" to ""), value)
        assertEquals("""{"a":[1,2.5,-300,true,null],"b":{"c":"x\"yé\n"},"d":""}""", Json.write(value))
        assertThrows(JsonException::class.java) { Json.parse("{\"a\":1,}") }
        assertThrows(JsonException::class.java) { Json.parse("[1] 2") }
        assertThrows(JsonException::class.java) { Json.parse("01") }
        assertEquals(listOf<Any?>(), Json.parse("﻿ [ ] "))
    }

    @Test
    fun jsonToCsvFlattensObjects() {
        val rows = DataConversions.table(Json.parse("""[{"id":1,"name":"Ada, Countess","tags":["x","y"],"address":{"city":"London"}},{"id":2,"extra":true}]"""))
        assertEquals(listOf("id", "name", "tags", "address.city", "extra"), rows[0])
        assertEquals(listOf("1", "Ada, Countess", """["x","y"]""", "London", ""), rows[1])
        assertEquals(listOf("2", "", "", "", "true"), rows[2])
        assertEquals(
            "id,name,tags,address.city,extra\r\n1,\"Ada, Countess\",\"[\"\"x\"\",\"\"y\"\"]\",London,\r\n2,,,,true\r\n",
            Csv.write(rows),
        )
        assertThrows(ConversionException::class.java) { DataConversions.table(Json.parse("[1,2]")) }
    }

    @Test
    fun csvReadsQuotesAndLineBreaks() {
        val rows = Csv.parse("﻿name,note\r\n\"Doe, J\",\"said \"\"hi\"\"\nthen left\"\r\n\r\nlast,\n")
        assertEquals(listOf(listOf("name", "note"), listOf("Doe, J", "said \"hi\"\nthen left"), listOf("last", "")), rows)
        assertEquals(
            listOf(mapOf("name" to "Doe, J", "note" to "said \"hi\"\nthen left"), mapOf("name" to "last", "note" to "")),
            Csv.toObjects(rows),
        )
        assertThrows(ConversionException::class.java) { Csv.parse("a,\"b\n") }
    }

    @Test
    fun yamlQuotesWhatWouldBeMisread() {
        val yaml = Yaml.write(Json.parse("""{"name":"Ada","on":"yes","n":"42","list":[1,{"k":"a: b"}],"empty":[],"nothing":null}"""))
        assertEquals(
            "name: Ada\n\"on\": \"yes\"\n\"n\": \"42\"\nlist:\n  - 1\n  - k: \"a: b\"\nempty: []\nnothing: null\n",
            yaml,
        )
    }

    /** The cases every editor plugin runs (the same file in the VS Code, Sublime and Neovim ones). */
    @Test
    fun sharedDataCases() {
        val text = javaClass.getResourceAsStream("/data-cases.json")!!.use { String(it.readAllBytes(), Charsets.UTF_8) }
        val cases = Json.parse(text).asObject()!!["cases"].asArray()!!.map { it.asObject()!! }
        var run = 0
        for (case in cases) {
            val from = case.string("from")!!
            val to = case.string("to")!!
            if (!DataConversions.supports(from, to)) continue
            run++
            val name = "$from to $to: ${case.string("name")}"
            val input = case.string("input")!!
            if (case["error"] == true) {
                assertThrows(name, ConversionException::class.java) { DataConversions.convertText(input, from, to) }
            } else {
                assertEquals(name, case.string("output"), DataConversions.convertText(input, from, to))
            }
        }
        assertEquals("every case of the four pairs", cases.size, run)
    }

    @Test
    fun numbersKeepTheirText() {
        val value = Json.parse("[9007199254740993, -0.10e+2, 12345678901234567890]", keepNumberText = true)
        assertEquals(listOf(JsonNumber("9007199254740993"), JsonNumber("-0.10e+2"), JsonNumber("12345678901234567890")), value)
        assertEquals("[9007199254740993,-0.10e+2,12345678901234567890]", Json.write(value))
        // Read for the API, numbers stay Long and Double.
        assertEquals(listOf(9007199254740993L, -10.0), Json.parse("[9007199254740993, -0.10e+2]"))
    }

    @Test
    fun columnNamesAreAllDifferent() {
        assertEquals(listOf("column2", "column2_2"), Csv.columnNames(listOf("column2"), 2))
        assertEquals(listOf("id", "id_3", "id_2"), Csv.columnNames(listOf("id", "id", "id_2"), 3))
        assertEquals(listOf("column1_2", "column1"), Csv.columnNames(listOf("", "column1"), 2))
        assertEquals(';', Csv.separator("\r\na;b\n"))
        assertEquals('|', Csv.separator("a|b,c|d\n"))
        // A key "a.b" and a key "b" inside "a" are two columns.
        val rows = DataConversions.table(Json.parse("[{\"a.b\":1,\"a\":{\"b\":2}},{\"a\":{\"b\":3}}]", keepNumberText = true))
        assertEquals(listOf(listOf("a.b", "a.b_2"), listOf("1", "2"), listOf("", "3")), rows)
    }

    @Test
    fun dataThatIsNotUtf8IsRefused() {
        val csv = temp.newFile("latin1.csv").toPath()
        Files.write(csv, byteArrayOf(0x63, 0x69, 0x74, 0x79, 0x0a, 0x4d, 0xfc.toByte(), 0x6e, 0x63, 0x68, 0x0a))
        val error = assertThrows(ConversionException::class.java) {
            DataConversions.convert(csv, temp.root.toPath().resolve("out.json"), "csv", "json")
        }
        assertTrue(error.message!!.contains("not UTF-8"))
    }

    @Test
    fun dataFilesConvert() {
        val json = temp.newFile("people.json").toPath()
        Files.writeString(json, """[{"name":"Ada"},{"name":"Alan"}]""")
        val csv = temp.root.toPath().resolve("people.csv")
        DataConversions.convert(json, csv, "json", "csv")
        assertEquals("name\r\nAda\r\nAlan\r\n", Files.readString(csv))
        val back = temp.root.toPath().resolve("back.json")
        DataConversions.convert(csv, back, "csv", "json")
        assertEquals(listOf(mapOf("name" to "Ada"), mapOf("name" to "Alan")), Json.parse(Files.readString(back)))
    }

    @Test
    fun subtitlesBetweenSrtAndVtt() {
        val srt = "1\n00:00:01,000 --> 00:00:02,500\nHello <i>there</i>\n\n2\n00:00:03,000 --> 00:00:04,000\nTwo\nlines\n"
        val vtt = SubtitleConversions.srtToVtt(srt)
        assertEquals("WEBVTT\n\n00:00:01.000 --> 00:00:02.500\nHello <i>there</i>\n\n00:00:03.000 --> 00:00:04.000\nTwo\nlines\n\n", vtt)
        val fromVtt = SubtitleConversions.vttToSrt("WEBVTT\n\nNOTE a note\n\nintro\n01:02.000 --> 01:03.000 align:start\n<v Roger>Hi</v>\n")
        assertEquals("1\n00:01:02,000 --> 00:01:03,000\nHi\n\n", fromVtt)
    }

    @Test
    fun imagesWithJava() {
        val image = BufferedImage(40, 30, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.color = Color(255, 84, 24)
        g.fillRect(10, 10, 20, 10)
        g.dispose()
        val png = temp.newFile("logo.png").toPath()
        ImageIO.write(image, "png", png.toFile())
        for (to in listOf("jpg", "bmp", "gif", "tiff")) {
            val out = temp.root.toPath().resolve("out.$to")
            ImageConversions.convert(png, out, to)
            val read = ImageIO.read(out.toFile())
            assertNotNull(to, read)
            assertEquals(40, read.width)
        }
        // Transparent pixels become white in a JPG.
        val jpg = ImageIO.read(temp.root.toPath().resolve("out.jpg").toFile())
        assertTrue(Color(jpg.getRGB(0, 0)).red > 240)
        val notImage = temp.newFile("fake.png").toPath()
        Files.writeString(notImage, "not an image")
        assertThrows(ConversionException::class.java) { ImageConversions.convert(notImage, temp.root.toPath().resolve("x.jpg"), "jpg") }
    }
}
