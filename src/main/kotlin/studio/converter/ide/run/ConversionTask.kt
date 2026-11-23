package studio.converter.ide.run

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.vfs.LocalFileSystem
import studio.converter.ide.core.CanceledException
import studio.converter.ide.core.CloudClient
import studio.converter.ide.core.ConversionException
import studio.converter.ide.core.Route
import studio.converter.ide.core.TargetOption
import studio.converter.ide.core.ToolSet
import studio.converter.ide.settings.ApiKeyStore
import studio.converter.ide.settings.ConverterStudioConfigurable
import studio.converter.ide.settings.ConverterStudioSettings
import java.awt.Desktop
import java.nio.file.Path

/**
 * Converts the chosen files in the background (status bar progress with Cancel), then reports
 * with a notification: Open and Show in folder for the results; for failures the reason, and
 * "Try in the cloud (uses credits)" after a local attempt (never automatic).
 */
class ConversionTask(
    project: Project,
    private val files: List<Path>,
    private val target: TargetOption,
    private val route: Route,
    private val tools: ToolSet,
) : Task.Backgroundable(project, if (route == Route.CLOUD) "Converting in the cloud" else "Converting", true) {
    private val outcomes = ArrayList<FileOutcome>()

    override fun run(indicator: ProgressIndicator) {
        indicator.isIndeterminate = false
        val settings = ConverterStudioSettings.getInstance()
        val indexes = target.files(route)
        val cancel = Conversions.cancelOf(indicator)
        var client: CloudClient? = null
        if (route == Route.CLOUD) {
            val key = ApiKeyStore.get()
            if (key == null) {
                indexes.forEach { outcomes += FileOutcome.Failed(files[it], "noApiKey", "No Converter Studio API key is set.") }
                return
            }
            try {
                client = CloudClient(settings.state.apiUrl, key, ConverterStudioSettings.pluginVersion())
            } catch (e: ConversionException) {
                indexes.forEach { outcomes += FileOutcome.Failed(files[it], e.code, e.message ?: e.code) }
                return
            }
        }
        val question = CostQuestion { name, credits, max -> askCost(name, credits, max) }
        try {
            indexes.forEachIndexed { k, index ->
                val file = files[index]
                indicator.text = "Converting ${file.fileName} (${k + 1} of ${indexes.size})"
                indicator.text2 = ""
                indicator.fraction = k.toDouble() / indexes.size
                val conversions = Conversions(cancel) { text, fraction ->
                    indicator.text2 = text
                    if (fraction != null) indicator.fraction = (k + fraction.coerceIn(0.0, 1.0)) / indexes.size
                }
                val outcome = when (route) {
                    Route.LOCAL -> conversions.local(
                        file,
                        target.local.getValue(index),
                        tools,
                        settings.localOptions(),
                        ConverterStudioService.libreOfficeProfile(),
                    )
                    Route.CLOUD -> conversions.cloud(file, target.to, client!!, settings.state.maxCredits.toLong().coerceAtLeast(0), question)
                    Route.APP -> error("the app gets its files through DesktopHandOver")
                }
                outcomes += outcome
                // After these, every other file would fail the same way.
                if (outcome is FileOutcome.Failed && outcome.code in STOPS_BATCH) {
                    indexes.drop(k + 1).forEach { outcomes += FileOutcome.Failed(files[it], "skipped", "Not tried after the error above.") }
                    return
                }
            }
        } catch (_: CanceledException) {
            // onCancel reports what was done.
        }
        files.indices.filter { it !in indexes }.forEach {
            outcomes += FileOutcome.Failed(files[it], "notOffered", "${route.title} does not convert this file to ${target.name}.")
        }
    }

    override fun onSuccess() = report(canceled = false)

    override fun onCancel() = report(canceled = true)

    override fun onThrowable(error: Throwable) {
        notify(NotificationType.ERROR, "Conversion stopped", "Something went wrong: ${error.message ?: error.javaClass.simpleName}")
    }

    private fun report(canceled: Boolean) {
        val project = project ?: return
        val converted = outcomes.filterIsInstance<FileOutcome.Converted>()
        val failed = outcomes.filterIsInstance<FileOutcome.Failed>()
        val where = when (route) {
            Route.LOCAL -> "on this computer (${target.localLabel})"
            Route.CLOUD -> "in the cloud"
            Route.APP -> "in Converter Studio"
        }
        if (converted.isNotEmpty()) {
            val names = converted.take(5).joinToString(", ") { it.result.fileName.toString() } + if (converted.size > 5) ", …" else ""
            val title = if (converted.size == 1) "Converted to ${target.name}" else "${converted.size} files converted to ${target.name}"
            val notification = group().createNotification(title, "${escape(names)} ($where)", NotificationType.INFORMATION)
            val first = converted.first().result
            notification.addAction(NotificationAction.createSimple("Open") { open(project, first) })
            notification.addAction(NotificationAction.createSimple("Show in folder") { RevealFileAction.openFile(first) })
            notification.notify(project)
        }
        if (canceled) {
            notify(NotificationType.INFORMATION, "Conversion canceled", if (converted.isEmpty()) "Nothing was saved." else "The files finished before Cancel were saved.")
        }
        if (failed.isNotEmpty()) {
            val lines = failed.take(5).joinToString("<br>") { "${escape(it.source.fileName.toString())}: ${escape(it.message)}" } +
                if (failed.size > 5) "<br>…and ${failed.size - 5} more" else ""
            val title = if (failed.size == 1) "Not converted to ${target.name}" else "${failed.size} files not converted to ${target.name}"
            val notification = group().createNotification(title, lines, NotificationType.WARNING)
            val cloudFiles = failed.mapNotNull { f -> files.indexOf(f.source).takeIf { it >= 0 && it in target.cloud } }
            if (route == Route.LOCAL && cloudFiles.isNotEmpty()) {
                notification.addAction(NotificationAction.createSimpleExpiring("Try in the cloud (uses credits)") { tryInCloud(project, cloudFiles) })
            } else if (route == Route.LOCAL && target.cloud.isEmpty()) {
                notification.addAction(NotificationAction.createSimple("Cloud conversions…") { openSettings(project) })
            }
            if (route == Route.LOCAL || failed.any { it.code in SETTINGS_CODES }) {
                notification.addAction(NotificationAction.createSimple("Settings") { openSettings(project) })
            }
            notification.notify(project)
        }
    }

    /** The cloud for files that failed here, after the user says yes to the upload. */
    private fun tryInCloud(project: Project, indexes: List<Int>) {
        val count = if (indexes.size == 1) files[indexes[0]].fileName.toString() else "${indexes.size} files"
        val yes = MessageDialogBuilder.yesNo(
            "Convert in the Cloud?",
            "$count will be uploaded to converter.studio over HTTPS and converted to ${target.name} there. " +
                "This uses credits of your Converter Studio account.",
        ).yesText("Upload and Convert").noText("Cancel").ask(project)
        if (yes) ConversionTask(project, files, target.copy(cloud = indexes), Route.CLOUD, tools).queue()
    }

    private fun askCost(name: String, credits: Long?, maxCredits: Long): Boolean {
        var yes = false
        ApplicationManager.getApplication().invokeAndWait({
            yes = MessageDialogBuilder.yesNo(
                "Convert $name?",
                "This conversion costs ${credits ?: "more"} credits, more than your limit of $maxCredits per conversion " +
                    "(Settings | Tools | Converter Studio). Nothing is charged unless you go ahead.",
            ).yesText("Convert").noText("Skip").ask(project)
        }, ModalityState.defaultModalityState())
        return yes
    }

    private fun notify(type: NotificationType, title: String, content: String) {
        val project = project ?: return
        group().createNotification(title, escape(content), type).notify(project)
    }

    companion object {
        private val STOPS_BATCH = setOf("invalidApiKey", "noApiKey", "notEnoughCredits", "dailyLimit", "monthlyLimit", "badApiUrl", "network")
        private val SETTINGS_CODES = setOf("invalidApiKey", "noApiKey", "badApiUrl", "badSetting", "toolMissing")

        fun group() = NotificationGroupManager.getInstance().getNotificationGroup("Converter Studio")

        fun openSettings(project: Project?) = ShowSettingsUtil.getInstance().showSettingsDialog(project, ConverterStudioConfigurable::class.java)

        /** Opens a result: in the IDE when it has an editor for the file, otherwise with the system's app. */
        fun open(project: Project, path: Path) {
            val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: return
            val type = file.fileType
            if (!type.isBinary || type.name == "Image" || type.name == "SVG") {
                OpenFileDescriptor(project, file).navigate(true)
                return
            }
            ApplicationManager.getApplication().executeOnPooledThread {
                val opened = runCatching {
                    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                        Desktop.getDesktop().open(path.toFile())
                        true
                    } else {
                        false
                    }
                }.getOrDefault(false)
                if (!opened) ApplicationManager.getApplication().invokeLater { RevealFileAction.openFile(path) }
            }
        }

        fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
