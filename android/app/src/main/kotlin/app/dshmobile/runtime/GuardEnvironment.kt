package app.dshmobile.runtime

import java.io.File

/**
 * The environment variables the app gives the guard plugin (plugins/packages/guard) when it starts dsh. They are internal, not
 * settings: the owner's 權限 level and Auto review switch, and the app's folders under every name dsh may use for them. One
 * pure function, so a test can check what reaches dsh.
 */
object GuardEnvironment {
    /**
     * [level] and the owner's Auto review switch [autoReviewSwitch]; [native] is the layout dsh starts in. [nativeOwn] are the
     * app's own folders the guard never lets go in native mode (the rootfs, the native runtime). [workspaceNames] are the host
     * spellings of the workspace (see [hostNames]); under proot each is read as /root/workspace, because proot binds the
     * workspace at each of them.
     */
    fun of(level: PermissionLevel, autoReviewSwitch: Boolean, native: Boolean, nativeOwn: List<File>, workspaceNames: List<String>): Map<String, String> {
        // DSHM_AUTO_REVIEW lets the guard put a conversation dsh runs on its own (a reminder, a helper) on the same preset
        // ShellSession gives the ones the owner sends to: never "auto" at 全部放行 (PermissionLevel.autoReviews).
        val common = mapOf("DSHM_GUARD_LEVEL" to level.id, "DSHM_AUTO_REVIEW" to if (level.autoReviews(autoReviewSwitch)) "1" else "0")
        if (native) return common + ("DSHM_GUARD_PROTECTED" to nativeOwn.joinToString(":") { it.path })
        val aliases = workspaceNames.distinct().filter { it != AppPaths.GUEST_WORKSPACE }.joinToString(";") { "$it=${AppPaths.GUEST_WORKSPACE}" }
        return common + ("DSHM_GUARD_PROTECTED" to "/opt/dsh:/opt/dshm") + (if (aliases.isEmpty()) emptyMap() else mapOf("DSHM_GUARD_ALIASES" to aliases))
    }

    /**
     * The workspace's host path as the app names it and with its links resolved. On Android /data/user/0 is a link to
     * /data/data, and dsh records and prints the resolved name (a conversation's folder, a tool's path), so the model also
     * uses that one.
     */
    fun hostNames(workspace: File): List<String> =
        listOfNotNull(workspace.absolutePath, runCatching { workspace.canonicalPath }.getOrNull()).distinct()
}
