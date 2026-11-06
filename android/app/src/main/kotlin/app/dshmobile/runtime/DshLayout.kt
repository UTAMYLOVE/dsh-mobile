package app.dshmobile.runtime

import java.io.File

/**
 * How dsh runs and where it sees the app's folders. Under proot (the default) dsh lives in the Debian rootfs and sees
 * `/root/workspace`, `/root/.dsh` and `/opt/...`; in the experimental native mode the same dsh files (from the rootfs) run on
 * Android's own node, so dsh sees the host paths themselves. dsh records the workspace path in every conversation, so the two
 * modes keep separate conversation lists; switching back shows the other list again (nothing is moved or deleted).
 */
data class DshLayout(
    val native: Boolean,
    /** The workspace as dsh sees it: its cwd, `DSHM_WORKSPACE`, the registered workspace. */
    val workspace: String,
    /** dsh's home as dsh sees it (`DSH_HOME`). */
    val dshHome: String,
    /** The app's plugins (memory, notify, ui) as dsh sees them. */
    val pluginDir: String,
    /** The `--patch` file as dsh sees it (the host file is always rootfs/opt/dshm/dshm.cordis.yml). */
    val patchFile: String,
    /** dsh's entry point (`.../@deepseek-ai/dsh/lib/bin.js`) as dsh sees it. */
    val dshEntry: String,
    /** node and its flags (native: Android's node with the shim). */
    val node: List<String>,
    /** bash for dsh's terminal-bash shell, when dsh's default (/bin/bash) is not there. */
    val shellPath: String? = null,
) {
    /** The dsh command line: launcher flags (`--profile`, `--patch`) before the app flags (`--no-open`, `--host`, `--port`). */
    val args: List<String>
        get() = node + listOf(dshEntry, "--profile", "web", "--patch", patchFile, "--no-open", "--host", "127.0.0.1", "--port", "0")

    companion object {
        val PROOT = DshLayout(
            native = false,
            workspace = AppPaths.GUEST_WORKSPACE,
            dshHome = AppPaths.GUEST_DSH_HOME,
            pluginDir = "/opt/dshm/plugins",
            patchFile = "/opt/dshm/dshm.cordis.yml",
            dshEntry = "/opt/dsh/current/node_modules/@deepseek-ai/dsh/lib/bin.js",
            // The rootfs' node, without the proot loader's LD_LIBRARY_PATH.
            node = listOf("/usr/bin/env", "-u", "LD_LIBRARY_PATH", "node"),
        )

        /** Native: the host paths; [dshDir] is the active version's directory in the rootfs (rootfs/opt/dsh/<version>). */
        fun native(paths: AppPaths, dshDir: File, node: List<String>, shellPath: String) = DshLayout(
            native = true,
            workspace = paths.workspace.path,
            dshHome = paths.dshHome.path,
            pluginDir = File(paths.rootfs, "opt/dshm/plugins").path,
            patchFile = File(paths.rootfs, "opt/dshm/dshm.cordis.yml").path,
            dshEntry = File(dshDir, "node_modules/@deepseek-ai/dsh/lib/bin.js").path,
            node = node,
            shellPath = shellPath,
        )

        /**
         * The layout of the dsh that runs now (or ran last). Code that talks to dsh about paths (the workspace registration,
         * new conversations, the conversation list) reads it; the controller sets it before every start.
         */
        @Volatile
        var active: DshLayout = PROOT

        /**
         * Whether a conversation that runs in [cwd] belongs to the other mode: true = it was made in native mode (the host path)
         * and proot runs now, false = made under proot (/root/...) and native runs now, null = it belongs here (or is unknown).
         * dsh keeps a conversation's folder and cannot run it where that folder does not exist: it accepted the message and then
         * never answered, so the app stood at 「傳送中」 (owner, 2026-11-05). Both are the same folder on the phone, under two names.
         */
        fun otherModeOf(cwd: String?, layout: DshLayout = active): Boolean? {
            if (cwd.isNullOrEmpty()) return null
            val ws = layout.workspace.trimEnd('/')
            if (cwd == ws || cwd.startsWith("$ws/")) return null
            return when {
                layout.native && (cwd == AppPaths.GUEST_WORKSPACE || cwd.startsWith("/root/")) -> false
                // proot binds the workspace at its host path too (native mode is gone), so those conversations run here
                !layout.native && cwd.startsWith("/data/") -> null
                else -> null
            }
        }

        /** Every workspace prefix a tool path may start with, to show paths relative to the workspace in either mode. */
        fun workspacePrefixes(): List<String> = listOf(active.workspace.trimEnd('/') + "/", AppPaths.GUEST_WORKSPACE + "/").distinct()
    }
}
