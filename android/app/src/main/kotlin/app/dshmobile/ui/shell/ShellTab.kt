package app.dshmobile.ui.shell

/** The shell's five pages, in dock order (the mockup's page 0 is the overview). Labels: Chinese on top, English below. */
enum class ShellTab(val zh: String, val en: String) {
    OVERVIEW("總覽", "OVERVIEW"),
    CHAT("對話", "CHAT"),
    AGENTS("代理人", "AGENTS"),
    LIBRARY("書架", "LIBRARY"),
    SETTINGS("設定", "SETTINGS"),
    ;

    /** Pages that show dsh's data: while dsh is not ready they show the status panel instead. */
    val needsDsh: Boolean get() = this == CHAT || this == AGENTS
}
