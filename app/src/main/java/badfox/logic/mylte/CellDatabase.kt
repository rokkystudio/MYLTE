package badfox.logic.mylte

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.location.Location
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Stores precise GPS + cellular signal measurements and cached tower estimates.
 *
 * Measurements are never deleted because of age. A cell keeps up to 5,000 newest samples,
 * and old rows are removed only when a new sample for the same cell pushes it over that limit.
 */
class CellDatabase(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    data class TowerEstimate(
        val cellKey: String,
        val radio: String,
        val operator: String?,
        val identityText: String,
        val latitude: Double,
        val longitude: Double,
        val radiusMeters: Double,
        val sampleCount: Int,
        val lastSeenAt: Long,
        val updatedAt: Long,
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE cell_measurements (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                cell_key TEXT NOT NULL,
                radio TEXT NOT NULL,
                operator TEXT,
                identity_text TEXT NOT NULL,
                registered INTEGER NOT NULL,
                signal_dbm INTEGER NOT NULL,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                gps_accuracy_m REAL NOT NULL,
                recorded_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX idx_cell_measurements_key_time ON cell_measurements(cell_key, recorded_at DESC)"
        )
        db.execSQL(
            """
            CREATE TABLE tower_estimates (
                cell_key TEXT PRIMARY KEY,
                radio TEXT NOT NULL,
                operator TEXT,
                identity_text TEXT NOT NULL,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                radius_m REAL NOT NULL,
                sample_count INTEGER NOT NULL,
                last_seen_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE tower_estimates ADD COLUMN last_seen_at INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE tower_estimates SET last_seen_at = updated_at WHERE last_seen_at = 0")
        }
    }

    /**
     * Adds one accepted precise-GPS cellular measurement, applies per-cell rolling retention,
     * and refreshes the cached estimate for that cell.
     */
    fun insertMeasurement(record: CellTowerRecord, location: Location, recordedAt: Long = System.currentTimeMillis()) {
        val signalDbm = record.signalDbm
        if (signalDbm !in MIN_VALID_DBM..MAX_VALID_DBM) return

        writableDatabase.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("cell_key", record.cellKey)
                put("radio", record.radio)
                put("operator", record.operator)
                put("identity_text", record.identityLines.joinToString("\n"))
                put("registered", if (record.registered) 1 else 0)
                put("signal_dbm", signalDbm)
                put("latitude", location.latitude)
                put("longitude", location.longitude)
                put("gps_accuracy_m", location.accuracy.toDouble())
                put("recorded_at", recordedAt)
            }
            writableDatabase.insertOrThrow("cell_measurements", null, values)
            pruneMeasurements(record.cellKey)
            updateTowerEstimate(record.cellKey)
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    /**
     * Returns every cached tower estimate, including towers that have not been observed recently.
     */
    fun readTowerEstimates(): List<TowerEstimate> {
        val result = mutableListOf<TowerEstimate>()
        readableDatabase.query(
            "tower_estimates",
            arrayOf(
                "cell_key",
                "radio",
                "operator",
                "identity_text",
                "latitude",
                "longitude",
                "radius_m",
                "sample_count",
                "last_seen_at",
                "updated_at",
            ),
            null,
            null,
            null,
            null,
            "last_seen_at DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += TowerEstimate(
                    cellKey = cursor.getString(0),
                    radio = cursor.getString(1),
                    operator = cursor.getString(2),
                    identityText = cursor.getString(3),
                    latitude = cursor.getDouble(4),
                    longitude = cursor.getDouble(5),
                    radiusMeters = cursor.getDouble(6),
                    sampleCount = cursor.getInt(7),
                    lastSeenAt = cursor.getLong(8),
                    updatedAt = cursor.getLong(9),
                )
            }
        }
        return result
    }

    private fun pruneMeasurements(cellKey: String) {
        writableDatabase.execSQL(
            """
            DELETE FROM cell_measurements
            WHERE cell_key = ?
              AND id NOT IN (
                  SELECT id
                  FROM cell_measurements
                  WHERE cell_key = ?
                  ORDER BY recorded_at DESC, id DESC
                  LIMIT $MAX_MEASUREMENTS_PER_CELL
              )
            """.trimIndent(),
            arrayOf(cellKey, cellKey),
        )
    }

    private fun updateTowerEstimate(cellKey: String) {
        data class Sample(
            val radio: String,
            val operator: String?,
            val identityText: String,
            val signalDbm: Int,
            val latitude: Double,
            val longitude: Double,
            val accuracyMeters: Double,
            val recordedAt: Long,
        )

        val samples = mutableListOf<Sample>()
        readableDatabase.query(
            "cell_measurements",
            arrayOf(
                "radio",
                "operator",
                "identity_text",
                "signal_dbm",
                "latitude",
                "longitude",
                "gps_accuracy_m",
                "recorded_at",
            ),
            "cell_key = ?",
            arrayOf(cellKey),
            null,
            null,
            "recorded_at ASC, id ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                samples += Sample(
                    radio = cursor.getString(0),
                    operator = cursor.getString(1),
                    identityText = cursor.getString(2),
                    signalDbm = cursor.getInt(3),
                    latitude = cursor.getDouble(4),
                    longitude = cursor.getDouble(5),
                    accuracyMeters = cursor.getDouble(6),
                    recordedAt = cursor.getLong(7),
                )
            }
        }
        if (samples.isEmpty()) return

        val strongestDbm = samples.maxOf { it.signalDbm }
        var weightSum = 0.0
        var latitudeSum = 0.0
        var longitudeSum = 0.0
        samples.forEach { sample ->
            val weight = 10.0.pow((sample.signalDbm - strongestDbm) / 10.0)
            weightSum += weight
            latitudeSum += sample.latitude * weight
            longitudeSum += sample.longitude * weight
        }
        val latitude = latitudeSum / weightSum
        val longitude = longitudeSum / weightSum

        var radiusSquaredSum = 0.0
        samples.forEach { sample ->
            val weight = 10.0.pow((sample.signalDbm - strongestDbm) / 10.0)
            val distance = distanceMeters(latitude, longitude, sample.latitude, sample.longitude)
            radiusSquaredSum += weight * (
                distance * distance +
                    sample.accuracyMeters * sample.accuracyMeters
                )
        }
        val radiusMeters = sqrt(radiusSquaredSum / weightSum)
        val newest = samples.last()

        val values = ContentValues().apply {
            put("cell_key", cellKey)
            put("radio", newest.radio)
            put("operator", newest.operator)
            put("identity_text", newest.identityText)
            put("latitude", latitude)
            put("longitude", longitude)
            put("radius_m", radiusMeters)
            put("sample_count", samples.size)
            put("last_seen_at", newest.recordedAt)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            "tower_estimates",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun distanceMeters(
        latitudeA: Double,
        longitudeA: Double,
        latitudeB: Double,
        longitudeB: Double,
    ): Double {
        val earthRadiusMeters = 6_371_000.0
        val lat1 = Math.toRadians(latitudeA)
        val lat2 = Math.toRadians(latitudeB)
        val deltaLat = Math.toRadians(latitudeB - latitudeA)
        val deltaLon = Math.toRadians(longitudeB - longitudeA)

        val a = sin(deltaLat / 2.0).pow(2.0) +
            cos(lat1) * cos(lat2) * sin(deltaLon / 2.0).pow(2.0)
        return 2.0 * earthRadiusMeters * atan2(sqrt(a), sqrt(1.0 - a))
    }

    private companion object {
        const val DATABASE_NAME = "mylte.db"
        const val DATABASE_VERSION = 2
        const val MAX_MEASUREMENTS_PER_CELL = 5_000
        const val MIN_VALID_DBM = -200
        const val MAX_VALID_DBM = -1
    }
}
