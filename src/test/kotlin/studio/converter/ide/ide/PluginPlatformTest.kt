package studio.converter.ide.ide

import com.intellij.notification.NotificationGroupManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import studio.converter.ide.actions.ConvertAction
import studio.converter.ide.actions.Prepared
import studio.converter.ide.core.CancelCheck
import studio.converter.ide.core.Catalogue
import studio.converter.ide.core.LocalOptions
import studio.converter.ide.core.LocalRecipes
import studio.converter.ide.core.Route
import studio.converter.ide.core.ToolSet
import studio.converter.ide.run.Conversions
import studio.converter.ide.run.FileOutcome
import studio.converter.ide.settings.ApiKeyStore
import studio.converter.ide.settings.ConverterStudioConfigurable
import studio.converter.ide.settings.ConverterStudioSettings
import studio.converter.ide.ui.ConvertDialog
import com.intellij.openapi.util.Disposer
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO

/** The plugin inside the IntelliJ Platform: its action, settings, key storage and a local conversion. */
class PluginPlatformTest : BasePlatformTestCase() {
    private val tempDirs = ArrayList<java.nio.file.Path>()

    override fun tearDown() {
        try {
            tempDirs.forEach { Conversions.deleteTree(it) }
            ApiKeyStore.set(null)
        } finally {
            super.tearDown()
        }
    }

    private fun diskFolder(): java.nio.file.Path = Files.createTempDirectory("cs-platform-test").also { tempDirs.add(it) }

    fun testActionIsInTheProjectViewAndEditorTabMenus() {
        val manager = ActionManager.getInstance()
        val action = manager.getAction("studio.converter.ide.Convert")
        assertInstanceOf(action, ConvertAction::class.java)
        for (groupId in listOf("ProjectViewPopupMenu", "EditorTabPopupMenu")) {
            val group = manager.getAction(groupId) as DefaultActionGroup
            assertTrue(groupId, group.getChildActionsOrStubs().any { manager.getId(it) == "studio.converter.ide.Convert" })
        }
        assertEquals("Convert with Converter Studio…", action.templatePresentation.text)
    }

    fun testActionShowsForFilesOnDiskOnly() {
        val folder = diskFolder()
        Files.writeString(folder.resolve("data.json"), "[]")
        val dir = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(folder)!!
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(folder.resolve("data.json"))!!
        val action = ConvertAction()

        fun visibleFor(vararg files: com.intellij.openapi.vfs.VirtualFile): Boolean {
            val context = SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(*files))
                .build()
            val event = TestActionEvent.createTestEvent(action, context)
            action.update(event)
            return event.presentation.isEnabledAndVisible
        }
        assertTrue(visibleFor(file))
        assertFalse(visibleFor(dir))
        // The light test project's in-memory files are not on disk.
        assertFalse(visibleFor(myFixture.configureByText("x.txt", "x").virtualFile))
    }

    fun testSettingsPersistAndTheKeyStaysOutOfThem() {
        val settings = ConverterStudioSettings.getInstance()
        settings.state.maxCredits = 7
        settings.state.toolPaths["FFMPEG"] = "/opt/ffmpeg/bin/ffmpeg"
        ApiKeyStore.set("cvs_live_platform_test")
        assertEquals("cvs_live_platform_test", ApiKeyStore.get())
        val xml = com.intellij.util.xmlb.XmlSerializer.serialize(settings.state)
        val text = com.intellij.openapi.util.JDOMUtil.write(xml)
        assertTrue(text.contains("/opt/ffmpeg/bin/ffmpeg"))
        assertFalse(text.contains("cvs_live"))
        ApiKeyStore.set(null)
        assertNull(ApiKeyStore.get())
        settings.loadState(ConverterStudioSettings.Settings())
    }

    fun testSettingsPageOpensAndApplies() {
        val configurable = ConverterStudioConfigurable()
        try {
            val component = configurable.createComponent()
            assertNotNull(component)
            configurable.reset()
            assertFalse(configurable.isModified)
            configurable.apply()
        } finally {
            configurable.disposeUIResources()
        }
        assertNotNull(ShowSettingsUtil.getInstance())
    }

    fun testNotificationGroupAndVersion() {
        assertNotNull(NotificationGroupManager.getInstance().getNotificationGroup("Converter Studio"))
        // Written at build time, sent as X-Converter-Studio-Client: jetbrains/<version>.
        assertTrue(ConverterStudioSettings.pluginVersion(), Regex("^\\d+\\.\\d+\\.\\d+$").matches(ConverterStudioSettings.pluginVersion()))
    }

    fun testLocalConversionSavesNextToTheOriginalAndRefreshesTheProjectView() {
        val folder = diskFolder()
        val png = folder.resolve("logo.png")
        ImageIO.write(BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB), "png", png.toFile())
        Files.writeString(folder.resolve("logo.jpg"), "taken")
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(folder)
        val recipe = LocalRecipes.find("png", "jpg", ToolSet.NONE)!!
        val outcome = Conversions(CancelCheck.NEVER).local(png, recipe, ToolSet.NONE, LocalOptions(), null)
        outcome as FileOutcome.Converted
        assertEquals("logo (2).jpg", outcome.result.fileName.toString())
        assertNotNull(LocalFileSystem.getInstance().findFileByNioFile(outcome.result))
        assertEquals("taken", Files.readString(folder.resolve("logo.jpg")))
        val failed = Conversions(CancelCheck.NEVER).local(folder.resolve("logo.jpg"), LocalRecipes.find("jpg", "png", ToolSet.NONE)!!, ToolSet.NONE, LocalOptions(), null)
        assertTrue(failed is FileOutcome.Failed)
    }

    fun testDialogStartsWithThisComputer() {
        val folder = diskFolder()
        Files.writeString(folder.resolve("people.json"), "[]")
        val files = listOf(folder.resolve("people.json"))
        val prepared = Prepared.load(files, CancelCheck.NEVER)
        // No key and no app: only this computer, nothing read from the network.
        assertSame(Catalogue.BUILT_IN, prepared.catalogue)
        assertFalse(prepared.appAvailable)
        assertEquals(listOf("csv", "yaml"), prepared.plan.targets.map { it.to })
        val dialog = ConvertDialog(project, files, prepared)
        try {
            assertTrue(dialog.isOKActionEnabled)
            dialog.doOKAction()
            val (target, route) = dialog.choice!!
            assertEquals("csv", target.to)
            assertEquals(Route.LOCAL, route)
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }
}
