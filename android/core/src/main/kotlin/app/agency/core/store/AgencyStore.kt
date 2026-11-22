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
 * Everything the agency keeps, one repository per aggregate. The app implements it with the encrypted Room database
 * (`RoomAgencyStore`); tests and a later desktop build use [InMemoryAgencyStore]. Both pass the same contract tests.
 *
 * Conventions: `insert` never overwrites (INSERT OR IGNORE on the id, so a re-run import is harmless) and says whether it
 * wrote; `upsert` replaces; lists of day records are ordered by `at`, then id; day ranges are inclusive coach days.
 */
interface AgencyStore {
    val profile: ProfileRepo
    val rules: RulesRepo
    val days: DayOverrideRepo
    val meals: MealRepo
    val food: FoodRepo
    val orders: OrderRepo
    val places: PlaceRepo
    val water: DayLogRepo<WaterLog>
    val training: DayLogRepo<TrainingLog>
    val signs: DayLogRepo<SignLog>
    val weights: DayLogRepo<WeightLog>
    val conditions: ConditionRepo
    val baselines: BaselineRepo
    val media: MediaRepo
    val messages: MessageRepo
    val proposals: ProposalRepo
    val memory: MemoryRepo
    val nudges: NudgeRepo
    val journal: JournalRepo
    val nerve: NerveRepo
    val thinking: ThinkingRepo
    val lessons: LessonRepo

    /** Runs [block] atomically: if it throws, nothing it wrote stays. Calls may nest (the outermost one commits). */
    suspend fun <T> transaction(block: suspend () -> T): T
}

interface ProfileRepo {
    suspend fun get(): Profile?
    suspend fun put(profile: Profile)
}

interface RulesRepo {
    /** The current rules, or null before anything was written (callers then use the defaults). */
    suspend fun current(): CompanyRules?

    /** Writes [rules] as the next version (stamped [at]) and keeps it in the history; returns what was stored. */
    suspend fun put(rules: CompanyRules, at: Long): CompanyRules

    /** Every version, oldest first. */
    suspend fun history(): List<CompanyRules>

    suspend fun section(key: PlanSectionKey): PlanSection?
    suspend fun sections(): List<PlanSection>

    /** Writes the next version of [key] and keeps it in the history; returns what was stored. */
    suspend fun putSection(key: PlanSectionKey, json: String, changedBy: String, at: Long): PlanSection

    /** Every version of [key], oldest first. */
    suspend fun sectionHistory(key: PlanSectionKey): List<PlanSection>
}

interface DayOverrideRepo {
    suspend fun get(day: String): DayOverride?
    suspend fun put(override: DayOverride)
    suspend fun delete(day: String): Boolean
    suspend fun between(fromDay: String, toDay: String): List<DayOverride>
}

/** A log of records that belong to coach days. */
interface DayLogRepo<T : DayRecord> {
    suspend fun insert(record: T): Boolean
    suspend fun upsert(record: T)
    suspend fun get(id: String): T?
    suspend fun onDay(day: String): List<T>
    suspend fun between(fromDay: String, toDay: String): List<T>
    suspend fun delete(id: String): Boolean
    suspend fun count(): Int
}

/** Meals with their items (written and deleted together) and the labels waiting for his numbers. */
interface MealRepo : DayLogRepo<Meal> {
    suspend fun putPending(label: PendingLabel)
    suspend fun pending(): List<PendingLabel>
    suspend fun deletePending(mealId: String, itemIdx: Int): Boolean
    suspend fun countItems(): Int
}

/** The 常吃食物表, keyed by name. */
interface FoodRepo {
    suspend fun get(name: String): FoodEntry?
    suspend fun all(): List<FoodEntry>
    suspend fun insert(entry: FoodEntry): Boolean
    suspend fun upsert(entry: FoodEntry)
    suspend fun delete(name: String): Boolean
    suspend fun count(): Int
}

interface OrderRepo : DayLogRepo<MealOrder> {
    suspend fun setStatus(id: String, status: OrderStatus): Boolean
}

/** Saved places and the search-result cache. */
interface PlaceRepo {
    suspend fun insertFavorite(place: FavoritePlace): Boolean
    suspend fun favorites(): List<FavoritePlace>
    suspend fun deleteFavorite(id: String): Boolean
    suspend fun putResult(result: SearchResult)
    suspend fun result(id: String): SearchResult?
    suspend fun results(kind: SearchKind): List<SearchResult>

    /** Deletes results fetched before [cutoff] except the ids in [keep]; returns how many went. */
    suspend fun pruneResults(cutoff: Long, keep: Set<String>): Int
}

interface ConditionRepo {
    /** One score per day: a second score for the same day replaces the first. */
    suspend fun put(log: ConditionLog)
    suspend fun get(day: String): ConditionLog?
    suspend fun between(fromDay: String, toDay: String): List<ConditionLog>
    suspend fun count(): Int
}

interface BaselineRepo {
    suspend fun get(id: String): Baseline?
    suspend fun insert(baseline: Baseline): Boolean
    suspend fun upsert(baseline: Baseline)
    suspend fun all(): List<Baseline>
    suspend fun count(): Int
}

interface MediaRepo {
    suspend fun insert(media: Media): Boolean
    suspend fun get(id: String): Media?
    suspend fun delete(id: String): Boolean
    suspend fun all(): List<Media>
    suspend fun count(): Int
}

interface MessageRepo {
    suspend fun insert(message: Message): Boolean

    /** Replaces a message (a nudge's template once the model has worded it). */
    suspend fun upsert(message: Message)
    suspend fun get(id: String): Message?

    /** The newest [limit] messages, oldest first. */
    suspend fun recent(limit: Int): List<Message>

    /** Messages with `fromAt <= at < toAt`, oldest first. */
    suspend fun between(fromAt: Long, toAt: Long): List<Message>
    suspend fun count(): Int
}

interface ProposalRepo {
    suspend fun insert(proposal: Proposal): Boolean
    suspend fun upsert(proposal: Proposal)
    suspend fun get(id: String): Proposal?
    suspend fun withStatus(status: ProposalStatus): List<Proposal>
    suspend fun count(): Int
}

interface MemoryRepo {
    suspend fun putItem(item: MemoryItem)
    suspend fun item(id: String): MemoryItem?

    /** Items not deleted, optionally of one block, oldest first. */
    suspend fun items(block: String? = null): List<MemoryItem>
    suspend fun putEvent(event: MemoryEvent)
    suspend fun event(id: String): MemoryEvent?

    /** Every event, newest first. */
    suspend fun events(): List<MemoryEvent>
}

interface NudgeRepo {
    suspend fun insert(nudge: Nudge): Boolean
    suspend fun upsert(nudge: Nudge)
    suspend fun get(id: String): Nudge?

    /** Nudges with `fromAt <= at < toAt`, oldest first. */
    suspend fun between(fromAt: Long, toAt: Long): List<Nudge>
    suspend fun markAnswered(id: String, at: Long): Boolean
}

/** The nerve's signals and the effects its links produced (agency-links §4). */
interface NerveRepo {
    /** INSERT OR IGNORE on the id: false when the signal was already handled (de-dup). */
    suspend fun insertSignal(signal: StoredSignal): Boolean
    suspend fun signal(id: String): StoredSignal?

    /** Signals of the coach days [fromDay]..[toDay], by `at`, then id. */
    suspend fun signals(fromDay: String, toDay: String): List<StoredSignal>
    suspend fun insertEffect(effect: NerveEffect): Boolean
    suspend fun effect(id: String): NerveEffect?

    /** Effects of [day], by `at`, then id. */
    suspend fun effects(day: String): List<NerveEffect>

    /** Effects with [status] (any day), by `at`, then id. */
    suspend fun effectsWith(status: EffectStatus): List<NerveEffect>
    suspend fun setStatus(id: String, status: EffectStatus, decidedAt: Long?): Boolean
}

/**
 * The manager's stored thinking (T12, owner's choice 乙), one row per reply. Read only to send it back to the provider that
 * produced it; nothing else reads it (no screen, no log, no memory, no export).
 */
interface ThinkingRepo {
    suspend fun put(thinking: StoredThinking)

    /** The rows of [messageIds] that exist. */
    suspend fun of(messageIds: Collection<String>): List<StoredThinking>

    /** Deletes the rows written before [before]; returns how many went. */
    suspend fun prune(before: Long): Int
    suspend fun count(): Int
}

/**
 * 理論課 (T19c, schema v4): the videos (search candidates, 「課程清單」, his progress) and their quizzes. A video's title, link
 * and 教材 are copied by code from a stored search result; nothing here is ever model text except checked quiz questions.
 */
interface LessonRepo {
    /** INSERT OR IGNORE on the id. */
    suspend fun insert(lesson: Lesson): Boolean
    suspend fun upsert(lesson: Lesson)
    suspend fun get(id: String): Lesson?

    /** Every lesson, by the time it was found, then id. */
    suspend fun all(): List<Lesson>
    suspend fun withStatus(status: LessonStatus): List<Lesson>
    suspend fun putQuiz(quiz: Quiz)
    suspend fun quiz(id: String): Quiz?

    /** The quizzes of [lessonId], oldest first. */
    suspend fun quizzes(lessonId: String): List<Quiz>
}

/** The import log and the audit log. */
interface JournalRepo {
    suspend fun logMigration(log: MigrationLog)
    suspend fun migrations(): List<MigrationLog>
    suspend fun audit(log: AuditLog)
    suspend fun audits(): List<AuditLog>
}
