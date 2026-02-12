package com.esp32pumpwifi.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.min

private const val DB_NAME = "daily_dose_events.db"
private const val DB_VERSION = 1
private const val SOURCE_AUTO = "AUTO"
private const val SOURCE_MANUAL = "MANUAL"

data class ProgramSnapshotEntity(
    val id: Long = 0,
    val moduleId: Long,
    val pumpNum: Int,
    val sentAtMs: Long,
    val programHash: String,
    val rawEncodedProgram: String,
    val ok: Boolean
)

data class DoseEventEntity(
    val id: Long = 0,
    val moduleId: Long,
    val pumpNum: Int,
    val dayStartMs: Long,
    val offsetMs: Long,
    val volumeMl: Float,
    val source: String,
    val programHashUsed: String?,
    val eventKey: String
)

private class DailyDoseDbHelper(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE program_snapshots (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                moduleId INTEGER NOT NULL,
                pumpNum INTEGER NOT NULL,
                sentAtMs INTEGER NOT NULL,
                programHash TEXT NOT NULL,
                rawEncodedProgram TEXT NOT NULL,
                ok INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_program_snapshots_day ON program_snapshots(moduleId, pumpNum, sentAtMs)")

        db.execSQL(
            """
            CREATE TABLE dose_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                moduleId INTEGER NOT NULL,
                pumpNum INTEGER NOT NULL,
                dayStartMs INTEGER NOT NULL,
                offsetMs INTEGER NOT NULL,
                volumeMl REAL NOT NULL,
                source TEXT NOT NULL,
                programHashUsed TEXT,
                eventKey TEXT NOT NULL UNIQUE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_dose_events_day ON dose_events(moduleId, pumpNum, dayStartMs)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
}

object DailyDoseStore {
    private const val LINE_LEN = 12
    private const val MIN_DURATION_MS = 50
    private const val MAX_DURATION_MS = 600_000

    @Volatile
    private var helper: DailyDoseDbHelper? = null

    private fun db(context: Context): SQLiteDatabase {
        val appContext = context.applicationContext
        val h = helper ?: synchronized(this) {
            helper ?: DailyDoseDbHelper(appContext).also { helper = it }
        }
        return h.writableDatabase
    }

    data class DayRange(val dayStartMs: Long, val dayEndMs: Long)

    fun todayRange(zone: ZoneId = ZoneId.systemDefault()): DayRange {
        val today = LocalDate.now(zone)
        val range = DayBoundaryUtils.dayRange(today, zone)
        return DayRange(range.dayStartMs, range.dayEndMsExclusive)
    }

    fun computeProgramHash(rawProgram: String): String {
        val canonical = rawProgram
            .replace("\r\n", "\n")
            .trim()
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun insertProgramSnapshot(context: Context, snapshot: ProgramSnapshotEntity): Long {
        val values = ContentValues().apply {
            put("moduleId", snapshot.moduleId)
            put("pumpNum", snapshot.pumpNum)
            put("sentAtMs", snapshot.sentAtMs)
            put("programHash", snapshot.programHash)
            put("rawEncodedProgram", snapshot.rawEncodedProgram)
            put("ok", if (snapshot.ok) 1 else 0)
        }
        return db(context).insert("program_snapshots", null, values)
    }

    fun getSnapshotsForDay(context: Context, moduleId: Long, pumpNum: Int, dayStartMs: Long, dayEndMs: Long): List<ProgramSnapshotEntity> {
        val query = """
            SELECT id,moduleId,pumpNum,sentAtMs,programHash,rawEncodedProgram,ok
            FROM program_snapshots
            WHERE moduleId=? AND pumpNum=? AND sentAtMs>=? AND sentAtMs<? AND ok=1
            ORDER BY sentAtMs ASC
        """.trimIndent()

        return db(context).rawQuery(
            query,
            arrayOf(moduleId.toString(), pumpNum.toString(), dayStartMs.toString(), dayEndMs.toString())
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        ProgramSnapshotEntity(
                            id = cursor.getLong(0),
                            moduleId = cursor.getLong(1),
                            pumpNum = cursor.getInt(2),
                            sentAtMs = cursor.getLong(3),
                            programHash = cursor.getString(4),
                            rawEncodedProgram = cursor.getString(5),
                            ok = cursor.getInt(6) == 1
                        )
                    )
                }
            }
        }
    }

    fun insertDoseEventIgnore(context: Context, event: DoseEventEntity): Long {
        val values = ContentValues().apply {
            put("moduleId", event.moduleId)
            put("pumpNum", event.pumpNum)
            put("dayStartMs", event.dayStartMs)
            put("offsetMs", event.offsetMs)
            put("volumeMl", event.volumeMl)
            put("source", event.source)
            put("programHashUsed", event.programHashUsed)
            put("eventKey", event.eventKey)
        }
        return db(context).insertWithOnConflict("dose_events", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun sumMlForDay(context: Context, moduleId: Long, pumpNum: Int, dayStartMs: Long): Float {
        val query = "SELECT COALESCE(SUM(volumeMl), 0) FROM dose_events WHERE moduleId=? AND pumpNum=? AND dayStartMs=?"
        return db(context).rawQuery(
            query,
            arrayOf(moduleId.toString(), pumpNum.toString(), dayStartMs.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getFloat(0) else 0f
        }
    }

    fun countForDay(context: Context, moduleId: Long, pumpNum: Int, dayStartMs: Long): Int {
        val query = "SELECT COUNT(*) FROM dose_events WHERE moduleId=? AND pumpNum=? AND dayStartMs=?"
        return db(context).rawQuery(
            query,
            arrayOf(moduleId.toString(), pumpNum.toString(), dayStartMs.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun saveProgramSnapshotsFromMessage(context: Context, moduleId: Long, rawProgram576: String, sentAtMs: Long = System.currentTimeMillis()) {
        if (rawProgram576.length != 576) return
        val chunks = rawProgram576.chunked(LINE_LEN)
        if (chunks.size != 48) return

        for (pump in 1..4) {
            val start = (pump - 1) * 12
            val pumpProgram = chunks.subList(start, start + 12).joinToString(separator = "")
            val pumpHash = computeProgramHash(pumpProgram)
            insertProgramSnapshot(
                context,
                ProgramSnapshotEntity(
                    moduleId = moduleId,
                    pumpNum = pump,
                    sentAtMs = sentAtMs,
                    programHash = pumpHash,
                    rawEncodedProgram = pumpProgram,
                    ok = true
                )
            )
        }
    }

    fun buildAutoDoseEventsForToday(context: Context, moduleId: Long) {
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val zone = ZoneId.systemDefault()
        val nowMs = System.currentTimeMillis()
        val (dayStartMs, dayEndMs) = todayRange(zone)
        val day = Instant.ofEpochMilli(dayStartMs).atZone(zone).toLocalDate()
        val cappedNowMs = min(nowMs, dayEndMs)

        for (pump in 1..4) {
            val flow = prefs.getFloat("esp_${moduleId}_pump${pump}_flow", 0f)
            if (flow <= 0f) continue

            val snapshots = getSnapshotsForDay(context, moduleId, pump, dayStartMs, cappedNowMs)
            if (snapshots.isEmpty()) continue

            for (i in snapshots.indices) {
                val snapshot = snapshots[i]
                val intervalEnd = if (i + 1 < snapshots.size) snapshots[i + 1].sentAtMs else cappedNowMs
                val doses = parsePumpProgramDoses(snapshot.rawEncodedProgram, flow)

                for ((offsetMs, volumeMl) in doses) {
                    val doseStartMs = DayBoundaryUtils.instantAtOffset(day, offsetMs, zone).toEpochMilli()
                    if (doseStartMs > nowMs) continue
                    if (snapshot.sentAtMs > doseStartMs) continue
                    if (doseStartMs >= intervalEnd) continue

                    val eventKey = "A:${moduleId}:${pump}:${dayStartMs}:${offsetMs}:${formatVolume(volumeMl)}:${snapshot.programHash}"
                    insertDoseEventIgnore(
                        context,
                        DoseEventEntity(
                            moduleId = moduleId,
                            pumpNum = pump,
                            dayStartMs = dayStartMs,
                            offsetMs = offsetMs,
                            volumeMl = volumeMl,
                            source = SOURCE_AUTO,
                            programHashUsed = snapshot.programHash,
                            eventKey = eventKey
                        )
                    )
                }
            }
        }
    }

    fun saveManualDoseEvent(context: Context, moduleId: Long, pumpNum: Int, volumeMl: Float, tsMs: Long = System.currentTimeMillis()) {
        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochMilli(tsMs).atZone(zone).toLocalDate()
        val range = DayBoundaryUtils.dayRange(day, zone)
        val dayStartMs = range.dayStartMs
        val offsetMs = (tsMs - dayStartMs).coerceAtLeast(0L)
        val eventKey = "M:${moduleId}:${pumpNum}:${tsMs}:${formatVolume(volumeMl)}"
        insertDoseEventIgnore(
            context,
            DoseEventEntity(
                moduleId = moduleId,
                pumpNum = pumpNum,
                dayStartMs = dayStartMs,
                offsetMs = offsetMs,
                volumeMl = volumeMl,
                source = SOURCE_MANUAL,
                programHashUsed = null,
                eventKey = eventKey
            )
        )
    }

    private fun parsePumpProgramDoses(rawPumpProgram: String, flowMlPerSec: Float): List<Pair<Long, Float>> {
        if (rawPumpProgram.length != 144) return emptyList()
        return rawPumpProgram.chunked(LINE_LEN).mapNotNull { line ->
            if (!isValidEnabledProgramLine(line)) return@mapNotNull null
            val hh = line.substring(2, 4).toIntOrNull() ?: return@mapNotNull null
            val mm = line.substring(4, 6).toIntOrNull() ?: return@mapNotNull null
            val durationMs = line.substring(6, 12).toIntOrNull() ?: return@mapNotNull null
            if (hh !in 0..23 || mm !in 0..59) return@mapNotNull null
            if (durationMs !in MIN_DURATION_MS..MAX_DURATION_MS) return@mapNotNull null

            val offsetMs = hh * 3_600_000L + mm * 60_000L
            val volumeMl = (durationMs / 1000f) * flowMlPerSec
            offsetMs to volumeMl
        }
    }

    private fun isValidEnabledProgramLine(line: String): Boolean {
        if (line.length != LINE_LEN) return false
        if (!line.all(Char::isDigit)) return false
        if (line == "000000000000") return false
        return line[0] == '1'
    }

    private fun formatVolume(volumeMl: Float): String = "%.3f".format(volumeMl)
}
