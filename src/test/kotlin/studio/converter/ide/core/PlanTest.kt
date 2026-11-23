package studio.converter.ide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Paths

/** Format detection, the dialog's targets and routes, names of results, and the desktop hand-over link. */
class PlanTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val api = Catalogue.fromApi(
        """{"formats":[
            {"id":"heic","name":"HEIC","category":"image","extensions":["heic","heif"],"to":["jpg","png","webp","pdf"]},
            {"id":"png","name":"PNG","category":"image","extensions":["png"],"to":["jpg","pdf","webp","avif"]},
            {"id":"tgz","name":"TAR.GZ","category":"archive","extensions":["tar.gz","tgz"],"to":["zip"]},
            {"id":"webp","name":"WebP","category":"image","extensions":["webp"],"to":["png"]}
        ]}""",
    )

    @Test
    fun formatsByExtension() {
        assertEquals("tgz", api.formatOfName("backup.TAR.GZ"))
        assertEquals("heic", api.formatOfName("IMG_0001.HEIF"))
        assertNull(api.formatOfName(".png"))
        assertNull(api.formatOfName("README"))
        assertEquals("jpg", Catalogue.BUILT_IN.formatOfName("photo.jpeg"))
        assertEquals(emptyList<String>(), Catalogue.BUILT_IN.cloudTargets("png"))
        assertEquals(listOf("jpg", "pdf", "webp", "avif"), api.cloudTargets("png"))
    }

    @Test
    fun localTargetsComeFirstAndCloudOnlyOnesAfter() {
        // Java only: PNG → JPG here; HEIC needs the cloud.
        val plan = Planner.plan(listOf("a.png", "b.heic"), api, ToolSet.NONE, LocalOptions(), appEnabled = false)
        assertEquals(listOf("png", "heic"), plan.formats)
        val jpg = plan.targets.first()
        assertEquals("jpg", jpg.to)
        assertEquals(setOf(0), jpg.local.keys)
        assertEquals(listOf(0, 1), jpg.cloud)
        assertEquals(Route.LOCAL, Planner.defaultRoute(jpg, cloudReady = true))
        val webp = plan.targets.first { it.to == "webp" }
        assertTrue(webp.local.isEmpty())
        assertEquals(Route.CLOUD, Planner.defaultRoute(webp, cloudReady = true))
        assertNull(Planner.defaultRoute(webp, cloudReady = false))
        assertTrue(webp.app.isEmpty())
    }

    @Test
    fun theAppTakesWhatTheCatalogueOffers() {
        val plan = Planner.plan(listOf("b.heic"), api, ToolSet.NONE, LocalOptions(), appEnabled = true)
        val jpg = plan.targets.first { it.to == "jpg" }
        assertEquals(listOf(0), jpg.app)
        assertEquals(Route.APP, Planner.defaultRoute(jpg, cloudReady = true))
        // Without the catalogue, the app is offered for what this computer can do.
        val offline = Planner.plan(listOf("a.png"), Catalogue.BUILT_IN, ToolSet.NONE, LocalOptions(), appEnabled = true)
        assertEquals(listOf(0), offline.targets.first { it.to == "jpg" }.app)
        assertFalse(offline.targets.any { it.to == "webp" })
    }

    @Test
    fun unknownFilesHaveNoTargets() {
        val plan = Planner.plan(listOf("Makefile"), api, Fakes.all(), LocalOptions(), appEnabled = true)
        assertEquals(listOf<String?>(null), plan.formats)
        assertTrue(plan.targets.isEmpty())
    }

    @Test
    fun resultNamesNeverOverwrite() {
        assertEquals("photo.jpg", Naming.localResultName("photo.heic", "jpg", sameFormat = false))
        assertEquals("report-compressed.pdf", Naming.localResultName("report.pdf", "pdf", sameFormat = true))
        assertEquals("backup.tar.gz", Naming.localResultName("backup.zip", "tgz", sameFormat = false))
        assertEquals("photo (2).jpg", Naming.numbered("photo.jpg", 2))
        assertEquals("site (2).tar.gz", Naming.numbered("site.tar.gz", 2))
        assertEquals("scan.zip", Naming.cloudResultName("scan.pdf", "scan.zip", "png"))
        assertEquals("evil.jpg", Naming.cloudResultName("x.heic", "../../evil.jpg", "jpg"))
        assertEquals("x.jpg", Naming.cloudResultName("x.heic", "..", "jpg"))

        val folder = temp.newFolder("out").toPath()
        Files.writeString(folder.resolve("photo.jpg"), "first")
        Files.writeString(folder.resolve("photo (2).jpg"), "second")
        val result = temp.newFile("result.bin").toPath()
        Files.writeString(result, "third")
        val saved = Naming.moveIntoPlace(result, folder, "photo.jpg")
        assertEquals("photo (3).jpg", saved.fileName.toString())
        assertEquals("first", Files.readString(folder.resolve("photo.jpg")))
        assertEquals("third", Files.readString(saved))
    }

    @Test
    fun desktopLinkEncodesEveryValue() {
        val file = Paths.get("/home/me/a b+c#d&e%f é.heic")
        assertEquals(
            "converter-studio://convert?to=jpg&from=jetbrains&file=%2Fhome%2Fme%2Fa%20b%2Bc%23d%26e%25f%20%C3%A9.heic",
            DesktopApp.link("jpg", listOf(file)),
        )
        assertEquals("0.1.11", DesktopApp.MIN_VERSION)
    }

    @Test
    fun longHandOversAreSplit() {
        val many = (1..300).map { Paths.get("/p/f$it.png") }
        val links = DesktopApp.links("webp", many)
        assertTrue(links.size > 1)
        assertTrue(links.all { it.length <= DesktopApp.MAX_LINK_LENGTH })
        assertEquals(300, links.sumOf { link -> link.split("&file=").size - 1 })
        val short = (1..250).map { Paths.get("/$it") }
        assertTrue(DesktopApp.links("webp", short).all { it.split("&file=").size - 1 <= DesktopApp.MAX_FILES_PER_LINK })
    }

    @Test
    fun handOverCommandsAndPaths() {
        val link = "converter-studio://convert?to=pdf&from=jetbrains&file=%2Fa"
        assertEquals(listOf("rundll32.exe", "url.dll,FileProtocolHandler", link), DesktopApp.openCommand(Os.WINDOWS, link))
        assertEquals(listOf("open", link), DesktopApp.openCommand(Os.MAC, link))
        assertEquals(listOf("xdg-open", link), DesktopApp.openCommand(Os.LINUX, link))
        assertTrue(DesktopApp.canHandOver(Paths.get("/home/me/a.png")))
        assertTrue(DesktopApp.canHandOver("C:\\Users\\me\\a.png"))
        assertFalse(DesktopApp.canHandOver("\\\\wsl$\\Ubuntu\\home\\me\\a.png"))
        assertFalse(DesktopApp.canHandOver("\\\\wsl.localhost\\Ubuntu\\home\\me\\a.png"))
    }
}
