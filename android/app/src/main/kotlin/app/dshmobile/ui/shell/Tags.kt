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
    /** The header (DSH MOBILE, time, page name and dots) and the page viewport below it. */
    const val SHELL_HEADER = "shell-header"
    const val SHELL_STAGE = "shell-stage"

    /** Everything that floats over the pages at the bottom: the reconnecting chip, the attach menu and the dock itself. */
    const val DOCK = "dock"
    /** + [AttachmentChip.id]: one picked attachment above the input row. */
    const val ATTACHMENT_CHIP = "attachment-chip-"
    /** + [AttachmentChip.id]: its remove button. */
    const val ATTACHMENT_REMOVE = "attachment-remove-"

    /** + [app.dshmobile.chat.SessionRow.id]: the tappable part of a conversation row. */
    const val SESSION_ROW = "session-row-"
    /** + id: the row's menu button. */
    const val SESSION_MENU = "session-menu-"
    /** + [app.dshmobile.chat.DayGroup.name]: a group heading. */
    const val SESSION_GROUP = "session-group-"
    const val NEW_SESSION = "new-session"
    const val SESSION_SEARCH = "session-search"
    const val SESSION_LIST = "session-list"
    const val SESSION_CLOSE = "session-close"
    const val RENAME = "rename"
    const val RENAME_FIELD = "rename-field"
    const val RENAME_SAVE = "rename-save"
    const val RENAME_CANCEL = "rename-cancel"
    const val DELETE = "delete"
    const val DELETE_CONFIRM = "delete-confirm"
    const val DELETE_CANCEL = "delete-cancel"

    const val PROMPT_SHEET = "prompt-sheet"
    /** The scrolling part of the sheet (the long reason or the questions); the buttons stay below it. */
    const val PROMPT_SCROLL = "prompt-scroll"
    const val PROMPT_MORE = "prompt-more"
    const val APPROVE_ONCE = "approve-once"
    const val REJECT = "reject"
    /** + question id + "-" + option index. */
    const val OPTION = "option-"
    const val QUESTION_SUBMIT = "question-submit"
    const val QUESTION_DECLINE = "question-decline"
    const val PROMPT_ERROR = "prompt-error"
    const val PROMPT_CONNECTING = "prompt-connecting"
}
