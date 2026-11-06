package app.dshmobile.chat

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * A project (= a custom assistant, 0.5.0): the workspace folder `projects/<slug>/` with `AGENTS.md` (its 說明, which dsh reads
 * for every conversation started in that folder), `project.json` and the owner's reference files.
 */
data class Project(
    val slug: String,
    val name: String,
    val instructions: String,
    val assistant: Boolean,
    val template: String?,
    val createdAt: Long,
    val files: List<String> = emptyList(),
) {
    /** The big letter on its tile. */
    val initial: String get() = name.trim().take(1).ifEmpty { "專" }
}

data class ProjectTemplate(val id: String, val name: String, val instructions: String)

class ProjectStore(private val workspace: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val root get() = File(workspace, DIR)
    private val _projects = MutableStateFlow(load())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    fun refresh() { _projects.value = load() }

    fun get(slug: String): Project? = _projects.value.firstOrNull { it.slug == slug }

    /** A new project named [name] (a template's 說明 when [template] is given); a taken slug gets -2, -3… */
    @Synchronized fun create(name: String, template: ProjectTemplate? = null, assistant: Boolean = template != null): Project {
        val clean = name.trim().ifEmpty { template?.name ?: "新專案" }.take(40)
        val base = slugOf(clean)
        var slug = base
        var n = 2
        while (File(root, slug).exists()) slug = "$base-${n++}"
        val dir = File(root, slug).apply { mkdirs() }
        val p = Project(slug, clean, template?.instructions ?: "", assistant, template?.id, clock())
        write(dir, p)
        refresh()
        return get(slug) ?: p
    }

    fun update(slug: String, name: String? = null, instructions: String? = null, assistant: Boolean? = null) {
        val p = get(slug) ?: return
        val dir = File(root, slug).takeIf { it.isDirectory } ?: return
        write(dir, p.copy(name = name?.trim()?.ifEmpty { null } ?: p.name, instructions = instructions ?: p.instructions, assistant = assistant ?: p.assistant))
        refresh()
    }

    /** Copies a reference file into the project folder (a taken name gets a number); returns its name there. */
    fun addFile(slug: String, name: String, bytes: ByteArray): String? {
        val dir = File(root, slug).takeIf { it.isDirectory } ?: return null
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]+"), "_").trim().trimStart('.').ifEmpty { "檔案" }.take(80)
        if (safe in RESERVED) return null
        var target = File(dir, safe)
        var n = 2
        while (target.exists()) target = File(dir, "${safe.substringBeforeLast('.')}-$n${if ('.' in safe) "." + safe.substringAfterLast('.') else ""}").also { n++ }
        target.writeBytes(bytes)
        refresh()
        return target.name
    }

    fun removeFile(slug: String, name: String) {
        if (name in RESERVED || '/' in name || name.startsWith(".")) return
        File(File(root, slug), name).takeIf { it.isFile }?.delete()
        refresh()
    }

    /** Removes the project folder (its conversations stay in the list). */
    fun delete(slug: String) {
        if (!SLUG_OK.matches(slug)) return
        File(root, slug).deleteRecursively()
        refresh()
    }

    private fun write(dir: File, p: Project) {
        atomic(File(dir, AGENTS), agentsMd(p))
        atomic(File(dir, META), buildJsonObject {
            put("version", 1); put("name", p.name); put("assistant", p.assistant)
            p.template?.let { put("template", it) }
            put("createdAt", p.createdAt)
        }.toString())
    }

    private fun load(): List<Project> = runCatching {
        root.listFiles()?.filter { it.isDirectory && SLUG_OK.matches(it.name) }?.mapNotNull { dir ->
            val meta = runCatching { Json.parseToJsonElement(File(dir, META).readText()) as JsonObject }.getOrNull() ?: return@mapNotNull null
            fun s(k: String) = (meta[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            Project(
                slug = dir.name,
                name = s("name") ?: dir.name,
                instructions = instructionsOf(runCatching { File(dir, AGENTS).readText() }.getOrDefault("")),
                assistant = (meta["assistant"] as? JsonPrimitive)?.booleanOrNull ?: false,
                template = s("template"),
                createdAt = (meta["createdAt"] as? JsonPrimitive)?.longOrNull ?: 0,
                files = dir.listFiles()?.filter { it.isFile && it.name !in RESERVED && !it.name.startsWith(".") }?.map { it.name }?.sorted().orEmpty(),
            )
        }?.sortedByDescending { it.createdAt }.orEmpty()
    }.getOrDefault(emptyList())

    companion object {
        const val DIR = "projects"
        const val AGENTS = "AGENTS.md"
        const val META = "project.json"
        private val RESERVED = setOf(AGENTS, META)
        private val SLUG_OK = Regex("^[a-z0-9][a-z0-9-]{0,47}$")
        private const val MARK = "<!-- dshm-project: the owner's instructions follow -->"

        /** ASCII letters and digits of [name], else `p-` + a short hash (Chinese names); never `..` or a slash. */
        fun slugOf(name: String): String {
            val ascii = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(32)
            if (ascii.length >= 2) return ascii
            val hash = MessageDigest.getInstance("SHA-256").digest(name.toByteArray()).take(4).joinToString("") { "%02x".format(it) }
            return "p-$hash"
        }

        /** The dsh-side folder of a project, for `session/create` (both runtimes: [workspaceRoot] is DshLayout's). */
        fun cwdOf(workspaceRoot: String, slug: String) = workspaceRoot.trimEnd('/') + "/" + DIR + "/" + slug

        /** The project slug of a conversation folder, when it is one. */
        fun slugOfCwd(cwd: String?): String? = cwd?.substringAfter("/$DIR/", "")?.takeIf { SLUG_OK.matches(it) }

        fun agentsMd(p: Project): String = buildString {
            append("# 專案：").append(p.name).append("\n\n")
            append("這個資料夾是 DSH Mobile 的一個專案。這裡的檔案是主人給的參考資料，回答前先看需要的檔案。\n\n")
            append(MARK).append("\n")
            append(p.instructions.trim()).append("\n")
        }

        fun instructionsOf(md: String): String = if (MARK in md) md.substringAfter(MARK).trim() else md.trim()

        private fun atomic(file: File, text: String) {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }

        val templates = listOf(
            ProjectTemplate("stocks", "台股研究員", "我是長期投資的上班族。回答用繁體中文、條列；數字附來源和日期；用台灣的說法（漲跌、外資、投信、法說會）。不要給買賣建議，只給資訊和判斷依據。"),
            ProjectTemplate("novel", "小說助理", "你是我的小說寫作夥伴。記住這個專案裡的設定檔，人物、世界觀、文風前後一致；我說寫下一章時接著上一章寫，不重複情節。用繁體中文。"),
            ProjectTemplate("ledger", "記帳助理", "幫我記帳和整理支出。我說「午餐 120」就記一筆（日期、類別、金額、備註）；月底幫我做分類加總和跟上個月比較。金額用新台幣。"),
            ProjectTemplate("translate", "翻譯", "把我給的內容翻成通順的繁體中文（台灣用語）；我給中文就翻成自然的英文。專有名詞第一次出現時附原文。不要加解說，除非我問。"),
            ProjectTemplate("english", "英文老師", "你是有耐心的英文老師。我寫英文時先改錯（列出原句、改正、原因），再給一個更自然的說法；適時出一題小練習。解說用繁體中文。"),
        )
    }
}
