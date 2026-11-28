package app.dshmobile.wire

import app.agency.core.agent.Company
import app.agency.core.base.Clock
import app.agency.core.remind.ReminderLog
import app.agency.core.store.Role
import app.agency.core.store.Store
import app.agency.core.store.readDoc
import app.agency.core.store.writeDoc
import app.dshmobile.platform.Notifier
import app.dshmobile.platform.alarm.AlarmScheduler
import app.dshmobile.platform.alarm.WakeHandler
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * What an alarm, a reboot, a clock change, the app coming to the front and every sentence or tap does: the company closes the
 * days that ended ([Company.rollover]), decides what is due ([Company.reminders]), the notices are posted (loud or quiet), the
 * reminder log is kept (doc [LOG_DOC]) and the next exact alarm is armed at `nextWakeAt`. Nothing due: nothing is posted, the
 * alarm is still armed. A NOTE line the rollover itself wrote (a stage opened, a photocard, the weekly result) raises one quiet
 * notification when no screen is in front. Before onboarding (no rules yet) it does nothing at all. Runs one at a time.
 *
 * If something throws half way, a retry alarm is armed anyway ([RETRY]), so a bad moment never leaves the app without an alarm.
 */
class CompanyWake(
    private val company: () -> Company,
    private val store: () -> Store,
    private val notifier: Notifier,
    private val alarms: AlarmScheduler,
    private val clock: () -> Clock,
    private val foreground: () -> Boolean,
    private val noteTitle: () -> String,
    private val log: (String) -> Unit = {},
) : WakeHandler {
    private val mutex = Mutex()

    override suspend fun onWake() {
        mutex.withLock { withContext(Dispatchers.IO) { run() } }
    }

    private fun run() {
        val c = company()
        if (c.rules().callName.isBlank()) return
        var armed = false
        try {
            val lastId = c.messages(1).lastOrNull()?.id ?: 0L
            c.rollover()
            val fresh = c.messages(12).filter { it.id > lastId && it.role == Role.NOTE }
            if (fresh.isNotEmpty() && !foreground()) {
                notifier.post(NOTE_KEY, noteTitle(), fresh.last().text.lineSequence().firstOrNull().orEmpty().take(80), loud = false)
            }
            val d = c.reminders(store().readDoc<ReminderLog>(LOG_DOC) ?: ReminderLog())
            for (n in d.notices) notifier.post(n.key, n.title, n.text, n.loud)
            store().writeDoc(LOG_DOC, d.log)
            alarms.arm(d.nextWakeAt)
            armed = true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log("wake step failed (${e.javaClass.simpleName})")
        } finally {
            if (!armed) runCatching { alarms.arm(clock().now().plus(RETRY)) }
        }
    }

    companion object {
        /** The doc that keeps what was already announced today (a [ReminderLog]). */
        const val LOG_DOC = "reminder_log"
        const val NOTE_KEY = "note"
        val RETRY: Duration = Duration.ofMinutes(30)
    }
}
