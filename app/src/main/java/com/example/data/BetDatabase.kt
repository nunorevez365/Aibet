package com.example.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.quant.BasketballSlip
import com.example.quant.BetStatus
import com.example.quant.GeneratedSlip
import com.example.quant.RoiSummary
import com.example.quant.StakeMode
import com.example.quant.TennisSlip
import kotlinx.coroutines.flow.Flow
import java.util.Locale

@Entity(tableName = "bet_slips")
data class BetSlipEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val cycleLabel: String,
    val slipCategory: String,
    val title: String,
    val selectionsSummary: String,
    val combinedOdd: Double,
    val stake: Double,
    val isFreebet: Boolean,
    val expectedValue: Double,
    val trueProbability: Double,
    val status: String = BetStatus.PENDING.dbValue,
    val netProfit: Double = 0.0
)

@Entity(tableName = "cached_analysis")
data class CachedAnalysisEntity(
    @PrimaryKey val id: Int = 1,
    val updatedAtMillis: Long,
    val cycleCode: String,
    val startDateIso: String,
    val endDateIso: String,
    val exactDatesCsv: String,
    val payloadJson: String
)

@Dao
interface BetSlipDao {
    @Query("SELECT * FROM bet_slips ORDER BY createdAtMillis DESC, id DESC")
    fun observeAllSlips(): Flow<List<BetSlipEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSlip(slip: BetSlipEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSlips(slips: List<BetSlipEntity>)

    @Query("UPDATE bet_slips SET status = :status, netProfit = :netProfit WHERE id = :id")
    suspend fun updateSlipOutcome(id: Long, status: String, netProfit: Double)

    @Query("DELETE FROM bet_slips WHERE id = :id")
    suspend fun deleteSlip(id: Long)

    @Query("DELETE FROM bet_slips")
    suspend fun clearAll()
}

@Dao
interface CachedAnalysisDao {
    @Query("SELECT * FROM cached_analysis WHERE id = :slotId LIMIT 1")
    suspend fun getBySlot(slotId: Int): CachedAnalysisEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CachedAnalysisEntity)

    @Query("DELETE FROM cached_analysis")
    suspend fun clear()
}

@Database(
    entities = [BetSlipEntity::class, CachedAnalysisEntity::class],
    version = 2,
    exportSchema = false
)
abstract class BetDatabase : RoomDatabase() {
    abstract fun betSlipDao(): BetSlipDao
    abstract fun cachedAnalysisDao(): CachedAnalysisDao

    companion object {
        @Volatile
        private var INSTANCE: BetDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `cached_analysis` (
                        `id` INTEGER NOT NULL,
                        `updatedAtMillis` INTEGER NOT NULL,
                        `cycleCode` TEXT NOT NULL,
                        `startDateIso` TEXT NOT NULL,
                        `endDateIso` TEXT NOT NULL,
                        `exactDatesCsv` TEXT NOT NULL,
                        `payloadJson` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): BetDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    BetDatabase::class.java,
                    "betquant_ai.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}

class BetHistoryRepository(private val dao: BetSlipDao) {

    val allSlips: Flow<List<BetSlipEntity>> = dao.observeAllSlips()

    suspend fun saveGeneratedSlip(slip: GeneratedSlip, cycleLabel: String): Long {
        return dao.insertSlip(slip.toEntity(cycleLabel))
    }

    suspend fun saveGeneratedSlips(slips: List<GeneratedSlip>, cycleLabel: String) {
        dao.insertSlips(slips.map { it.toEntity(cycleLabel) })
    }

    suspend fun saveBasketballSlip(slip: BasketballSlip, cycleLabel: String): Long {
        return dao.insertSlip(slip.toEntity(cycleLabel))
    }

    suspend fun saveBasketballSlips(slips: List<BasketballSlip>, cycleLabel: String) {
        dao.insertSlips(slips.map { it.toEntity(cycleLabel) })
    }

    suspend fun saveTennisSlip(slip: TennisSlip, cycleLabel: String): Long {
        return dao.insertSlip(slip.toEntity(cycleLabel))
    }

    suspend fun saveTennisSlips(slips: List<TennisSlip>, cycleLabel: String) {
        dao.insertSlips(slips.map { it.toEntity(cycleLabel) })
    }

    suspend fun updateOutcome(slip: BetSlipEntity, newStatus: BetStatus) {
        val computedNetProfit = when (newStatus) {
            BetStatus.PENDING -> 0.0
            BetStatus.WON -> slip.stake * (slip.combinedOdd - 1.0)
            BetStatus.LOST -> if (slip.isFreebet) 0.0 else -slip.stake
        }
        dao.updateSlipOutcome(
            id = slip.id,
            status = newStatus.dbValue,
            netProfit = computedNetProfit
        )
    }

    suspend fun deleteSlip(id: Long) = dao.deleteSlip(id)

    suspend fun clearHistory() = dao.clearAll()

    companion object {
        fun computeRoiSummary(slips: List<BetSlipEntity>): RoiSummary {
            val settled = slips.filter { BetStatus.fromDb(it.status) != BetStatus.PENDING }
            val won = settled.count { BetStatus.fromDb(it.status) == BetStatus.WON }
            val lost = settled.count { BetStatus.fromDb(it.status) == BetStatus.LOST }
            val pending = slips.size - settled.size

            val winRate = if (settled.isNotEmpty()) (won.toDouble() / settled.size) * 100.0 else 0.0
            val totalStakedReal = settled.filter { !it.isFreebet }.sumOf { it.stake }
            val totalStakedAll = settled.sumOf { it.stake }
            val totalNetProfit = settled.sumOf { it.netProfit }
            val freebetProfit = settled.filter { it.isFreebet }.sumOf { it.netProfit }

            val denominator = if (totalStakedReal > 0.0) totalStakedReal else totalStakedAll
            val roi = if (denominator > 0.0) (totalNetProfit / denominator) * 100.0 else 0.0

            // Build chronological equity curve for Canvas plotting
            val chronologicalSettled = settled.sortedBy { it.createdAtMillis }
            val curve = mutableListOf(0.0)
            var running = 0.0
            for (item in chronologicalSettled) {
                running += item.netProfit
                curve.add(running)
            }

            return RoiSummary(
                totalSlips = slips.size,
                settledCount = settled.size,
                wonCount = won,
                lostCount = lost,
                pendingCount = pending,
                winRatePercent = winRate,
                totalStakedReal = totalStakedReal,
                totalNetProfit = totalNetProfit,
                roiPercent = roi,
                freebetNetProfit = freebetProfit,
                equityCurvePoints = curve
            )
        }
    }
}

private fun GeneratedSlip.toEntity(cycleLabel: String): BetSlipEntity {
    val summary = selections.joinToString("\n") { leg ->
        val edgeSign = if (leg.edge >= 0) "+" else ""
        String.format(
            Locale.US,
            "%s • %s @ %.2f (Edge %s%.1f%%)",
            leg.matchLabel,
            leg.market.code,
            leg.odd,
            edgeSign,
            leg.edge * 100.0
        )
    }
    return BetSlipEntity(
        cycleLabel = cycleLabel,
        slipCategory = type.code,
        title = title,
        selectionsSummary = summary,
        combinedOdd = combinedOdd,
        stake = stake,
        isFreebet = stakeMode == StakeMode.FREEBET,
        expectedValue = expectedValue,
        trueProbability = combinedTrueProb
    )
}

private fun BasketballSlip.toEntity(cycleLabel: String): BetSlipEntity {
    val summary = selections.joinToString("\n") { leg ->
        val edgeSign = if (leg.edge >= 0) "+" else ""
        String.format(
            Locale.US,
            "%s • %s @ %.2f (Edge %s%.1f%%)",
            leg.matchLabel,
            leg.lineLabel,
            leg.odd,
            edgeSign,
            leg.edge * 100.0
        )
    }
    return BetSlipEntity(
        cycleLabel = "🏀 $cycleLabel",
        slipCategory = "BASKET_${type.code}",
        title = title,
        selectionsSummary = summary,
        combinedOdd = combinedOdd,
        stake = stake,
        isFreebet = stakeMode == StakeMode.FREEBET,
        expectedValue = expectedValue,
        trueProbability = combinedTrueProb
    )
}

private fun TennisSlip.toEntity(cycleLabel: String): BetSlipEntity {
    val summary = selections.joinToString("\n") { leg ->
        val edgeSign = if (leg.edge >= 0) "+" else ""
        String.format(
            Locale.US,
            "%s • %s @ %.2f (Edge %s%.1f%%)",
            leg.matchLabel,
            leg.lineLabel,
            leg.odd,
            edgeSign,
            leg.edge * 100.0
        )
    }
    return BetSlipEntity(
        cycleLabel = "🎾 $cycleLabel",
        slipCategory = "TENNIS_${type.code}",
        title = title,
        selectionsSummary = summary,
        combinedOdd = combinedOdd,
        stake = stake,
        isFreebet = stakeMode == StakeMode.FREEBET,
        expectedValue = expectedValue,
        trueProbability = combinedTrueProb
    )
}
