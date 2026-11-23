package studio.converter.ide.core

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration

/**
 * The hand-over to Converter Studio for desktop: the plugin opens
 * `converter-studio://convert?to=<format>&from=jetbrains&file=<path>[&file=…]` with the operating
 * system and expects no reply. The app shows a "Files from …" card (Continue or Ignore), then
 * lists the files in its Convert tab with the format chosen; the user presses Convert there.
 * Nothing is uploaded and nothing starts by itself. Older app versions only come to the front.
 */
object DesktopApp {
    /** The first desktop version that takes these links. */
    const val MIN_VERSION = "0.1.11"

    /** The client name in the link (`from=`). */
    const val CLIENT = "jetbrains"

    const val SCHEME = "converter-studio"

    /** The app's bundle id on macOS. */
    const val MAC_BUNDLE_ID = "studio.converter.desktop"

    /**
     * Links longer than this are split (Windows passes about 2 000 characters to a link handler);
     * the app merges links with the same `from` and `to` that arrive within 15 seconds.
     */
    const val MAX_LINK_LENGTH = 2000

    /** The app takes at most this many files per link. */
    const val MAX_FILES_PER_LINK = 200

    /** One hand-over link. */
    fun link(to: String, files: List<Path>): String = buildString {
        append(SCHEME).append("://convert?to=").append(encode(to)).append("&from=").append(CLIENT)
        for (file in files) append("&file=").append(encode(file.toAbsolutePath().toString()))
    }

    /** The links for [files]: one, or several when one would be too long or hold too many files. */
    fun links(to: String, files: List<Path>): List<String> {
        val out = ArrayList<String>()
        var batch = ArrayList<Path>()
        for (file in files) {
            if (batch.isNotEmpty() && (batch.size >= MAX_FILES_PER_LINK || link(to, batch + file).length > MAX_LINK_LENGTH)) {
                out.add(link(to, batch))
                batch = ArrayList()
            }
            batch.add(file)
        }
        if (batch.isNotEmpty()) out += link(to, batch)
        return out
    }

    /**
     * Whether the app on this computer should get this file: not a file inside WSL
     * (`\\wsl$\…`, `\\wsl.localhost\…`), whose path the Windows app does not handle.
     */
    fun canHandOver(path: Path): Boolean = canHandOver(path.toString())

    fun canHandOver(path: String): Boolean {
        val text = path.replace('/', '\\').lowercase()
        return !(text.startsWith("\\\\wsl$\\") || text.startsWith("\\\\wsl.localhost\\"))
    }

    /** Percent-encoding for a query value (spaces as `%20`, not `+`). */
    fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    /** The command that opens a link with the system's handler, as an argument list. */
    fun openCommand(os: Os, link: String): List<String> = when (os) {
        Os.WINDOWS -> listOf("rundll32.exe", "url.dll,FileProtocolHandler", link)
        Os.MAC -> listOf("open", link)
        Os.LINUX -> listOf("xdg-open", link)
    }

    /** Whether the app is installed, as far as the system can say. */
    enum class Presence { INSTALLED, NOT_FOUND, UNKNOWN }

    /** Asks the system whether something handles `converter-studio://` links. */
    fun detect(os: Os, runner: ProcessRunner = ProcessRunner(), home: String = System.getProperty("user.home")): Presence {
        val timeout = Duration.ofSeconds(10)
        fun ask(vararg command: String): ProcessResult? = runCatching { runner.run(command.toList(), timeout) }.getOrNull()
        return when (os) {
            Os.LINUX -> {
                val answer = ask("xdg-mime", "query", "default", "x-scheme-handler/$SCHEME") ?: return Presence.UNKNOWN
                if (answer.exitCode == 0 && answer.output.isNotBlank()) Presence.INSTALLED else Presence.NOT_FOUND
            }
            Os.MAC -> {
                val found = ask("mdfind", "kMDItemCFBundleIdentifier == \"$MAC_BUNDLE_ID\"")
                when {
                    found != null && found.exitCode == 0 && found.output.isNotBlank() -> Presence.INSTALLED
                    listOf(Paths.get("/Applications/Converter Studio.app"), Paths.get(home, "Applications", "Converter Studio.app")).any { Files.isDirectory(it) } -> Presence.INSTALLED
                    found != null && found.exitCode == 0 -> Presence.NOT_FOUND
                    else -> Presence.UNKNOWN
                }
            }
            Os.WINDOWS -> {
                val keys = listOf("HKCU\\Software\\Classes\\$SCHEME", "HKLM\\Software\\Classes\\$SCHEME")
                // A Store (MSIX) install registers its links elsewhere: no answer here is not a "no".
                if (keys.any { ask("reg", "query", it)?.exitCode == 0 }) Presence.INSTALLED else Presence.UNKNOWN
            }
        }
    }
}
