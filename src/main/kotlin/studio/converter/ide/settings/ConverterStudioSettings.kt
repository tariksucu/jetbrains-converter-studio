package studio.converter.ide.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import studio.converter.ide.core.Endpoints
import studio.converter.ide.core.LocalOptions
import studio.converter.ide.core.ToolId

/**
 * The plugin's settings (Settings | Tools | Converter Studio), stored in `converter-studio.xml`
 * of the IDE's options. The API key is not here: it is in the IDE's password safe ([ApiKeyStore]).
 */
@Service(Service.Level.APP)
@State(name = "ConverterStudioSettings", storages = [Storage("converter-studio.xml")])
class ConverterStudioSettings : PersistentStateComponent<ConverterStudioSettings.Settings> {
    class Settings {
        /** Paths the user typed, by [ToolId] name; empty means "find it". */
        var toolPaths: MutableMap<String, String> = LinkedHashMap()

        /** Tesseract's languages. */
        var ocrLanguages: String = "eng"

        /** Hand files to the Converter Studio desktop app when it is installed. */
        var useDesktopApp: Boolean = false

        var apiUrl: String = Endpoints.DEFAULT_API_URL

        /** A cloud conversion that costs more asks first; 0 asks every time. */
        var maxCredits: Int = 20
    }

    private var settings = Settings()

    override fun getState(): Settings = settings

    override fun loadState(state: Settings) {
        settings = state
    }

    fun toolOverrides(): Map<ToolId, String> = ToolId.values().mapNotNull { id ->
        settings.toolPaths[id.name]?.trim()?.takeIf { it.isNotEmpty() }?.let { id to it }
    }.toMap()

    fun localOptions(): LocalOptions = LocalOptions(ocrLanguages = settings.ocrLanguages)

    companion object {
        const val PLUGIN_ID = "studio.converter.ide"

        fun getInstance(): ConverterStudioSettings = ApplicationManager.getApplication().getService(ConverterStudioSettings::class.java)

        /** The plugin's version (written at build time), for `X-Converter-Studio-Client` and the User-Agent. */
        fun pluginVersion(): String = VERSION

        private val VERSION: String by lazy {
            ConverterStudioSettings::class.java.getResourceAsStream("/studio/converter/ide/version.txt")
                ?.use { String(it.readAllBytes(), Charsets.UTF_8).trim() }
                ?.takeIf { it.isNotEmpty() } ?: "dev"
        }
    }
}

/**
 * The API key, in the IDE's password safe (the system keychain, KeePass or memory only, as the
 * user chose in Settings | Appearance & Behavior | System Settings | Passwords). Slow on some
 * systems: never call it on the UI thread.
 */
object ApiKeyStore {
    private val attributes: CredentialAttributes
        get() = CredentialAttributes(generateServiceName("Converter Studio", "API key"))

    fun get(): String? = PasswordSafe.instance.getPassword(attributes)?.takeIf { it.isNotBlank() }

    fun set(key: String?) {
        PasswordSafe.instance.setPassword(attributes, key?.trim()?.takeIf { it.isNotEmpty() })
    }
}
