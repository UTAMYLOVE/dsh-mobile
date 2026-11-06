package app.dshmobile.ui.shell

/**
 * Test tags of the native interface; the end-to-end tests find elements by these only, never by Chinese text. The shell's root
 * sets `testTagsAsResourceId`, so UiAutomator sees them as resource ids too. Later tasks add their constants here. Prefixed
 * tags (ending in `-`) must never be a prefix of one another.
 */
object Tags {
    const val LIB_NEW = "lib-new"
    const val LIB_CARDS = "lib-cards"
    const val LIB_EMPTY = "lib-empty"
    const val LIB_BOOK = "lib-book-"
    const val LIB_NEW_PANEL = "lib-new-panel"
    const val LIB_TITLE = "lib-title"
    const val LIB_CREATE = "lib-create"
    const val LIB_BOOK_PAGE = "lib-volume-page"
    const val LIB_READ = "lib-read"
    const val LIB_NOT_CHAPTER = "lib-not-chapter-"
    const val LIB_READER = "lib-reader"
    const val LIB_FONT_DOWN = "lib-font-down"
    const val LIB_FONT_UP = "lib-font-up"
    const val LIB_NIGHT = "lib-night"
    const val LIB_TEXT = "lib-text"
    const val LIB_NEXT = "lib-next"
    const val LIB_WRITE_NEXT = "lib-write-next"
    const val LIB_CARDS_PAGE = "lib-cards-page"
    const val LIB_CARD_NEW = "lib-card-new"
    const val LIB_CARD_NAME = "lib-card-name"
    const val LIB_CARD_BODY = "lib-card-body"
    const val LIB_CARD_SAVE = "lib-card-save"
    const val CHAT_ADD_SHELF = "chat-add-shelf"
    const val CHAT_FILE = "chat-file-"
    const val MODAL_SCRIM = "modal-scrim"
    const val REPLY_STATS = "reply-stats"
    const val COPY_REPLY = "copy-reply-"
    const val MSG_MENU = "msg-menu"
    const val FOLLOWUP_CHIP = "followup-"
    const val MSG_COPY = "msg-copy"
    const val MSG_SPEAK = "msg-speak"
    const val MSG_EDIT = "msg-edit"
    const val MSG_REANSWER = "msg-reanswer"
    const val MSG_BRANCH = "msg-branch"
    const val EDIT_BANNER = "edit-banner"
    const val CHAT_STATS = "chat-stats"
    const val OVERVIEW_STATS = "overview-stats"
    const val WAR_FLOW = "war-flow"
    const val WAR_TICKER = "war-ticker"
    const val WAR_CORE = "war-core"
    const val WAR_CHART = "war-chart"
    const val WAR_STACK = "war-stack"
    const val OVERVIEW_WAR = "overview-war"
    const val WAR_TILES = "war-tiles"
    const val WAR_LOG = "war-log"
    const val WAR_CARD = "war-card-"
    const val WAR_DETAIL = "war-detail-"
    const val WAR_OPEN = "war-open-"
    const val WAR_LIST_TOGGLE = "war-list-toggle"
    const val CHAT_DATA = "chat-data"
    const val CHAT_ACTIVITY = "chat-activity"
    const val CHAT_DATA_PANEL = "chat-data-panel"
    const val CHAT_DATA_SCROLL = "chat-data-scroll"
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
    const val STATUS_SPINNER = "status-spinner"
    const val RETRY = "status-retry"
    const val EXPORT_LOG = "status-export-log"
    const val RECONNECTING = "reconnecting"
    const val LIBRARY_PLACEHOLDER = "library-placeholder"
    const val SHELL_BACKGROUND = "shell-background"
    /** The header (DSH MOBILE, time, page name and dots) and the page viewport below it. */
    const val SHELL_HEADER = "shell-header"
    const val SHELL_STAGE = "shell-stage"
    /** The thin line that sweeps across the page viewport once per tab change (depth transitions only). */
    const val PAGE_SWEEP = "page-sweep"

    /** Everything that floats over the pages at the bottom: the reconnecting chip, the attach menu and the dock itself. */
    const val DOCK = "dock"
    /** + [AttachmentChip.id]: one picked attachment above the input row. */
    const val ATTACHMENT_CHIP = "attachment-chip-"
    /** + [AttachmentChip.id]: its remove button. */
    const val ATTACHMENT_REMOVE = "attachment-remove-"
    /** Why the last attach or send of attachments failed, above the input row. */
    const val COMPOSER_ERROR = "composer-error"

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

    /** + child session id: the tappable text of an agent row (opens the parent conversation). */
    const val AGENT_ROW = "agent-row-"
    /** + child session id: its 「叫它停」 button. */
    const val AGENT_STOP = "agent-stop-"
    const val AGENTS_EMPTY = "agents-empty"

    const val OVERVIEW_RING = "overview-ring"
    const val OVERVIEW_SPLIT = "overview-split"
    const val OVERVIEW_AGENTS = "overview-agents"
    const val OVERVIEW_HEARTBEAT = "overview-heartbeat"
    const val OVERVIEW_REMINDERS = "overview-reminders"
    /** + [app.dshmobile.chat.SessionRow.id]: a recent conversation on the overview. */
    const val OVERVIEW_RECENT = "overview-recent-"
    const val UNVERIFIED_BANNER = "unverified-banner"

    /** + 0..2: the effect strength cells (full, balanced, saver). */
    const val EFFECT = "effect-"
    /** + model id: one choice of the model used for writing novels. */
    const val NOVEL_MODEL = "novel-model-"
    const val UNVERIFIED_WARNING = "unverified-warning"
    /** 「目前自動降為：…」 under the effect control. */
    const val EFFECT_LOWERED = "lowered-effect"
    /** 「未套用」 beside a lead model chosen but not applied yet. */
    const val LEAD_UNAPPLIED = "lead-unapplied"
    /** The glass card that asks before apply, update or rollback cut off a running conversation; its two buttons. */
    const val CONFIRM_CARD = "confirm-card"
    const val CONFIRM_WAIT = "confirm-wait"
    const val CONFIRM_GO = "confirm-go"
    const val VERIFIED_NOTE = "verified-note"
    /** The chat's 「重新啟動 dsh」 next to the writer-held refusal. */
    const val CHAT_RESTART_DSH = "chat-restart-dsh"
    /** Thinking strength options in the model menu: + "default" / off / low / high / max. */
    const val EFFORT_OPTION = "effort-option-"
    /** Reviewer-round options in the model menu: + 0..3. */
    const val REVIEW_OPTION = "review-option-"
    /** The line under the chat header summarising thinking strength and reviewer rounds. */
    const val CHAT_TUNING = "chat-tuning"
}
