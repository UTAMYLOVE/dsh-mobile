package app.dshmobile.wire

import android.app.Notification
import android.content.Intent
import app.agency.core.agent.tools.RulesDoc
import app.agency.core.base.Days
import app.dshmobile.BuildConfig
import app.agency.core.lessons.Lesson
import app.agency.core.lessons.LessonState as CoreState
import app.agency.core.plan.TaskId
import app.agency.core.rules.TrainingRule
import app.agency.core.store.Kind
import app.agency.core.store.Role
import app.agency.core.store.add
import app.dshmobile.platform.OldData
import app.dshmobile.platform.OldDb
import app.dshmobile.ui.LessonState
import app.dshmobile.wire.ui.LessonsAdapter
import app.dshmobile.wire.ui.OldDataAdapter
import app.dshmobile.wire.ui.SettingsPanelPrefs
import app.dshmobile.wire.ui.SingingAdapter
import app.dshmobile.wire.ui.TimerPlans
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Each door of the screens makes its main call reach the company (or the platform class behind it). Real wiring, invented data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AdaptersTest {
    private val rig = WireRig().also { it.onboarded() }
    private val ui get() = rig.wire.uiGraph
    private val store get() = rig.graph.store
    private val day get() = Days.today(rig.clock)

    @After fun tearDown() = rig.close()

    @Test fun memoAddDoneRemoveReachTheCompany() {
        ui.memo.add("買牛奶", null)
        val row = ui.memo.list().single()
        assertEquals("買牛奶", row.text)
        assertFalse(row.done)
        assertTrue(rig.wire.company.memos().any { it.text == "買牛奶" })
        ui.memo.setDone(row.id, true)
        assertTrue(ui.memo.list().isEmpty())
        ui.memo.add("寄信", "09:30")
        val second = ui.memo.list().single()
        assertNotNull(second.due)
        ui.memo.remove(second.id)
        assertTrue(ui.memo.list().isEmpty())
    }

    @Test fun moneyAddsThroughTheLogMoneyTool() {
        val line = ui.money.add(120, "LIVING", "午餐")
        assertTrue(line, line.contains("120"))
        assertEquals(120, rig.wire.company.moneyView().week.living)
        assertTrue(ui.money.summary().any { it.contains("120") })
    }

    @Test fun bodyLogsKgAndShowsItAgain() {
        val line = runBlocking { ui.body.log(68.4, null, null) }
        assertTrue(line, line.isNotBlank())
        assertTrue(ui.body.last().any { it.contains("68.4") })
        assertEquals(1, runBlocking { ui.body.log(null, null, null) }.lines().size) // nothing to log: a plain sentence, no crash
    }

    @Test fun theTimerPlanComesFromTheRulesAndFinishingWritesTheTask() {
        val plan = ui.timer.plan(TaskId.STRENGTH.name)
        assertTrue(plan.rounds >= 1)
        assertTrue(plan.moves.isNotEmpty())
        ui.timer.finish(TaskId.CORE.name, 1, 5, "OK", 0)
        assertEquals(1, store.entries(day, Kind.TASK).size)
        assertTrue(rig.wire.company.messages(5).any { it.role == Role.NOTE })
    }

    @Test fun timerPlansBuildFromTheTrainingRulesOnly() {
        val t = TrainingRule(coreMinutes = 4, strengthRounds = 3, pushUps = 10, squats = 20, plankSeconds = 30, stretchMinutes = 6)
        val strength = TimerPlans.of("STRENGTH", t)
        assertEquals(3, strength.rounds)
        assertEquals(listOf(30, 60, 30), strength.moves.map { it.seconds })
        val core = TimerPlans.of("CORE", t)
        assertEquals(2, core.rounds) // 240 seconds of 120-second rounds
        val stretch = TimerPlans.of("STRETCH", t)
        assertEquals(360, stretch.moves.single().seconds)
        val unset = TimerPlans.of("WALK", t)
        assertEquals(300, unset.moves.single().seconds) // a blank rule never makes an empty timer
    }

    @Test fun lessonsListApproveWatchAndComeBack() {
        val id = store.add(
            day, rig.clock.now(), Kind.LESSON,
            Lesson(subject = "語言", title = "測試影片", url = "https://example.com/watch?v=1", minutes = 8, state = CoreState.PROPOSED),
        ).id
        var now = 1_000_000L
        val lessons = LessonsAdapter(rig.app, rig.wire.company) { now }
        val proposed = lessons.lessons().single { it.id == id }
        assertEquals(LessonState.PROPOSED, proposed.state)
        lessons.approve(id, true)
        assertEquals(LessonState.APPROVED, lessons.lessons().single { it.id == id }.state)
        lessons.watch(id)
        val started: Intent = shadowOf(rig.app).nextStartedActivity
        assertEquals("https://example.com/watch?v=1", started.data.toString())
        now += 2 * 60_000
        lessons.cameBack()
        assertEquals(LessonState.WATCHED, lessons.lessons().single { it.id == id }.state)
        lessons.cameBack() // nothing is away any more
    }

    @Test fun aShortTripToTheVideoIsNotWatching() {
        val id = store.add(
            day, rig.clock.now(), Kind.LESSON,
            Lesson(subject = "語言", title = "短片", url = "https://example.com/v", state = CoreState.APPROVED),
        ).id
        var now = 5_000_000L
        val lessons = LessonsAdapter(rig.app, rig.wire.company) { now }
        lessons.watch(id)
        now += 10_000
        lessons.cameBack()
        assertEquals(LessonState.APPROVED, lessons.lessons().single { it.id == id }.state)
    }

    @Test fun anAddressThatIsNotWebIsNeverOpened() {
        val id = store.add(
            day, rig.clock.now(), Kind.LESSON,
            Lesson(subject = "語言", title = "怪連結", url = "file:///etc/passwd", state = CoreState.APPROVED),
        ).id
        LessonsAdapter(rig.app, rig.wire.company).watch(id)
        assertNull(shadowOf(rig.app).nextStartedActivity)
    }

    @Test fun traineeCardShowsTheStageNameAndTheDiary() {
        val card = ui.trainee.card()
        assertEquals("STAR", card.stageName)
        assertEquals("#88AAFF", card.colorHex)
        assertTrue(card.diary.isNotEmpty())
        assertEquals("C", card.level)
    }

    @Test fun settingsRulesSaveOnlyWhenEveryFieldIsAccepted() {
        val s = ui.settings
        assertTrue(s.rulesGroups().isNotEmpty())
        val bad = s.saveRules(mapOf("callName" to "小明", "memberColor" to "紅色"))
        assertNotNull(bad)
        assertEquals("阿星", rig.wire.company.rules().callName) // nothing was applied
        assertNull(s.saveRules(mapOf("callName" to "小明")))
        assertEquals("小明", rig.wire.company.rules().callName)
        assertEquals("他喜歡簡單直接的說法。", RulesDoc.handbook(store)) // the handbook is kept
        assertNotNull(s.saveRules(mapOf("nope" to "1")))
    }

    @Test fun settingsModelCapKeysAndSpend() {
        val s = ui.settings
        s.setModel(true, "smart")
        assertEquals(true, s.model().useDeepSeekForChat)
        assertEquals("smart", s.model().tier)
        s.setModel(false, "cheap")
        assertEquals("cheap", s.model().tier)
        assertFalse(s.model().claudeKeySet)
        assertTrue(runBlocking { s.saveKey("claude", WireRig.CLAUDE_KEY) })
        assertTrue(runBlocking { s.saveKey("deepseek", WireRig.DEEPSEEK_KEY) })
        assertFalse(runBlocking { s.saveKey("claude", "密碼") })
        assertTrue(s.model().claudeKeySet && s.model().deepSeekKeySet)
        s.setCap(300)
        assertEquals(300, s.spend().capYuan)
        s.setCap(null)
        assertNull(s.spend().capYuan)
        assertTrue(s.licenses().any { it.text.isNotBlank() })
        assertEquals(BuildConfig.VERSION_NAME, s.update().version)
        s.testNotification()
        // The wake handler may post its own note meanwhile (it runs on another thread), so look for the test one by its title.
        assertEquals(1, rig.notifications.count { it.extras.getString(Notification.EXTRA_TITLE) == "測試通知" })
    }

    @Test fun panelsFollowTheSettingsDocument() {
        val p = SettingsPanelPrefs(rig.graph.settings)
        assertTrue(p.get("stickers", true))
        p.set("stickers", false)
        assertFalse(p.get("stickers", true))
        assertTrue(SettingsPanelPrefs(rig.graph.settings).get("chips", true))
    }

    @Test fun aRecordedSongShowsInTheHistory() {
        val rate = 16_000
        val pcm = ShortArray(rate * 3) { i -> (Math.sin(2 * Math.PI * 220 * i / rate) * 8000).toInt().toShort() }
        val r = rig.wire.company.recordSong(pcm, rate, "練習")
        val history = ui.singing.history()
        assertEquals(1, history.size)
        assertEquals(r.rec.inTunePct, history.single().inTunePct)
        val shown = SingingAdapter.map(r.rec, r.card.lines)
        assertTrue(shown.comment.isNotBlank())
    }

    @Test fun theEvaluationPageListsWhatToHandInAndSaysWhyANoIsNo() {
        val v = ui.eval.view(monthly = false)
        assertEquals(listOf("song", "dance"), v.items.map { it.key })
        assertTrue(v.items.none { it.recorded })
        assertFalse(v.confirmed)
        ui.eval.confirm(false, mapOf("song" to 4)) // nothing handed in yet: refused, and the page title says so once
        assertTrue(ui.eval.view(false).title.contains("（"))
        assertFalse(ui.eval.view(false).title.contains("（"))
        assertEquals(listOf("song", "dance", "rotating", "body"), ui.eval.view(true).items.map { it.key })
    }

    @Test fun theOldChatIsListedOldestFirstAndCanBeDeleted() {
        val root = File(rig.app.noBackupFilesDir, "old-test").also { it.mkdirs() }
        File(root, OldData.OLD_DIR).mkdirs()
        File(File(root, OldData.OLD_DIR), OldData.OLD_DB).writeText("x")
        val rows = listOf(Triple("a", 1_000L, "OWNER" to "你好"), Triple("b", 2_000L, "COMPANY" to "早安"))
        val fake = object : OldDb {
            override fun query(sql: String, args: Array<String>): android.database.Cursor =
                if (sql.startsWith("SELECT COUNT")) android.database.MatrixCursor(arrayOf("c")).also { it.addRow(arrayOf<Any>(rows.size)) }
                else android.database.MatrixCursor(arrayOf("id", "at", "who", "text")).also { c ->
                    if (args.size == 1) rows.reversed().forEach { (id, at, p) -> c.addRow(arrayOf<Any>(id, at, p.first, p.second)) }
                }

            override fun close() = Unit
        }
        val adapter = OldDataAdapter({ OldData(root) { fake } }) { rig.clock }
        assertTrue(adapter.available())
        assertEquals(listOf("你好", "早安"), adapter.messages().map { it.text })
        assertEquals("你", adapter.messages().first().who)
        assertTrue(adapter.delete())
        assertFalse(adapter.available())
    }
}
