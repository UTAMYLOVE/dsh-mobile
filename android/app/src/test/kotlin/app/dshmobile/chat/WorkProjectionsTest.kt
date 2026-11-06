package app.dshmobile.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkProjectionsTest {
    private fun p(json: String): Map<String, JsonElement> = mapOf("todos" to Json.parseToJsonElement(json))

    @Test fun todosAndPlanParse() {
        val todos = WorkProjections.todos(p("""[{"content":"抓資料","status":"completed"},{"content":"整理","status":"in_progress"},{"content":"寫報告","status":"pending"}]"""))
        assertEquals(listOf(Todo("抓資料", TodoStatus.DONE), Todo("整理", TodoStatus.IN_PROGRESS), Todo("寫報告", TodoStatus.PENDING)), todos)
        assertNull(WorkProjections.todos(mapOf("todos" to JsonNull)))
        assertNull(WorkProjections.todos(emptyMap()))
        assertEquals(PlanState(active = true, pending = false), WorkProjections.plan(mapOf("plan" to Json.parseToJsonElement("""{"active":true,"pending":false}"""))))
        assertNull(WorkProjections.plan(emptyMap()))
    }
}
