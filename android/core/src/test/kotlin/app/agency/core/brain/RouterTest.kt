package app.agency.core.brain

import app.agency.core.model.RoleKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterTest {
    @Test fun commandWordsAreRecordsOfTheirRole() {
        assertEquals(Route.NotYet("water", RoleKey.BODY), Router.route("喝了半壺"))
        assertEquals(Route.NotYet("sign_in", RoleKey.MANAGER), Router.route("簽到。"))
        assertEquals(Route.NotYet("sign_out", RoleKey.MANAGER), Router.route("我要睡了"))
        assertEquals(Route.NotYet("training", RoleKey.BODY), Router.route("核心完成"))
        assertEquals(Route.NotYet("cheat_declare", RoleKey.MANAGER), Router.route("這週休假餐是明天晚餐"))
        assertEquals(Route.NotYet("rest_day", RoleKey.MANAGER), Router.route(" 今天休息 "))
    }

    @Test fun anythingElseGoesToTheModel() {
        assertEquals(Route.Model, Router.route("今天休息一下好不好，我好累"))
        assertEquals(Route.Model, Router.route("姐姐你好兇欸"))
        assertEquals(Route.Model, Router.route("我想簽到"))
    }

    @Test fun recordKindsAndRolesFollowArch13() {
        assertTrue(Router.isRecordKind("meal"))
        assertTrue(Router.isRecordKind("craving"))
        listOf("chat", "mood", "unwell", "discuss").forEach { assertFalse(it, Router.isRecordKind(it)) }
        listOf("meal", "craving", "weight", "label", "water", "training").forEach { assertEquals(it, RoleKey.BODY, Router.roleOf(it)) }
        listOf("sign_in", "sign_out", "condition", "rest_day", "cheat_declare", "hungry_morning", "chat").forEach {
            assertEquals(it, RoleKey.MANAGER, Router.roleOf(it))
        }
    }
}
