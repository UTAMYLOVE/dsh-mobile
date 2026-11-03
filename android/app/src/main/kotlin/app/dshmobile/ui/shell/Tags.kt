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

    // ---- the chat page (Task 8) ----
    const val CHAT_LIST_BUTTON = "chat-list-button"
    const val CHAT_TITLE = "chat-title"
    const val MODEL_CHIP = "model-chip"
    /** + model id. */
    const val MODEL_OPTION = "model-option-"
    const val USER_BUBBLE = "user-bubble"
    const val ASSISTANT_BUBBLE = "assistant-bubble"
    const val LIVE_BUBBLE = "live-bubble"
    const val THINKING = "thinking"
    /** + callId. */
    const val TOOL_CARD = "tool-card-"
    /** + callId. */
    const val TOOL_STATUS = "tool-status-"
    const val SEARCH_CARD = "search-card"
    /** + the source's number, from 1. */
    const val SOURCE_CHIP = "source-chip-"
    const val CITES = "cites"
    const val QUEUED = "queued"
    const val NOTICE = "notice"
    const val LOAD_OLDER = "load-older"
    /** + callId. */
    const val SHARE = "share-"
    /** The scrolling message list of the chat page. */
    const val CHAT_MESSAGES = "chat-messages"
    /** The inline model menu under the chat header. */
    const val MODEL_MENU = "model-menu"
    /** The follow stream is down: the banner and its reconnect button. */
    const val FOLLOW_ERROR = "follow-error"
    const val FOLLOW_RETRY = "follow-retry"
    /** The last send, stop or model change failed. */
    const val CHAT_ERROR = "chat-error"
    /** Loading older messages failed. */
    const val OLDER_ERROR = "older-error"
    /** + requestId: a pending bubble dsh never recorded, sent again / removed. */
    const val SEND_RETRY = "send-retry-"
    const val SEND_DISMISS = "send-dismiss-"
    /** + requestId: a bubble that is waiting for dsh. */
    const val PENDING_BUBBLE = "pending-bubble-"
    /** The chat page's header panel (「☰ 對話」, title, model chip). */
    const val CHAT_HEADER = "chat-header"
    /** The ↓ pill: new messages arrived below while the list was scrolled up. */
    const val SCROLL_TO_NEWEST = "scroll-to-newest"
}
