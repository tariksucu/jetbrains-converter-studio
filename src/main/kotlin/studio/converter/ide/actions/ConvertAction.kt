package studio.converter.ide.actions

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import studio.converter.ide.core.CancelCheck
import studio.converter.ide.core.CanceledException
import studio.converter.ide.core.Catalogue
import studio.converter.ide.core.ConversionException
import studio.converter.ide.core.ConversionPlan
import studio.converter.ide.core.DesktopApp
import studio.converter.ide.core.Os
import studio.converter.ide.core.Planner
import studio.converter.ide.core.ProcessRunner
import studio.converter.ide.core.Route
import studio.converter.ide.core.TargetOption
import studio.converter.ide.core.ToolSet
import studio.converter.ide.run.ConversionTask
import studio.converter.ide.run.ConverterStudioService
import studio.converter.ide.settings.ApiKeyStore
import studio.converter.ide.settings.ConverterStudioSettings
import studio.converter.ide.ui.ConvertDialog
import java.awt.GraphicsEnvironment
import java.nio.file.Path
import java.time.Duration

/** "Convert with Converter Studio…" in the Project view and on editor tabs. */
class ConvertAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && selectedFiles(e).isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val files = selectedFiles(e)
        if (files.isNotEmpty()) ConvertFlow.start(project, files)
    }

    companion object {
        /** The selected files on this computer's disk (folders, files inside archives and in-memory files left out). */
        fun selectedFiles(e: AnActionEvent): List<VirtualFile> {
            val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList()
                ?: listOfNotNull(e.getData(CommonDataKeys.VIRTUAL_FILE))
            return files.filter { !it.isDirectory && it.isInLocalFileSystem && it.fileSystem.protocol == "file" }.distinct()
        }
    }
}

/** What the dialog needs, worked out on a background thread. */
data class Prepared(
    val tools: ToolSet,
    val catalogue: Catalogue,
    /** Why the API's list of formats is missing, when it was wanted. */
    val catalogueError: String?,
    val hasKey: Boolean,
    /** The desktop app is turned on, seems installed and can take these files. */
    val appAvailable: Boolean,
    /** Why the app is not offered, when it is turned on. */
    val appNote: String?,
    val plan: ConversionPlan,
) {
    companion object {
        fun load(files: List<Path>, cancel: CancelCheck): Prepared {
            val settings = ConverterStudioSettings.getInstance()
            val service = ConverterStudioService.getInstance()
            val tools = service.tools(cancel)
            val hasKey = ApiKeyStore.get() != null
            var appNote: String? = null
            val appAvailable = if (!settings.state.useDesktopApp) {
                false
            } else if (GraphicsEnvironment.isHeadless()) {
                // A remote development host: the files are not on the computer the app runs on.
                appNote = "not in a remote session"
                false
            } else if (!files.all(DesktopApp::canHandOver)) {
                appNote = "not for files inside WSL"
                false
            } else if (service.desktopApp() == DesktopApp.Presence.NOT_FOUND) {
                appNote = "not installed"
                false
            } else {
                true
            }
            var catalogue = Catalogue.BUILT_IN
            var catalogueError: String? = null
            // The list of formats is public (no key is sent), and read only when the cloud or the app is set up.
            if (hasKey || appAvailable) {
                try {
                    catalogue = service.catalogue(settings.state.apiUrl, cancel)
                } catch (e: CanceledException) {
                    throw e
                } catch (e: ConversionException) {
                    catalogueError = e.message
                }
            }
            val plan = Planner.plan(files.map { it.fileName.toString() }, catalogue, tools, settings.localOptions(), appAvailable)
            return Prepared(tools, catalogue, catalogueError, hasKey, appAvailable, appNote, plan)
        }
    }
}

/** From the selection to the dialog, then to the chosen route. */
object ConvertFlow {
    fun start(project: Project, files: List<VirtualFile>) {
        val paths = files.mapNotNull { runCatching { it.toNioPath() }.getOrNull() }
        if (paths.isEmpty()) return
        val prepared = try {
            ProgressManager.getInstance().run(object : Task.WithResult<Prepared, RuntimeException>(project, "Converter Studio: looking for conversion tools", true) {
                override fun compute(indicator: ProgressIndicator): Prepared = try {
                    Prepared.load(paths, CancelCheck { indicator.isCanceled })
                } catch (_: CanceledException) {
                    throw ProcessCanceledException()
                }
            })
        } catch (_: ProcessCanceledException) {
            return
        }
        if (prepared.plan.targets.isEmpty()) {
            val names = if (paths.size == 1) paths[0].fileName.toString() else "these files"
            val cloudHint = if (prepared.hasKey) "" else "\n\nMore formats can be converted in the cloud with a Converter Studio API key (Settings | Tools | Converter Studio)."
            Messages.showInfoMessage(project, "No conversion found for $names on this computer.$cloudHint", "Convert with Converter Studio")
            return
        }
        val dialog = ConvertDialog(project, paths, prepared)
        if (!dialog.showAndGet()) return
        val (target, route) = dialog.choice ?: return
        when (route) {
            Route.LOCAL, Route.CLOUD -> ConversionTask(project, paths, target, route, prepared.tools).queue()
            Route.APP -> handOver(project, paths, target)
        }
    }

    /** Opens the desktop app with the files and the format chosen. */
    fun handOver(project: Project, files: List<Path>, target: TargetOption) {
        val chosen = target.app.map { files[it] }
        val links = DesktopApp.links(target.to, chosen)
        ApplicationManager.getApplication().executeOnPooledThread {
            val failure = links.firstNotNullOfOrNull { link ->
                val result = runCatching { ProcessRunner().run(DesktopApp.openCommand(Os.current(), link), Duration.ofSeconds(30)) }
                when {
                    result.isFailure -> result.exceptionOrNull()?.message ?: "the link could not be opened"
                    result.getOrThrow().exitCode != 0 -> "the system could not open the link (exit code ${result.getOrThrow().exitCode})"
                    else -> null
                }
            }
            ApplicationManager.getApplication().invokeLater {
                val count = if (chosen.size == 1) chosen[0].fileName.toString() else "${chosen.size} files"
                if (failure == null) {
                    ConversionTask.group().createNotification(
                        "Sent to Converter Studio",
                        ConversionTask.escape(
                            "$count with ${target.name} chosen. Press Convert in the app. " +
                                "This needs Converter Studio for desktop ${DesktopApp.MIN_VERSION} or later.",
                        ),
                        NotificationType.INFORMATION,
                    ).notify(project)
                } else {
                    ConversionTask.group().createNotification(
                        "Converter Studio did not open",
                        ConversionTask.escape("Could not open the desktop app: $failure. Is Converter Studio for desktop ${DesktopApp.MIN_VERSION} or later installed?"),
                        NotificationType.WARNING,
                    ).notify(project)
                }
            }
        }
    }
}
