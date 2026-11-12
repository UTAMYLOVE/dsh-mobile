package app.dshmobile.ui.shell

/**
 * The shell's pages, in dock order. Labels: Chinese on top, English below. Since 0.6.0 the dock shows only [SIMPLE] (the owner:
 * 「我只需要一個聰明聊天」, the war room stays); the others appear with 「顯示更多功能」 and Settings opens from the chat's menus.
 */
enum class ShellTab(val zh: String, val en: String) {
    OVERVIEW("總覽", "OVERVIEW"),
    CHAT("對話", "CHAT"),
    AGENTS("指揮室", "WAR ROOM"),
    LIBRARY("書架", "LIBRARY"),
    SETTINGS("設定", "SETTINGS"),
    ;

    companion object {
        val SIMPLE = listOf(CHAT, AGENTS)
    }

    /** Pages that show dsh's data: while dsh is not ready they show the status panel instead. */
    val needsDsh: Boolean get() = this == CHAT || this == AGENTS
}
