package com.pocketledger

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "transactions", indices = [Index(value = ["fingerprint"], unique = true)])
data class Transaction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fingerprint: String,
    val card: String,
    val occurredAt: Long,
    val description: String,
    val amountCents: Long,
    val balanceCents: Long,
    val originalSms: String,
    val category: String = UNCATEGORIZED,
    @ColumnInfo(defaultValue = "''") val purchaseName: String = "",
    @ColumnInfo(defaultValue = "'EXPENSE'") val kind: TransactionKind = TransactionKind.EXPENSE,
    @ColumnInfo(defaultValue = "1") val hasReportedBalance: Boolean = true
)

@Entity(tableName = "rules")
data class CategoryRule(@PrimaryKey val description: String, val category: String)

@Entity(tableName = "categories")
data class LedgerCategory(
    @PrimaryKey @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    @ColumnInfo(defaultValue = "0") val excludeFromSpending: Boolean = false
)

@Dao
interface LedgerDao {
    @Query("SELECT id, occurredAt, amountCents, category, kind FROM transactions ORDER BY id")
    suspend fun alertPurchases(): List<AlertPurchase>
    @Query("SELECT * FROM categories ORDER BY CASE WHEN name = 'Uncategorized' THEN 0 ELSE 1 END, name COLLATE NOCASE")
    fun observeCategories(): Flow<List<LedgerCategory>>
    @Query("SELECT name FROM categories WHERE excludeFromSpending = 1")
    suspend fun excludedCategories(): List<String>
    @Query("UPDATE categories SET excludeFromSpending = :excluded WHERE name = :name")
    suspend fun setCategoryExcluded(name: String, excluded: Boolean): Int
    @Query("SELECT EXISTS(SELECT 1 FROM categories WHERE name = :name)")
    suspend fun hasCategory(name: String): Boolean
    @Query("SELECT name FROM categories WHERE name = :name LIMIT 1")
    suspend fun categoryNamed(name: String): String?
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCategory(category: LedgerCategory): Long
    @Query("UPDATE transactions SET category = 'Uncategorized' WHERE category = :name")
    suspend fun uncategorize(name: String)
    @Query("DELETE FROM rules WHERE category = :name")
    suspend fun deleteRulesForCategory(name: String)
    @Query("DELETE FROM categories WHERE name = :name")
    suspend fun deleteCategory(name: String)
    @Query("SELECT * FROM transactions ORDER BY occurredAt DESC, id DESC")
    fun observeTransactions(): Flow<List<Transaction>>
    @Query("SELECT * FROM rules ORDER BY description")
    fun observeRules(): Flow<List<CategoryRule>>
    @Query("SELECT category FROM rules WHERE description = :description LIMIT 1")
    suspend fun categoryFor(description: String): String?
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(transaction: Transaction): Long
    @Query("UPDATE transactions SET category = :category, purchaseName = :purchaseName WHERE id = :id")
    suspend fun updatePurchase(id: Long, category: String, purchaseName: String): Int
    @Query("UPDATE transactions SET kind = :kind WHERE id = :id")
    suspend fun updateKind(id: Long, kind: TransactionKind): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveRule(rule: CategoryRule)
    @Query("DELETE FROM rules WHERE description = :description")
    suspend fun deleteRule(description: String)
    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteTransaction(id: Long)
}

@Database(entities = [Transaction::class, CategoryRule::class, LedgerCategory::class], version = 4, exportSchema = false)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun dao(): LedgerDao
    companion object {
        private fun seedCategories(db: SupportSQLiteDatabase) {
            defaultCategories.forEach { db.execSQL("INSERT OR IGNORE INTO categories (name) VALUES (?)", arrayOf(it)) }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN purchaseName TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE TABLE IF NOT EXISTS categories (name TEXT COLLATE NOCASE NOT NULL, PRIMARY KEY(name))")
                seedCategories(db)
                db.execSQL("INSERT OR IGNORE INTO categories (name) SELECT category FROM transactions UNION SELECT category FROM rules")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE categories ADD COLUMN excludeFromSpending INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN kind TEXT NOT NULL DEFAULT 'EXPENSE'")
                db.execSQL("ALTER TABLE transactions ADD COLUMN hasReportedBalance INTEGER NOT NULL DEFAULT 1")
            }
        }
        val SEED_CATEGORIES = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) { seedCategories(db) }
        }
        @Volatile private var instance: LedgerDatabase? = null
        fun get(context: Context): LedgerDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, LedgerDatabase::class.java, "ledger.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).addCallback(SEED_CATEGORIES).build().also { instance = it }
        }
    }
}
