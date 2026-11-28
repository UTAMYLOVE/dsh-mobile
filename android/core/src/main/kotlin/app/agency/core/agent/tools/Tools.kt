package app.agency.core.agent.tools

import app.agency.core.agent.Tool
import app.agency.core.agent.ToolOut

/**
 * Every tool 小葵 has. All are offered at every stage: a tool of a closed feature still records a voluntary report but says
 * in its facts that it does not count yet, and refuses anything that would schedule it (a timer, a screen).
 */
object Tools {
    fun all(): List<Tool> = listOf(
        CheckInTool(), CheckOutTool(),
        LogMealTool(), FixMealTool(), AttachPhotoTool(), RemoveEntryTool(),
        LogWaterTool(), LogDrinkTool(), RideOutTool(), RideBackTool(),
        LogTaskTool(), OpenTimerTool(), HabitDoneTool(), SetStatusTool(),
        LogBodyTool(), LogMoodTool(), LogMoneyTool(),
        MemoAddTool(), MemoListTool(), MemoDoneTool(), MemoRemoveTool(),
        RememberTool(), ForgetTool(), NoteTodayTool(),
        FindVideosTool(), AddVideoTool(), FindPlacesTool(), LessonWishTool(), LessonsTodayTool(),
        ProposeTool(), GetDayTool(), GetWeekTool(), ReadRulesTool(),
        RealTaskTool(), EvalStatusTool(), SongSummaryTool(), OpenScreenTool(),
        ImportPreviewTool(), UndoImportTool(),
    )

    private val byName: Map<String, Tool> by lazy { all().associateBy { it.spec.name } }

    /** A tool that may run from a tap without a model call, or null. */
    fun direct(name: String): Tool? = byName[name]?.takeIf { it.direct }

    /** Any tool by name. */
    fun named(name: String): Tool? = byName[name]

    /** The NOTE line a tap leaves in the conversation. */
    fun noteLine(tool: Tool, out: ToolOut): String =
        (tool as? Noted)?.noteLine(out) ?: if (out.ok) {
            "✓ " + ((out.facts["note"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "已記")
        } else "沒記到"
}
