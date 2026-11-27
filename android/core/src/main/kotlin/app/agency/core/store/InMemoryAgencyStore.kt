package app.agency.core.store

import app.agency.core.model.AuditLog
import app.agency.core.model.Baseline
import app.agency.core.model.CompanyRules
import app.agency.core.model.ConditionLog
import app.agency.core.model.DayOverride
import app.agency.core.model.DayRecord
import app.agency.core.model.EffectStatus
import app.agency.core.model.NerveEffect
import app.agency.core.model.StoredSignal
import app.agency.core.model.StoredThinking
import app.agency.core.model.FavoritePlace
import app.agency.core.model.Lesson
import app.agency.core.model.LessonStatus
import app.agency.core.model.Quiz
import app.agency.core.model.FoodEntry
import app.agency.core.model.Meal
import app.agency.core.model.MealOrder
import app.agency.core.model.Media
import app.agency.core.model.MemoryEvent
import app.agency.core.model.MemoryItem
import app.agency.core.model.Message
import app.agency.core.model.MigrationLog
import app.agency.core.model.Nudge
import app.agency.core.model.OrderStatus
import app.agency.core.model.PendingLabel
import app.agency.core.model.PlanSection
import app.agency.core.model.PlanSectionKey
import app.agency.core.model.Profile
import app.agency.core.model.Proposal
import app.agency.core.model.ProposalStatus
import app.agency.core.model.SearchKind
import app.agency.core.model.SearchResult
import app.agency.core.model.SignLog
import app.agency.core.model.TrainingLog
import app.agency.core.model.WaterLog
import app.agency.core.model.WeightLog

/**
 * [AgencyStore] in memory, for tests (and a desktop build later). Every call is atomic; [transaction] restores a snapshot when
 * its block throws. Concurrent writers outside a transaction are not isolated from one inside it (the brain is one queue).
 */
class InMemoryAgencyStore : AgencyStore {
    private class Tables(
        var profile: Profile? = null,
        val rules: MutableList<CompanyRules> = mutableListOf(),
        val sections: MutableList<PlanSection> = mutableListOf(),
        val days: MutableMap<String, DayOverride> = mutableMapOf(),
        val meals: MutableMap<String, Meal> = mutableMapOf(),
        val pending: MutableMap<Pair<String, Int>, PendingLabel> = mutableMapOf(),
        val food: MutableMap<String, FoodEntry> = mutableMapOf(),
        val orders: MutableMap<String, MealOrder> = mutableMapOf(),
        val favorites: MutableMap<String, FavoritePlace> = mutableMapOf(),
        val results: MutableMap<String, SearchResult> = mutableMapOf(),
        val water: MutableMap<String, WaterLog> = mutableMapOf(),
        val training: MutableMap<String, TrainingLog> = mutableMapOf(),
        val signs: MutableMap<String, SignLog> = mutableMapOf(),
        val weights: MutableMap<String, WeightLog> = mutableMapOf(),
        val conditions: MutableMap<String, ConditionLog> = mutableMapOf(),
        val baselines: MutableMap<String, Baseline> = mutableMapOf(),
        val media: MutableMap<String, Media> = mutableMapOf(),
        val messages: MutableMap<String, Message> = mutableMapOf(),
        val proposals: MutableMap<String, Proposal> = mutableMapOf(),
        val memoryItems: MutableMap<String, MemoryItem> = mutableMapOf(),
        val memoryEvents: MutableMap<String, MemoryEvent> = mutableMapOf(),
        val nudges: MutableMap<String, Nudge> = mutableMapOf(),
        val migrations: MutableList<MigrationLog> = mutableListOf(),
        val audits: MutableList<AuditLog> = mutableListOf(),
        val signals: MutableMap<String, StoredSignal> = mutableMapOf(),
        val effects: MutableMap<String, NerveEffect> = mutableMapOf(),
        val thinking: MutableMap<String, StoredThinking> = mutableMapOf(),
        val lessons: MutableMap<String, Lesson> = mutableMapOf(),
        val quizzes: MutableMap<String, Quiz> = mutableMapOf(),
        val income: MutableMap<String, app.agency.core.model.DeliveryIncome> = mutableMapOf(),
    ) {
        /** The records are immutable, so copying the containers is a full snapshot. */
        fun snapshot() = Tables(
            profile, rules.toMutableList(), sections.toMutableList(), days.toMutableMap(), meals.toMutableMap(),
            pending.toMutableMap(), food.toMutableMap(), orders.toMutableMap(), favorites.toMutableMap(), results.toMutableMap(),
            water.toMutableMap(), training.toMutableMap(), signs.toMutableMap(), weights.toMutableMap(), conditions.toMutableMap(),
            baselines.toMutableMap(), media.toMutableMap(), messages.toMutableMap(), proposals.toMutableMap(),
            memoryItems.toMutableMap(), memoryEvents.toMutableMap(), nudges.toMutableMap(), migrations.toMutableList(),
            audits.toMutableList(), signals.toMutableMap(), effects.toMutableMap(), thinking.toMutableMap(), lessons.toMutableMap(),
            quizzes.toMutableMap(), income.toMutableMap(),
        )
    }

    private val lock = Any()
    private var t = Tables()
    private var depth = 0

    private fun <R> locked(body: Tables.() -> R): R = synchronized(lock) { t.body() }

    override suspend fun <T> transaction(block: suspend () -> T): T {
        val before = synchronized(lock) { if (depth++ == 0) t.snapshot() else null }
        try {
            return block()
        } catch (e: Throwable) {
            if (before != null) synchronized(lock) { t = before }
            throw e
        } finally {
            synchronized(lock) { depth-- }
        }
    }

    private fun <T : DayRecord> Collection<T>.ordered() = sortedWith(compareBy<T>({ it.at }, { it.id }))

    private open inner class DayLog<T : DayRecord>(private val table: Tables.() -> MutableMap<String, T>) : DayLogRepo<T> {
        override suspend fun insert(record: T): Boolean = locked { table().putIfAbsent(record.id, record) == null }
        override suspend fun upsert(record: T) = locked { table()[record.id] = record }
        override suspend fun get(id: String): T? = locked { table()[id] }
        override suspend fun onDay(day: String): List<T> = locked { table().values.filter { it.day == day }.ordered() }
        override suspend fun between(fromDay: String, toDay: String): List<T> =
            locked { table().values.filter { it.day in fromDay..toDay }.ordered() }
        override suspend fun delete(id: String): Boolean = locked { table().remove(id) != null }
        override suspend fun count(): Int = locked { table().size }
    }

    override val profile = object : ProfileRepo {
        override suspend fun get(): Profile? = locked { profile }
        override suspend fun put(profile: Profile) = locked { this.profile = profile }
    }

    override val rules = object : RulesRepo {
        override suspend fun current(): CompanyRules? = locked { rules.lastOrNull() }
        override suspend fun put(rules: CompanyRules, at: Long): CompanyRules = locked {
            rules.copy(version = (this.rules.lastOrNull()?.version ?: 0) + 1, updatedAt = at).also { this.rules += it }
        }
        override suspend fun history(): List<CompanyRules> = locked { rules.toList() }
        override suspend fun section(key: PlanSectionKey): PlanSection? = locked { sections.lastOrNull { it.section == key } }
        override suspend fun sections(): List<PlanSection> =
            locked { PlanSectionKey.entries.mapNotNull { k -> sections.lastOrNull { it.section == k } } }
        override suspend fun putSection(key: PlanSectionKey, json: String, changedBy: String, at: Long): PlanSection = locked {
            val version = (sections.lastOrNull { it.section == key }?.version ?: 0) + 1
            PlanSection(key, json, version, at, changedBy).also { sections += it }
        }
        override suspend fun sectionHistory(key: PlanSectionKey): List<PlanSection> = locked { sections.filter { it.section == key } }
    }

    override val days = object : DayOverrideRepo {
        override suspend fun get(day: String): DayOverride? = locked { days[day] }
        override suspend fun put(override: DayOverride) = locked { days[override.day] = override }
        override suspend fun delete(day: String): Boolean = locked { days.remove(day) != null }
        override suspend fun between(fromDay: String, toDay: String): List<DayOverride> =
            locked { days.values.filter { it.day in fromDay..toDay }.sortedBy { it.day } }
    }

    override val meals: MealRepo = object : DayLog<Meal>({ meals }), MealRepo {
        override suspend fun delete(id: String): Boolean = locked {
            pending.keys.removeAll { it.first == id }
            meals.remove(id) != null
        }
        override suspend fun putPending(label: PendingLabel) = locked { pending[label.mealId to label.itemIdx] = label }
        override suspend fun pending(): List<PendingLabel> =
            locked { pending.values.sortedWith(compareBy({ it.askedAt }, { it.mealId }, { it.itemIdx })) }
        override suspend fun deletePending(mealId: String, itemIdx: Int): Boolean = locked { pending.remove(mealId to itemIdx) != null }
        override suspend fun countItems(): Int = locked { meals.values.sumOf { it.items.size } }
    }

    override val food = object : FoodRepo {
        override suspend fun get(name: String): FoodEntry? = locked { food[name] }
        override suspend fun all(): List<FoodEntry> = locked { food.values.sortedBy { it.name } }
        override suspend fun insert(entry: FoodEntry): Boolean = locked { food.putIfAbsent(entry.name, entry) == null }
        override suspend fun upsert(entry: FoodEntry) = locked { food[entry.name] = entry }
        override suspend fun delete(name: String): Boolean = locked { food.remove(name) != null }
        override suspend fun count(): Int = locked { food.size }
    }

    override val orders: OrderRepo = object : DayLog<MealOrder>({ orders }), OrderRepo {
        override suspend fun setStatus(id: String, status: OrderStatus): Boolean = locked {
            val o = orders[id] ?: return@locked false
            orders[id] = o.copy(status = status)
            true
        }
    }

    override val places = object : PlaceRepo {
        override suspend fun insertFavorite(place: FavoritePlace): Boolean = locked { favorites.putIfAbsent(place.id, place) == null }
        override suspend fun favorites(): List<FavoritePlace> =
            locked { favorites.values.sortedWith(compareBy({ it.savedAt }, { it.id })) }
        override suspend fun deleteFavorite(id: String): Boolean = locked { favorites.remove(id) != null }
        override suspend fun putResult(result: SearchResult) = locked { results[result.id] = result }
        override suspend fun result(id: String): SearchResult? = locked { results[id] }
        override suspend fun results(kind: SearchKind): List<SearchResult> =
            locked { results.values.filter { it.kind == kind }.sortedWith(compareBy({ it.fetchedAt }, { it.id })) }
        override suspend fun pruneResults(cutoff: Long, keep: Set<String>): Int = locked {
            val gone = results.values.filter { it.fetchedAt < cutoff && it.id !in keep }.map { it.id }
            gone.forEach { results.remove(it) }
            gone.size
        }
    }

    override val water: DayLogRepo<WaterLog> = DayLog { water }
    override val training: DayLogRepo<TrainingLog> = DayLog { training }
    override val signs: DayLogRepo<SignLog> = DayLog { signs }
    override val weights: DayLogRepo<WeightLog> = DayLog { weights }

    override val income = object : IncomeRepo {
        override suspend fun put(income: app.agency.core.model.DeliveryIncome) = locked { this.income[income.day] = income }
        override suspend fun get(day: String): app.agency.core.model.DeliveryIncome? = locked { income[day] }
        override suspend fun between(fromDay: String, toDay: String): List<app.agency.core.model.DeliveryIncome> =
            locked { income.values.filter { it.day in fromDay..toDay }.sortedBy { it.day } }
    }

    override val conditions = object : ConditionRepo {
        override suspend fun put(log: ConditionLog) = locked { conditions[log.day] = log }
        override suspend fun get(day: String): ConditionLog? = locked { conditions[day] }
        override suspend fun between(fromDay: String, toDay: String): List<ConditionLog> =
            locked { conditions.values.filter { it.day in fromDay..toDay }.sortedBy { it.day } }
        override suspend fun count(): Int = locked { conditions.size }
    }

    override val baselines = object : BaselineRepo {
        override suspend fun get(id: String): Baseline? = locked { baselines[id] }
        override suspend fun insert(baseline: Baseline): Boolean = locked { baselines.putIfAbsent(baseline.id, baseline) == null }
        override suspend fun upsert(baseline: Baseline) = locked { baselines[baseline.id] = baseline }
        override suspend fun all(): List<Baseline> = locked { baselines.values.sortedBy { it.id } }
        override suspend fun count(): Int = locked { baselines.size }
    }

    override val media = object : MediaRepo {
        override suspend fun insert(media: Media): Boolean = locked { this.media.putIfAbsent(media.id, media) == null }
        override suspend fun get(id: String): Media? = locked { media[id] }
        override suspend fun delete(id: String): Boolean = locked { media.remove(id) != null }
        override suspend fun all(): List<Media> = locked { media.values.sortedWith(compareBy({ it.createdAt }, { it.id })) }
        override suspend fun count(): Int = locked { media.size }
    }

    private fun Collection<Message>.byTime() = sortedWith(compareBy({ it.at }, { it.id }))

    override val messages = object : MessageRepo {
        override suspend fun upsert(message: Message) = locked { messages[message.id] = message }
        override suspend fun insert(message: Message): Boolean = locked { messages.putIfAbsent(message.id, message) == null }
        override suspend fun get(id: String): Message? = locked { messages[id] }
        override suspend fun recent(limit: Int): List<Message> = locked { messages.values.byTime().takeLast(limit.coerceAtLeast(0)) }
        override suspend fun between(fromAt: Long, toAt: Long): List<Message> =
            locked { messages.values.filter { it.at >= fromAt && it.at < toAt }.byTime() }
        override suspend fun count(): Int = locked { messages.size }
    }

    override val proposals = object : ProposalRepo {
        override suspend fun insert(proposal: Proposal): Boolean = locked { proposals.putIfAbsent(proposal.id, proposal) == null }
        override suspend fun upsert(proposal: Proposal) = locked { proposals[proposal.id] = proposal }
        override suspend fun get(id: String): Proposal? = locked { proposals[id] }
        override suspend fun withStatus(status: ProposalStatus): List<Proposal> =
            locked { proposals.values.filter { it.status == status }.sortedWith(compareBy({ it.createdAt }, { it.id })) }
        override suspend fun count(): Int = locked { proposals.size }
    }

    override val memory = object : MemoryRepo {
        override suspend fun putItem(item: MemoryItem) = locked { memoryItems[item.id] = item }
        override suspend fun item(id: String): MemoryItem? = locked { memoryItems[id] }
        override suspend fun items(block: String?): List<MemoryItem> = locked {
            memoryItems.values.filter { !it.deleted && (block == null || it.block == block) }
                .sortedWith(compareBy({ it.createdAt }, { it.id }))
        }
        override suspend fun putEvent(event: MemoryEvent) = locked { memoryEvents[event.id] = event }
        override suspend fun event(id: String): MemoryEvent? = locked { memoryEvents[id] }
        override suspend fun events(): List<MemoryEvent> =
            locked { memoryEvents.values.sortedWith(compareByDescending<MemoryEvent> { it.at }.thenByDescending { it.id }) }
    }

    override val nudges = object : NudgeRepo {
        override suspend fun upsert(nudge: Nudge) = locked { nudges[nudge.id] = nudge }
        override suspend fun insert(nudge: Nudge): Boolean = locked { nudges.putIfAbsent(nudge.id, nudge) == null }
        override suspend fun get(id: String): Nudge? = locked { nudges[id] }
        override suspend fun between(fromAt: Long, toAt: Long): List<Nudge> =
            locked { nudges.values.filter { it.at >= fromAt && it.at < toAt }.sortedWith(compareBy({ it.at }, { it.id })) }
        override suspend fun markAnswered(id: String, at: Long): Boolean = locked {
            val n = nudges[id] ?: return@locked false
            nudges[id] = n.copy(answeredAt = at)
            true
        }
    }

    override val journal = object : JournalRepo {
        override suspend fun logMigration(log: MigrationLog) = locked { migrations.removeAll { it.id == log.id }; migrations += log }
        override suspend fun migrations(): List<MigrationLog> = locked { migrations.sortedWith(compareBy({ it.at }, { it.id })) }
        override suspend fun audit(log: AuditLog) = locked { audits.removeAll { it.id == log.id }; audits += log }
        override suspend fun audits(): List<AuditLog> = locked { audits.sortedWith(compareBy({ it.at }, { it.id })) }
    }

    override val nerve = object : NerveRepo {
        override suspend fun insertSignal(signal: StoredSignal): Boolean = locked {
            if (signals.containsKey(signal.id)) false else { signals[signal.id] = signal; true }
        }
        override suspend fun signal(id: String): StoredSignal? = locked { signals[id] }
        override suspend fun signals(fromDay: String, toDay: String): List<StoredSignal> = locked {
            signals.values.filter { it.day in fromDay..toDay }.sortedWith(compareBy({ it.at }, { it.id }))
        }
        override suspend fun insertEffect(effect: NerveEffect): Boolean = locked {
            if (effects.containsKey(effect.id)) false else { effects[effect.id] = effect; true }
        }
        override suspend fun effect(id: String): NerveEffect? = locked { effects[id] }
        override suspend fun effects(day: String): List<NerveEffect> = locked {
            effects.values.filter { it.day == day }.sortedWith(compareBy({ it.at }, { it.id }))
        }
        override suspend fun effectsWith(status: EffectStatus): List<NerveEffect> = locked {
            effects.values.filter { it.status == status }.sortedWith(compareBy({ it.at }, { it.id }))
        }
        override suspend fun setStatus(id: String, status: EffectStatus, decidedAt: Long?): Boolean = locked {
            val e = effects[id] ?: return@locked false
            effects[id] = e.copy(status = status, decidedAt = decidedAt)
            true
        }
    }

    override val thinking = object : ThinkingRepo {
        override suspend fun put(thinking: StoredThinking) = locked { this.thinking[thinking.messageId] = thinking }
        override suspend fun of(messageIds: Collection<String>): List<StoredThinking> = locked { messageIds.mapNotNull { thinking[it] } }
        override suspend fun prune(before: Long): Int = locked {
            val old = thinking.values.filter { it.at < before }.map { it.messageId }
            old.forEach { thinking.remove(it) }
            old.size
        }
        override suspend fun count(): Int = locked { thinking.size }
    }

    override val lessons = object : LessonRepo {
        override suspend fun insert(lesson: Lesson): Boolean = locked { lessons.putIfAbsent(lesson.id, lesson) == null }
        override suspend fun upsert(lesson: Lesson) = locked { lessons[lesson.id] = lesson }
        override suspend fun get(id: String): Lesson? = locked { lessons[id] }
        override suspend fun all(): List<Lesson> = locked { lessons.values.sortedWith(compareBy({ it.foundAt }, { it.id })) }
        override suspend fun withStatus(status: LessonStatus): List<Lesson> = all().filter { it.status == status }
        override suspend fun putQuiz(quiz: Quiz) = locked { quizzes[quiz.id] = quiz }
        override suspend fun quiz(id: String): Quiz? = locked { quizzes[id] }
        override suspend fun quizzes(lessonId: String): List<Quiz> =
            locked { quizzes.values.filter { it.lessonId == lessonId }.sortedWith(compareBy({ it.createdAt }, { it.id })) }
    }
}
