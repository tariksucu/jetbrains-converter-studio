package studio.converter.ide.run

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.util.EnvironmentUtil
import studio.converter.ide.core.CancelCheck
import studio.converter.ide.core.Catalogue
import studio.converter.ide.core.CloudClient
import studio.converter.ide.core.DesktopApp
import studio.converter.ide.core.Os
import studio.converter.ide.core.ToolDetector
import studio.converter.ide.core.ToolLocator
import studio.converter.ide.core.ToolSet
import studio.converter.ide.settings.ConverterStudioSettings
import java.nio.file.Path
import java.nio.file.Paths

/**
 * What the plugin keeps between conversions: the tools found on this computer, whether the
 * desktop app is installed, and the API's list of formats (for an hour). All of it is worked out
 * on a background thread, the first time it is needed or after the settings change.
 */
@Service(Service.Level.APP)
class ConverterStudioService {
    @Volatile
    private var tools: ToolSet? = null

    @Volatile
    private var app: DesktopApp.Presence? = null

    @Volatile
    private var catalogue: CachedCatalogue? = null

    private class CachedCatalogue(val apiUrl: String, val catalogue: Catalogue, val at: Long)

    /** The tools found (detected once, then cached). */
    fun tools(cancel: CancelCheck = CancelCheck.NEVER): ToolSet {
        tools?.let { return it }
        val settings = ConverterStudioSettings.getInstance()
        val found = ToolDetector(locator()).detect(settings.toolOverrides(), cancel)
        tools = found
        return found
    }

    /** Whether Converter Studio for desktop seems installed (asked once). */
    fun desktopApp(): DesktopApp.Presence = app ?: DesktopApp.detect(Os.current()).also { app = it }

    /** The API's catalogue of formats; read again after an hour. Throws when the API cannot be reached. */
    fun catalogue(apiUrl: String, cancel: CancelCheck): Catalogue {
        val cached = catalogue
        if (cached != null && cached.apiUrl == apiUrl && System.currentTimeMillis() - cached.at < CATALOGUE_MAX_AGE_MS) return cached.catalogue
        val fresh = CloudClient(apiUrl, null, ConverterStudioSettings.pluginVersion()).formats(cancel)
        catalogue = CachedCatalogue(apiUrl, fresh, System.currentTimeMillis())
        return fresh
    }

    /** Forgets everything (the settings changed, or the user asked to detect again). */
    fun invalidate() {
        tools = null
        app = null
        catalogue = null
    }

    companion object {
        private const val CATALOGUE_MAX_AGE_MS = 60 * 60 * 1000L

        fun getInstance(): ConverterStudioService = ApplicationManager.getApplication().getService(ConverterStudioService::class.java)

        /** The search for the tools, with the PATH of the user's shell (macOS GUI apps get a short one). */
        fun locator(): ToolLocator = ToolLocator(
            Os.current(),
            EnvironmentUtil.getValue("PATH") ?: System.getenv("PATH"),
            { name -> EnvironmentUtil.getValue(name) ?: System.getenv(name) },
            System.getProperty("user.home"),
        )

        /** LibreOffice's profile for the plugin, kept between conversions (its first start is slow). */
        fun libreOfficeProfile(): Path = Paths.get(PathManager.getSystemPath(), "converter-studio", "libreoffice-profile")
    }
}
