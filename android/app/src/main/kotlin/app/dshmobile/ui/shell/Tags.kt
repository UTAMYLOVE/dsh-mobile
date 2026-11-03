package app.dshmobile.ui.shell

/**
 * Test tags of the native interface; the end-to-end tests find elements by these only, never by Chinese text. The shell's root
 * sets `testTagsAsResourceId`, so UiAutomator sees them as resource ids too. Later tasks add their constants here. Prefixed
 * tags (ending in `-`) must never be a prefix of one another.
 */
object Tags {
    /** + [ShellTab.name]. */
    const val TAB = "tab-"
    const val COMPOSER_INPUT = "composer-input"
    const val SEND = "composer-send"
    const val STOP = "composer-stop"
    const val ATTACH = "composer-attach"
    const val ATTACH_IMAGE = "attach-image"
    const val ATTACH_FILE = "attach-file"
    const val COMPOSER_HINT = "composer-hint"
    const val STATUS_PANEL = "status-panel"
    const val RETRY = "status-retry"
    const val EXPORT_LOG = "status-export-log"
    const val RECONNECTING = "reconnecting"
    const val LIBRARY_PLACEHOLDER = "library-placeholder"
    const val SHELL_BACKGROUND = "shell-background"

    /** Everything that floats over the pages at the bottom: the reconnecting chip, the attach menu and the dock itself. */
    const val DOCK = "dock"
    /** + [AttachmentChip.id]: one picked attachment above the input row. */
    const val ATTACHMENT_CHIP = "attachment-chip-"
    /** + [AttachmentChip.id]: its remove button. */
    const val ATTACHMENT_REMOVE = "attachment-remove-"
}
