package studio.converter.ide.core

/** Where a conversion runs. */
enum class Route(val title: String) {
    /** On this computer, with the plugin's own code or a program found here. */
    LOCAL("On this computer"),
    /** Handed to the Converter Studio desktop app, which converts on this computer too. */
    APP("Converter Studio app"),
    /** Uploaded to converter.studio; uses credits. */
    CLOUD("Cloud (uses credits)"),
}

/** A target format for the selected files and the routes that can reach it. */
data class TargetOption(
    val to: String,
    val name: String,
    /** The files a local recipe can convert (index in the selection → recipe). */
    val local: Map<Int, LocalRecipe>,
    /** The files the cloud can convert (by the API's catalogue). */
    val cloud: List<Int>,
    /** The files the desktop app can take. */
    val app: List<Int>,
) {
    /** "FFmpeg", "LibreOffice / Pandoc": who does it here. */
    val localLabel: String get() = local.values.map { it.label }.distinct().joinToString(" / ")

    fun files(route: Route): List<Int> = when (route) {
        Route.LOCAL -> local.keys.sorted()
        Route.APP -> app
        Route.CLOUD -> cloud
    }
}

/** What the dialog offers. */
data class ConversionPlan(
    /** Each selected file's format, or null when it is not one Converter Studio knows. */
    val formats: List<String?>,
    val targets: List<TargetOption>,
)

/**
 * Lists the targets for a selection: what this computer can do with the tools found, what the
 * desktop app can take (when the user turned it on) and what the cloud offers (when the API's
 * catalogue was read). Targets that every file reaches on this computer come first.
 */
object Planner {
    fun plan(
        fileNames: List<String>,
        catalogue: Catalogue,
        tools: ToolSet,
        options: LocalOptions,
        appEnabled: Boolean,
    ): ConversionPlan {
        val formats = fileNames.map { catalogue.formatOfName(it) ?: Catalogue.BUILT_IN.formatOfName(it) }
        val order = LinkedHashMap<String, Int>()
        val local = HashMap<String, MutableMap<Int, LocalRecipe>>()
        val cloud = HashMap<String, MutableList<Int>>()
        formats.forEachIndexed { index, format ->
            if (format == null) return@forEachIndexed
            for (recipe in LocalRecipes.targets(format, tools, options)) {
                order.putIfAbsent(recipe.to, order.size)
                local.getOrPut(recipe.to) { LinkedHashMap() }[index] = recipe
            }
            for (to in catalogue.cloudTargets(format)) {
                order.putIfAbsent(to, order.size)
                cloud.getOrPut(to) { ArrayList() }.add(index)
            }
        }
        val n = fileNames.size
        val targets = order.keys.map { to ->
            val localFiles = local[to].orEmpty()
            val cloudFiles = cloud[to].orEmpty()
            TargetOption(
                to = to,
                name = catalogue.name(to),
                local = localFiles,
                cloud = cloudFiles,
                // The app converts what Converter Studio converts; without the catalogue, at
                // least what this computer can.
                app = if (!appEnabled) emptyList() else if (catalogue.fromApi) cloudFiles else localFiles.keys.sorted(),
            )
        }.sortedWith(
            compareBy<TargetOption>(
                { if (it.local.size == n) 0 else if (it.local.isNotEmpty()) 1 else 2 },
                { if (maxOf(it.local.size, it.cloud.size, it.app.size) == n) 0 else 1 },
                { order[it.to] },
            ),
        )
        return ConversionPlan(formats, targets)
    }

    /** The route a target starts with: this computer when it can, then the app, then the cloud. */
    fun defaultRoute(target: TargetOption, cloudReady: Boolean): Route? = when {
        target.local.isNotEmpty() -> Route.LOCAL
        target.app.isNotEmpty() -> Route.APP
        target.cloud.isNotEmpty() && cloudReady -> Route.CLOUD
        else -> null
    }
}
