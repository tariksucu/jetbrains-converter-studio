package studio.converter.ide.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import studio.converter.ide.core.CloudClient
import studio.converter.ide.core.ConversionException
import studio.converter.ide.core.DesktopApp
import studio.converter.ide.core.Endpoints
import studio.converter.ide.core.OcrLanguages
import studio.converter.ide.core.ToolDetector
import studio.converter.ide.core.ToolId
import studio.converter.ide.core.ToolSet
import studio.converter.ide.run.ConverterStudioService

/** Settings | Tools | Converter Studio. */
class ConverterStudioConfigurable : BoundConfigurable("Converter Studio") {
    private val settings = ConverterStudioSettings.getInstance()
    private val toolFields = LinkedHashMap<ToolId, TextFieldWithBrowseButton>()
    private val toolStatus = ToolId.values().associateWith { JBLabel("Looking…") }
    private val keyField = JBPasswordField()
    private val keyStatus = JBLabel("")
    private var removeKey = false

    override fun createPanel(): DialogPanel {
        val panel = panel {
            group("On This Computer") {
                row {
                    comment(
                        "Conversions run here first: with the plugin's own code (images, JSON, CSV, YAML, subtitles) and with the " +
                            "programs below when they are installed. Leave a path empty to find the program automatically.",
                    )
                }
                for (tool in ToolId.values()) {
                    row("${tool.displayName}:") {
                        val field = textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("${tool.displayName} Program"))
                            .align(AlignX.FILL)
                            .bindText({ settings.state.toolPaths[tool.name].orEmpty() }, { value ->
                                if (value.isBlank()) settings.state.toolPaths.remove(tool.name) else settings.state.toolPaths[tool.name] = value.trim()
                            })
                        toolFields[tool] = field.component
                    }
                    row("") { cell(toolStatus.getValue(tool)) }
                }
                row {
                    button("Detect Again") { detect() }
                }
                row("OCR languages:") {
                    textField()
                        .bindText({ settings.state.ocrLanguages }, { settings.state.ocrLanguages = it.trim().ifEmpty { "eng" } })
                        .columns(16)
                        .comment("Tesseract's languages, for example <code>eng</code> or <code>eng+deu</code>.")
                }
            }
            group("Converter Studio App") {
                row {
                    checkBox("Use the Converter Studio desktop app when installed")
                        .bindSelected({ settings.state.useDesktopApp }, { settings.state.useDesktopApp = it })
                        .comment(
                            "Offers \"Converter Studio app\" in the convert dialog: the app opens with the files and the format chosen, " +
                                "and you press Convert there. The app converts on this computer. Needs Converter Studio for desktop " +
                                "${DesktopApp.MIN_VERSION} or later (<a href=\"https://converter.studio\">converter.studio</a>).",
                        )
                }
            }
            group("Cloud (Uses Credits)") {
                row {
                    comment(
                        "Cloud conversions upload the file to converter.studio over HTTPS and use credits of your account. They run only " +
                            "when you choose Cloud in the convert dialog. <a href=\"https://converter.studio/privacy\">Privacy policy</a>",
                    )
                }
                row("API key:") {
                    cell(keyField).columns(28).comment("Kept in the IDE's password safe, never in settings files. Type a new key to replace it.")
                    cell(keyStatus)
                }
                row {
                    browserLink("Get an API key", "https://converter.studio/account#api-keys")
                    button("Check Key") { checkKey() }
                    button("Remove Key") {
                        removeKey = true
                        keyField.text = ""
                        keyStatus.text = "The key will be removed when you apply."
                    }
                }
                row("API address:") {
                    textField()
                        .bindText({ settings.state.apiUrl }, { settings.state.apiUrl = it.trim().trimEnd('/').ifEmpty { Endpoints.DEFAULT_API_URL } })
                        .columns(28)
                        .comment("Change it only for a test server: your API key is sent to this address. HTTPS only.")
                }
                row("Max credits per conversion:") {
                    intTextField(0..100_000)
                        .bindIntText({ settings.state.maxCredits }, { settings.state.maxCredits = it })
                        .comment("A conversion that costs more asks first; 0 asks every time. Most images and documents cost 1 credit.")
                }
            }
        }
        detect(force = false)
        loadKeyStatus()
        return panel
    }

    override fun isModified(): Boolean = super.isModified() || keyField.password.isNotEmpty() || removeKey

    override fun apply() {
        val typedUrl = settings.state.apiUrl
        super.apply()
        try {
            Endpoints.checkApiUrl(settings.state.apiUrl)
        } catch (e: ConversionException) {
            settings.state.apiUrl = typedUrl
            throw ConfigurationException(e.message)
        }
        if (OcrLanguages.parse(settings.state.ocrLanguages) == null) {
            throw ConfigurationException("The OCR languages must look like eng or eng+deu.")
        }
        val newKey = String(keyField.password).trim()
        val remove = removeKey
        keyField.text = ""
        removeKey = false
        if (newKey.isNotEmpty() || remove) {
            keyStatus.text = "Saving…"
            ApplicationManager.getApplication().executeOnPooledThread {
                ApiKeyStore.set(if (remove && newKey.isEmpty()) null else newKey)
                ui { loadKeyStatus() }
            }
        }
        ConverterStudioService.getInstance().invalidate()
        detect(force = true)
    }

    /** Finds the tools again (with the paths in the fields) and shows what was found. */
    private fun detect(force: Boolean = true) {
        toolStatus.values.forEach { it.text = "Looking…" }
        val overrides = toolFields.mapValues { it.value.text.trim() }.filterValues { it.isNotEmpty() }
        ApplicationManager.getApplication().executeOnPooledThread {
            val found: ToolSet = if (!force && overrides == settings.toolOverrides()) {
                ConverterStudioService.getInstance().tools()
            } else {
                ToolDetector(ConverterStudioService.locator()).detect(overrides)
            }
            ui {
                for (tool in ToolId.values()) {
                    val info = found[tool]
                    toolStatus.getValue(tool).text = when {
                        info != null -> "Found: ${info.label} at ${info.path}"
                        found.problems[tool] != null -> found.problems.getValue(tool)
                        else -> "Not found. For ${tool.purpose}."
                    }
                }
            }
        }
    }

    private fun loadKeyStatus() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val saved = ApiKeyStore.get() != null
            ui { keyStatus.text = if (saved) "A key is saved." else "No key saved." }
        }
    }

    /** Asks Converter Studio about the typed key (or the saved one): the plan and credits. */
    private fun checkKey() {
        val typed = String(keyField.password).trim()
        val apiUrl = settings.state.apiUrl
        keyStatus.text = "Checking…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val text = try {
                val key = typed.ifEmpty { ApiKeyStore.get() } ?: throw ConversionException("noApiKey", "No key to check.")
                val account = CloudClient(apiUrl, key, ConverterStudioSettings.pluginVersion()).account()
                "Works: ${account.plan} plan, ${account.credits} credits."
            } catch (e: ConversionException) {
                e.message ?: e.code
            }
            ui { keyStatus.text = text }
        }
    }

    private fun ui(block: () -> Unit) = ApplicationManager.getApplication().invokeLater(block, ModalityState.any())
}
