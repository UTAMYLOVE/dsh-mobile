package app.dshmobile.agency.data

import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import app.dshmobile.agency.data.dao.BaselineDao
import app.dshmobile.agency.data.dao.ConditionDao
import app.dshmobile.agency.data.dao.DayOverrideDao
import app.dshmobile.agency.data.dao.FoodDao
import app.dshmobile.agency.data.dao.JournalDao
import app.dshmobile.agency.data.dao.MealDao
import app.dshmobile.agency.data.dao.MediaDao
import app.dshmobile.agency.data.dao.MemoryDao
import app.dshmobile.agency.data.dao.MessageDao
import app.dshmobile.agency.data.dao.NerveDao
import app.dshmobile.agency.data.dao.NudgeDao
import app.dshmobile.agency.data.dao.OrderDao
import app.dshmobile.agency.data.dao.PlaceDao
import app.dshmobile.agency.data.dao.ProfileDao
import app.dshmobile.agency.data.dao.ProposalDao
import app.dshmobile.agency.data.dao.RulesDao
import app.dshmobile.agency.data.dao.SignDao
import app.dshmobile.agency.data.dao.ThinkingDao
import app.dshmobile.agency.data.dao.TrainingDao
import app.dshmobile.agency.data.dao.WaterDao
import app.dshmobile.agency.data.dao.WeightDao
import app.dshmobile.agency.data.entities.AuditLogEntity
import app.dshmobile.agency.data.entities.BaselineEntity
import app.dshmobile.agency.data.entities.CompanyRulesEntity
import app.dshmobile.agency.data.entities.CompanyRulesHistoryEntity
import app.dshmobile.agency.data.entities.ConditionLogEntity
import app.dshmobile.agency.data.entities.DayOverrideEntity
import app.dshmobile.agency.data.entities.FavoritePlaceEntity
import app.dshmobile.agency.data.entities.FoodEntity
import app.dshmobile.agency.data.entities.MealEntity
import app.dshmobile.agency.data.entities.MealItemEntity
import app.dshmobile.agency.data.entities.MealOrderEntity
import app.dshmobile.agency.data.entities.MediaEntity
import app.dshmobile.agency.data.entities.MemoryEventEntity
import app.dshmobile.agency.data.entities.MemoryItemEntity
import app.dshmobile.agency.data.entities.MessageEntity
import app.dshmobile.agency.data.entities.MigrationLogEntity
import app.dshmobile.agency.data.entities.NerveEffectEntity
import app.dshmobile.agency.data.entities.NudgeEntity
import app.dshmobile.agency.data.entities.PendingLabelEntity
import app.dshmobile.agency.data.entities.PlanSectionEntity
import app.dshmobile.agency.data.entities.PlanSectionHistoryEntity
import app.dshmobile.agency.data.entities.ProfileEntity
import app.dshmobile.agency.data.entities.ProposalEntity
import app.dshmobile.agency.data.entities.SearchResultEntity
import app.dshmobile.agency.data.entities.SignLogEntity
import app.dshmobile.agency.data.entities.SignalEntity
import app.dshmobile.agency.data.entities.ThinkingEntity
import app.dshmobile.agency.data.entities.TrainingLogEntity
import app.dshmobile.agency.data.entities.WaterLogEntity
import app.dshmobile.agency.data.entities.WeightLogEntity
import app.dshmobile.agency.secure.DataLockedException
import app.dshmobile.agency.secure.TransientDataException
import java.io.File
import net.zetetic.database.sqlcipher.SQLiteNotADatabaseException
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * The agency's records (arch 1.2): Room over SQLCipher, file `noBackupFilesDir/agency/agency.db`. Schemas are in `schemas/`:
 * v1 (T02), v2 adds the nerve's `signal` and `nerve_effect` (T11b, [MIGRATION_1_2]), v3 the 「沒送出」 mark on `message` and the
 * manager's `thinking` (T12, [MIGRATION_2_3]), v4 理論課's `lesson` and `quiz` (T19c, [MIGRATION_3_4]), v5 `delivery_income`
 * (batch A, [MIGRATION_4_5]).
 */
@Database(
    version = 5,
    exportSchema = true,
    entities = [
        ProfileEntity::class, CompanyRulesEntity::class, CompanyRulesHistoryEntity::class, PlanSectionEntity::class,
        PlanSectionHistoryEntity::class, DayOverrideEntity::class, MealEntity::class, MealItemEntity::class, FoodEntity::class,
        PendingLabelEntity::class, MealOrderEntity::class, FavoritePlaceEntity::class, SearchResultEntity::class,
        WaterLogEntity::class, TrainingLogEntity::class, ConditionLogEntity::class, SignLogEntity::class, WeightLogEntity::class,
        BaselineEntity::class, MediaEntity::class, MessageEntity::class, ProposalEntity::class, MemoryItemEntity::class,
        MemoryEventEntity::class, NudgeEntity::class, MigrationLogEntity::class, AuditLogEntity::class, SignalEntity::class,
        NerveEffectEntity::class, ThinkingEntity::class, app.dshmobile.agency.data.entities.LessonEntity::class,
        app.dshmobile.agency.data.entities.QuizEntity::class, app.dshmobile.agency.data.entities.DeliveryIncomeEntity::class,
    ],
)
abstract class AgencyDatabase : RoomDatabase() {
    abstract fun profile(): ProfileDao
    abstract fun rules(): RulesDao
    abstract fun days(): DayOverrideDao
    abstract fun meals(): MealDao
    abstract fun food(): FoodDao
    abstract fun orders(): OrderDao
    abstract fun places(): PlaceDao
    abstract fun water(): WaterDao
    abstract fun training(): TrainingDao
    abstract fun signs(): SignDao
    abstract fun weights(): WeightDao
    abstract fun conditions(): ConditionDao
    abstract fun baselines(): BaselineDao
    abstract fun media(): MediaDao
    abstract fun messages(): MessageDao
    abstract fun proposals(): ProposalDao
    abstract fun memory(): MemoryDao
    abstract fun nudges(): NudgeDao
    abstract fun journal(): JournalDao
    abstract fun nerve(): NerveDao
    abstract fun thinking(): ThinkingDao
    abstract fun lessons(): app.dshmobile.agency.data.dao.LessonDao
    abstract fun income(): app.dshmobile.agency.data.dao.IncomeDao

    companion object {
        /** v1 → v2 (T11b): the nerve's two tables; nothing else changes, every v1 row stays. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_1_2_SQL.forEach(db::execSQL)
            }
        }

        /** Copied from `schemas/…/2.json` (Room checks the result against it on open). */
        val MIGRATION_1_2_SQL = listOf(
            "CREATE TABLE IF NOT EXISTS `signal` (`id` TEXT NOT NULL, `day` TEXT NOT NULL, `at` INTEGER NOT NULL, `type` TEXT NOT NULL, `json` TEXT NOT NULL, `depth` INTEGER NOT NULL, `cause_id` TEXT, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_signal_day` ON `signal` (`day`)",
            "CREATE TABLE IF NOT EXISTS `nerve_effect` (`id` TEXT NOT NULL, `day` TEXT NOT NULL, `at` INTEGER NOT NULL, `link` TEXT NOT NULL, `signal_id` TEXT NOT NULL, `kind` TEXT NOT NULL, `json` TEXT NOT NULL, `because` TEXT NOT NULL, `status` TEXT NOT NULL, `proposal_id` TEXT, `due_at` INTEGER, `decided_at` INTEGER, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_nerve_effect_day` ON `nerve_effect` (`day`)",
            "CREATE INDEX IF NOT EXISTS `index_nerve_effect_status` ON `nerve_effect` (`status`)",
        )

        /** v2 → v3 (T12): the 「沒送出」 mark (every existing line was sent: 0) and the thinking table; every v2 row stays. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_2_3_SQL.forEach(db::execSQL)
            }
        }

        /** Copied from `schemas/…/3.json` (Room checks the result against it on open). */
        val MIGRATION_2_3_SQL = listOf(
            "ALTER TABLE `message` ADD COLUMN `unsent` INTEGER NOT NULL DEFAULT 0",
            "CREATE TABLE IF NOT EXISTS `thinking` (`message_id` TEXT NOT NULL, `at` INTEGER NOT NULL, `provider` TEXT NOT NULL, `wire` TEXT NOT NULL, `payload` TEXT NOT NULL, PRIMARY KEY(`message_id`))",
            "CREATE INDEX IF NOT EXISTS `index_thinking_at` ON `thinking` (`at`)",
        )

        /** v3 → v4 (T19c): 理論課's two tables; every v3 row stays. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_3_4_SQL.forEach(db::execSQL)
            }
        }

        /** Copied from `schemas/…/4.json` (Room checks the result against it on open). */
        val MIGRATION_3_4_SQL = listOf(
            "CREATE TABLE IF NOT EXISTS `lesson` (`id` TEXT NOT NULL, `result_id` TEXT NOT NULL, `category` TEXT NOT NULL, `language` TEXT, `title` TEXT NOT NULL, `url` TEXT NOT NULL, `source` TEXT NOT NULL, `status` TEXT NOT NULL, `found_at` INTEGER NOT NULL, `listed_at` INTEGER, `opened_at` INTEGER, `watched_at` INTEGER, `key_points` TEXT, `quiz_passed_at` INTEGER, `note` TEXT, `passed_at` INTEGER, `passed_day` TEXT, `score` INTEGER, `retries` INTEGER NOT NULL, `fallback` INTEGER NOT NULL, `practical_media_id` TEXT, `review_day` TEXT, `review_score` INTEGER, `reviewed_at` INTEGER, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_lesson_status` ON `lesson` (`status`)",
            "CREATE TABLE IF NOT EXISTS `quiz` (`id` TEXT NOT NULL, `lesson_id` TEXT NOT NULL, `kind` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `questions` TEXT NOT NULL, `shown` INTEGER NOT NULL, `answers` TEXT NOT NULL, `status` TEXT NOT NULL, `score` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_quiz_lesson_id` ON `quiz` (`lesson_id`)",
        )

        /** v4 → v5 (batch A): 外送收入, one row a day; every v4 row stays. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_4_5_SQL.forEach(db::execSQL)
            }
        }

        /** Copied from `schemas/…/5.json` (Room checks the result against it on open). */
        val MIGRATION_4_5_SQL = listOf(
            "CREATE TABLE IF NOT EXISTS `delivery_income` (`day` TEXT NOT NULL, `amount` INTEGER NOT NULL, `at` INTEGER NOT NULL, PRIMARY KEY(`day`))",
        )

        val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)

        fun file(context: Context) = File(context.noBackupFilesDir, "agency/agency.db")

        /**
         * Opens (creating on first use) the encrypted database at [file] with the passphrase from [key]. Blocking: call it off
         * the main thread. Nothing is ever deleted when it fails. What it throws:
         * - [DataLockedException]: the passphrase is gone or cannot be opened (its Keystore key is lost), or an existing file
         *   does not open with it or is damaged. Trying again does not help (「資料鎖打不開」).
         * - [TransientDataException]: the secrets file or the Keystore could not be read or written, or an existing file could
         *   not be opened for a passing reason (disk full, an I/O error, busy, cannot open). Offer to try again.
         * - For a new file that cannot be created (disk full, I/O), [TransientDataException] too (T06 review M5).
         * - Not mapped, so the caller must catch them too and must not delete anything: Room's [IllegalStateException] (the
         *   schema's identity hash differs, or a migration is missing after a downgrade), a [RuntimeException] the Keystore
         *   throws that is not one of the above, and an [UnsatisfiedLinkError] when SQLCipher's library cannot load.
         */
        fun open(context: Context, key: DbKey = DbKey.forApp(context), file: File = file(context)): AgencyDatabase {
            System.loadLibrary("sqlcipher")
            file.parentFile?.mkdirs()
            val existed = file.exists()
            return openChecked(context, file, existed, SupportOpenHelperFactory(key.sqlcipherKey(dbExists = existed)))
        }

        /**
         * [open] after the key: opens through [factory] now, not at the first query, and maps every SQLite open failure on an
         * existing file ([existed]) to [DataLockedException] (does not open with the key, or damaged) or
         * [TransientDataException] (every other one), keeping the file. Separate so JVM tests can inject failures.
         */
        internal fun openChecked(context: Context, file: File, existed: Boolean, factory: SupportSQLiteOpenHelper.Factory): AgencyDatabase {
            val db = build(context, file.absolutePath, factory)
            try {
                db.openHelper.writableDatabase
            } catch (e: SQLiteNotADatabaseException) {
                db.closeQuietly()
                throw DataLockedException("the database key does not open the file", e)
            } catch (e: SQLiteDatabaseCorruptException) {
                db.closeQuietly()
                // A file being created cannot be damaged yet: a passing failure (T06 review M5), never the lock.
                if (!existed) throw TransientDataException("the database file could not be created", e)
                throw DataLockedException("the database file is damaged", e)
            } catch (e: SQLiteException) {
                db.closeQuietly()
                if (!existed) throw TransientDataException("the database file could not be created", e)
                throw TransientDataException("the database file cannot be opened now", e)
            }
            return db
        }

        private fun AgencyDatabase.closeQuietly() {
            try {
                close()
            } catch (_: Exception) {
            }
        }

        /** The schema on any open helper: [name] null is in memory. Tests inject the framework (plain SQLite) factory. */
        fun build(context: Context, name: String?, factory: SupportSQLiteOpenHelper.Factory): AgencyDatabase {
            val builder = if (name == null) {
                Room.inMemoryDatabaseBuilder(context.applicationContext, AgencyDatabase::class.java)
            } else {
                Room.databaseBuilder(context.applicationContext, AgencyDatabase::class.java, name)
            }
            return builder.openHelperFactory(factory).addMigrations(*MIGRATIONS).build()
        }
    }
}
