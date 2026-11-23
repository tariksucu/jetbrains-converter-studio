package studio.converter.ide.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import studio.converter.ide.actions.Prepared
import studio.converter.ide.core.DesktopApp
import studio.converter.ide.core.Planner
import studio.converter.ide.core.Route
import studio.converter.ide.core.TargetOption
import studio.converter.ide.core.ToolId
import studio.converter.ide.run.ConversionTask
import studio.converter.ide.settings.ConverterStudioSettings
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * The format list and where to convert: on this computer (the default when it can), the
 * Converter Studio app, or the cloud (uses credits). Each route says what it does with the files.
 */
class ConvertDialog(private val project: Project, private val files: List<Path>, private val prepared: Prepared) : DialogWrapper(project, true) {
    private val targets = prepared.plan.targets
    private val list = JBList(targets)
    private val buttons = Route.values().associateWith { JBRadioButton(it.title) }
    private val group = ButtonGroup()
    private lateinit var details: JEditorPane

    /** The choice, once OK was pressed. */
    var choice: Pair<TargetOption, Route>? = null
        private set

    init {
        title = "Convert with Converter Studio"
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = TargetRenderer()
        list.visibleRowCount = 10
        list.addListSelectionListener { if (!it.valueIsAdjusting) selectDefaultRoute() }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && isOKActionEnabled) doOKAction()
            }
        })
        buttons.values.forEach { button ->
            group.add(button)
            button.addActionListener { describe() }
        }
        init()
        list.selectedIndex = 0
        selectDefaultRoute()
    }

    override fun createCenterPanel(): JComponent = panel {
        row { label(header()) }
        prepared.plan.formats.withIndex().filter { it.value == null }.takeIf { it.isNotEmpty() }?.let { unknown ->
            row { comment("Not a format Converter Studio knows: " + unknown.joinToString(", ") { ConversionTask.escape(files[it.index].fileName.toString()) }) }
        }
        row { scrollCell(list).align(Align.FILL) }.resizableRow()
        group("Where") {
            // The radio buttons in a plain panel: their group is ours (they are enabled per target).
            row {
                cell(
                    JPanel().apply {
                        layout = BoxLayout(this, BoxLayout.Y_AXIS)
                        isOpaque = false
                        Route.values().forEach { add(buttons.getValue(it)) }
                    },
                )
            }
            row { details = comment("", maxLineLength = 70).align(AlignX.FILL).component }
        }
        if (!prepared.hasKey) {
            row {
                comment("More formats in the cloud (uses credits): add a Converter Studio API key in <a href=\"settings\">Settings</a>.") {
                    ConversionTask.openSettings(project)
                }
            }
        }
        prepared.catalogueError?.let { error ->
            row { comment("Converter Studio's list of formats could not be loaded, so the cloud and the app offer less: ${ConversionTask.escape(error)}") }
        }
    }.apply { preferredSize = JBUI.size(560, 520) }

    override fun getPreferredFocusedComponent(): JComponent = list

    public override fun doOKAction() {
        val target = list.selectedValue ?: return
        val route = Route.values().firstOrNull { buttons.getValue(it).isSelected && buttons.getValue(it).isEnabled } ?: return
        choice = target to route
        super.doOKAction()
    }

    private fun header(): String = if (files.size == 1) "Convert ${files[0].fileName} to:" else "Convert ${files.size} files to:"

    /** Enables the routes the selected target has and picks this computer first. */
    private fun selectDefaultRoute() {
        val target = list.selectedValue
        for (route in Route.values()) {
            val button = buttons.getValue(route)
            val reason = target?.let { unavailable(it, route) }
            button.isEnabled = target != null && reason == null
            button.text = routeText(target, route, reason)
        }
        val route = target?.let { Planner.defaultRoute(it, prepared.hasKey) }
        if (route != null && buttons.getValue(route).isEnabled) buttons.getValue(route).isSelected = true else group.clearSelection()
        describe()
    }

    /** Why a route cannot convert the target, or null when it can. */
    private fun unavailable(target: TargetOption, route: Route): String? = when (route) {
        Route.LOCAL -> if (target.local.isEmpty()) "no program here does this" else null
        Route.APP -> when {
            !ConverterStudioSettings.getInstance().state.useDesktopApp -> "off in Settings"
            prepared.appNote != null -> prepared.appNote
            target.app.isEmpty() -> "not for these files"
            else -> null
        }
        Route.CLOUD -> when {
            !prepared.hasKey -> "needs an API key"
            target.cloud.isEmpty() -> if (prepared.catalogue.fromApi) "not offered for these files" else "formats not loaded"
            else -> null
        }
    }

    private fun routeText(target: TargetOption?, route: Route, reason: String?): String {
        val base = when (route) {
            Route.LOCAL -> if (target != null && target.local.isNotEmpty()) "${route.title} · ${target.localLabel}" else route.title
            else -> route.title
        }
        if (reason != null) return "$base ($reason)"
        val count = target?.files(route)?.size ?: 0
        return if (target != null && files.size > 1 && count < files.size) "$base ($count of ${files.size} files)" else base
    }

    /** What the chosen route does with the files. */
    private fun describe() {
        val target = list.selectedValue
        val route = Route.values().firstOrNull { buttons.getValue(it).isSelected }
        isOKActionEnabled = target != null && route != null
        setOKButtonText(
            when (route) {
                Route.CLOUD -> "Upload and Convert"
                Route.APP -> "Open in Converter Studio"
                else -> "Convert"
            },
        )
        if (!::details.isInitialized) return
        details.text = when {
            target == null || route == null -> "Choose a format and where to convert it."
            route == Route.LOCAL -> {
                val tools = target.local.values.flatMap { it.engine.tools }.distinct()
                val where = tools.mapNotNull { prepared.tools[it] }.joinToString(", ") { "${it.label} (${ConversionTask.escape(it.path.toString())})" }
                "Runs on this computer" + (if (where.isNotEmpty()) " with $where" else " with the plugin's own code") +
                    ". The files do not leave this computer. Results are saved next to the originals." +
                    if (ToolId.LIBREOFFICE in tools) " The first LibreOffice conversion takes a little longer." else ""
            }
            route == Route.APP ->
                "Opens Converter Studio for desktop (${DesktopApp.MIN_VERSION} or later) with the files and ${target.name} chosen; " +
                    "you press Convert in the app, which converts on this computer."
            else -> {
                val max = ConverterStudioSettings.getInstance().state.maxCredits
                "Uploads the files to converter.studio over HTTPS and converts them there, using credits of your account " +
                    "(a conversion above $max credits asks first). Uploads are deleted after the conversion, results after 6 hours. " +
                    "<a href=\"https://converter.studio/privacy\">Privacy policy</a>"
            }
        }
    }

    private inner class TargetRenderer : ColoredListCellRenderer<TargetOption>() {
        override fun customizeCellRenderer(list: JList<out TargetOption>, value: TargetOption?, index: Int, selected: Boolean, hasFocus: Boolean) {
            value ?: return
            append(value.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            val route = Planner.defaultRoute(value, prepared.hasKey)
            val where = when (route) {
                Route.LOCAL -> "On this computer · ${value.localLabel}"
                Route.APP -> "Converter Studio app"
                Route.CLOUD -> "Cloud (uses credits)"
                null -> "Cloud (needs an API key)"
            }
            append("   $where", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            val count = maxOf(value.local.size, value.cloud.size, value.app.size)
            if (files.size > 1 && count < files.size) append("   $count of ${files.size} files", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
    }
}
