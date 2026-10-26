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
 * Precise measurements are aggregated into 50-meter spatial cells. Repeated scans in one cell
 * refine its signal statistics without giving that location additional spatial weight.
 */
class CellDatabase(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    data class CoverageCell(
        val cellKey: String,
        val radio: String,
        val operator: String?,
        val identityText: String,
        val averageSignalDbm: Double,
        val lastSeenAt: Long,
        val south: Double,
        val west: Double,
        val north: Double,
        val east: Double,
    )

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
            CREATE TABLE cell_grid_scans (
                grid_x INTEGER NOT NULL,
                grid_y INTEGER NOT NULL,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                scan_count INTEGER NOT NULL,
                last_scan_at INTEGER NOT NULL,
                PRIMARY KEY(grid_x, grid_y)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE cell_grid_samples (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                cell_key TEXT NOT NULL,
                grid_x INTEGER NOT NULL,
                grid_y INTEGER NOT NULL,
                radio TEXT NOT NULL,
                operator TEXT,
                identity_text TEXT NOT NULL,
                signal_dbm INTEGER NOT NULL,
                recorded_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX idx_cell_grid_samples_key_grid_time ON cell_grid_samples(cell_key, grid_x, grid_y, recorded_at DESC, id DESC)"
        )
        db.execSQL(
            """
            CREATE TABLE cell_grid_stats (
                cell_key TEXT NOT NULL,
                grid_x INTEGER NOT NULL,
                grid_y INTEGER NOT NULL,
                radio TEXT NOT NULL,
                operator TEXT,
                identity_text TEXT NOT NULL,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                sample_count INTEGER NOT NULL,
                signal_sum_dbm INTEGER NOT NULL,
                signal_avg_dbm REAL NOT NULL,
                signal_min_dbm INTEGER NOT NULL,
                signal_max_dbm INTEGER NOT NULL,
                last_seen_at INTEGER NOT NULL,
                PRIMARY KEY(cell_key, grid_x, grid_y)
            )
            """.trimIndent()
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
        if (oldVersion < 3) {
            deleteInvalidIdentityRows(db)
        }
        if (oldVersion < 4) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS cell_grid_scans (
                    grid_x INTEGER NOT NULL,
                    grid_y INTEGER NOT NULL,
                    latitude REAL NOT NULL,
                    longitude REAL NOT NULL,
                    scan_count INTEGER NOT NULL,
                    last_scan_at INTEGER NOT NULL,
                    PRIMARY KEY(grid_x, grid_y)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS cell_grid_stats (
                    cell_key TEXT NOT NULL,
                    grid_x INTEGER NOT NULL,
                    grid_y INTEGER NOT NULL,
                    radio TEXT NOT NULL,
                    operator TEXT,
                    identity_text TEXT NOT NULL,
                    latitude REAL NOT NULL,
                    longitude REAL NOT NULL,
                    sample_count INTEGER NOT NULL,
                    signal_sum_dbm INTEGER NOT NULL,
                    signal_avg_dbm REAL NOT NULL DEFAULT -200,
                    signal_min_dbm INTEGER NOT NULL,
                    signal_max_dbm INTEGER NOT NULL,
                    last_seen_at INTEGER NOT NULL,
                    PRIMARY KEY(cell_key, grid_x, grid_y)
                )
                """.trimIndent()
            )
            migrateLegacyMeasurementsToGrid(db)
        }
        if (oldVersion < 5) {
            db.execSQL(
                "ALTER TABLE cell_grid_stats ADD COLUMN signal_avg_dbm REAL NOT NULL DEFAULT -200"
            )
            db.execSQL(
                """
                UPDATE cell_grid_stats
                SET signal_avg_dbm =
                    CASE
                        WHEN sample_count > 0 THEN signal_sum_dbm * 1.0 / sample_count
                        ELSE -200
                    END,
                    sample_count = MIN(sample_count, $MAX_GRID_CONFIDENCE)
                """.trimIndent()
            )
            db.execSQL(
                "UPDATE cell_grid_scans SET scan_count = MIN(scan_count, $MAX_GRID_CONFIDENCE)"
            )
        }
        if (oldVersion < 6) {
            normalizeGridCoordinates(db)
            db.execSQL("DROP TABLE IF EXISTS cell_measurements")
        }
        if (oldVersion < 7) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS cell_grid_samples (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    cell_key TEXT NOT NULL,
                    grid_x INTEGER NOT NULL,
                    grid_y INTEGER NOT NULL,
                    radio TEXT NOT NULL,
                    operator TEXT,
                    identity_text TEXT NOT NULL,
                    signal_dbm INTEGER NOT NULL,
                    recorded_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_cell_grid_samples_key_grid_time ON cell_grid_samples(cell_key, grid_x, grid_y, recorded_at DESC, id DESC)"
            )
            db.execSQL(
                """
                INSERT INTO cell_grid_samples (
                    cell_key, grid_x, grid_y, radio, operator, identity_text, signal_dbm, recorded_at
                )
                SELECT
                    cell_key, grid_x, grid_y, radio, operator, identity_text,
                    CAST(ROUND(signal_avg_dbm) AS INTEGER),
                    last_seen_at
                FROM cell_grid_stats
                """.trimIndent()
            )
            db.execSQL("UPDATE cell_grid_stats SET sample_count = 1")
        }
    }


    /**
     * Stores one valid precise-GPS cellular scan in the current 50-meter grid cell.
     *
     * At most five recent samples are retained for each cell identity in each grid cell. A valid
     * non-empty scan also removes coverage for cell identities previously seen in this grid cell
     * but absent from the current scan. Empty or unavailable scans never modify coverage.
     */
    fun insertMeasurements(
        records: List<CellTowerRecord>,
        location: Location,
        recordedAt: Long = System.currentTimeMillis(),
    ) {
        val acceptedRecords = records.filter {
            it.persistable && it.signalDbm in MIN_VALID_DBM..MAX_VALID_DBM
        }
        if (acceptedRecords.isEmpty()) return

        val grid = gridCell(location.latitude, location.longitude)
        val observedCellKeys = acceptedRecords.mapTo(linkedSetOf()) { it.cellKey }

        writableDatabase.beginTransaction()
        try {
            upsertGridScan(grid, recordedAt)
            val removedCellKeys = removeAbsentCellsFromGrid(grid, observedCellKeys)

            acceptedRecords.forEach { record ->
                insertGridSample(record, grid, recordedAt)
                pruneGridSamples(record.cellKey, grid)
                rebuildGridStat(record.cellKey, grid)
            }

            val affectedCellKeys = linkedSetOf<String>().apply {
                addAll(observedCellKeys)
                addAll(removedCellKeys)
                addAll(readTowerKeysWithoutCoverage())
            }
            affectedCellKeys.forEach(::updateOrRemoveTowerEstimate)

            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    private data class GridCell(
        val x: Long,
        val y: Long,
    )

    private fun upsertGridScan(grid: GridCell, recordedAt: Long) {
        val center = gridCenter(grid)
        writableDatabase.execSQL(
            """
            INSERT INTO cell_grid_scans (
                grid_x, grid_y, latitude, longitude, scan_count, last_scan_at
            ) VALUES (?, ?, ?, ?, 1, ?)
            ON CONFLICT(grid_x, grid_y) DO UPDATE SET
                latitude = excluded.latitude,
                longitude = excluded.longitude,
                scan_count = cell_grid_scans.scan_count + 1,
                last_scan_at = excluded.last_scan_at
            """.trimIndent(),
            arrayOf(grid.x, grid.y, center.first, center.second, recordedAt),
        )
    }

    private fun removeAbsentCellsFromGrid(
        grid: GridCell,
        observedCellKeys: Set<String>,
    ): Set<String> {
        val previouslyKnown = mutableSetOf<String>()
        readableDatabase.query(
            "cell_grid_stats",
            arrayOf("cell_key"),
            "grid_x = ? AND grid_y = ?",
            arrayOf(grid.x.toString(), grid.y.toString()),
            null,
            null,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                previouslyKnown += cursor.getString(0)
            }
        }

        val removedCellKeys = previouslyKnown - observedCellKeys
        removedCellKeys.forEach { cellKey ->
            writableDatabase.delete(
                "cell_grid_samples",
                "cell_key = ? AND grid_x = ? AND grid_y = ?",
                arrayOf(cellKey, grid.x.toString(), grid.y.toString()),
            )
            writableDatabase.delete(
                "cell_grid_stats",
                "cell_key = ? AND grid_x = ? AND grid_y = ?",
                arrayOf(cellKey, grid.x.toString(), grid.y.toString()),
            )
        }
        return removedCellKeys
    }

    private fun insertGridSample(record: CellTowerRecord, grid: GridCell, recordedAt: Long) {
        val values = ContentValues().apply {
            put("cell_key", record.cellKey)
            put("grid_x", grid.x)
            put("grid_y", grid.y)
            put("radio", record.radio)
            put("operator", record.operator)
            put("identity_text", record.identityLines.joinToString("\n"))
            put("signal_dbm", record.signalDbm)
            put("recorded_at", recordedAt)
        }
        writableDatabase.insertOrThrow("cell_grid_samples", null, values)
    }

    private fun pruneGridSamples(cellKey: String, grid: GridCell) {
        writableDatabase.execSQL(
            """
            DELETE FROM cell_grid_samples
            WHERE cell_key = ? AND grid_x = ? AND grid_y = ?
              AND id NOT IN (
                  SELECT id
                  FROM cell_grid_samples
                  WHERE cell_key = ? AND grid_x = ? AND grid_y = ?
                  ORDER BY recorded_at DESC, id DESC
                  LIMIT $MAX_SAMPLES_PER_CELL_GRID
              )
            """.trimIndent(),
            arrayOf(cellKey, grid.x, grid.y, cellKey, grid.x, grid.y),
        )
    }

    private fun rebuildGridStat(cellKey: String, grid: GridCell) {
        data class Aggregate(
            val radio: String,
            val operator: String?,
            val identityText: String,
            val count: Int,
            val sum: Int,
            val min: Int,
            val max: Int,
            val lastSeenAt: Long,
        )

        var aggregate: Aggregate? = null
        readableDatabase.rawQuery(
            """
            SELECT
                radio,
                operator,
                identity_text,
                COUNT(*),
                SUM(signal_dbm),
                MIN(signal_dbm),
                MAX(signal_dbm),
                MAX(recorded_at)
            FROM cell_grid_samples
            WHERE cell_key = ? AND grid_x = ? AND grid_y = ?
            """.trimIndent(),
            arrayOf(cellKey, grid.x.toString(), grid.y.toString()),
        ).use { cursor ->
            if (cursor.moveToFirst() && cursor.getInt(3) > 0) {
                aggregate = Aggregate(
                    radio = cursor.getString(0),
                    operator = cursor.getString(1),
                    identityText = cursor.getString(2),
                    count = cursor.getInt(3),
                    sum = cursor.getInt(4),
                    min = cursor.getInt(5),
                    max = cursor.getInt(6),
                    lastSeenAt = cursor.getLong(7),
                )
            }
        }

        val value = aggregate
        if (value == null) {
            writableDatabase.delete(
                "cell_grid_stats",
                "cell_key = ? AND grid_x = ? AND grid_y = ?",
                arrayOf(cellKey, grid.x.toString(), grid.y.toString()),
            )
            return
        }

        val center = gridCenter(grid)
        val values = ContentValues().apply {
            put("cell_key", cellKey)
            put("grid_x", grid.x)
            put("grid_y", grid.y)
            put("radio", value.radio)
            put("operator", value.operator)
            put("identity_text", value.identityText)
            put("latitude", center.first)
            put("longitude", center.second)
            put("sample_count", value.count)
            put("signal_sum_dbm", value.sum)
            put("signal_avg_dbm", value.sum.toDouble() / value.count)
            put("signal_min_dbm", value.min)
            put("signal_max_dbm", value.max)
            put("last_seen_at", value.lastSeenAt)
        }
        writableDatabase.insertWithOnConflict(
            "cell_grid_stats",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun readTowerKeysWithoutCoverage(): Set<String> {
        val result = linkedSetOf<String>()
        readableDatabase.rawQuery(
            """
            SELECT tower_estimates.cell_key
            FROM tower_estimates
            LEFT JOIN cell_grid_stats
                ON cell_grid_stats.cell_key = tower_estimates.cell_key
            GROUP BY tower_estimates.cell_key
            HAVING COUNT(cell_grid_stats.cell_key) = 0
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += cursor.getString(0)
            }
        }
        return result
    }

    private fun updateOrRemoveTowerEstimate(cellKey: String) {
        val count = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM cell_grid_stats WHERE cell_key = ?",
            arrayOf(cellKey),
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
        if (count == 0) {
            writableDatabase.delete(
                "tower_estimates",
                "cell_key = ?",
                arrayOf(cellKey),
            )
        } else {
            updateTowerEstimate(cellKey)
        }
    }

    /**
     * Returns observed coverage cells for all known towers.
     */
    fun readCoverageCells(): List<CoverageCell> {
        val result = mutableListOf<CoverageCell>()
        readableDatabase.query(
            "cell_grid_stats",
            arrayOf(
                "cell_key",
                "radio",
                "operator",
                "identity_text",
                "signal_avg_dbm",
                "last_seen_at",
                "grid_x",
                "grid_y",
            ),
            null,
            null,
            null,
            null,
            "last_seen_at DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val grid = GridCell(
                    x = cursor.getLong(6),
                    y = cursor.getLong(7),
                )
                val bounds = gridBounds(grid)
                result += CoverageCell(
                    cellKey = cursor.getString(0),
                    radio = cursor.getString(1),
                    operator = cursor.getString(2),
                    identityText = cursor.getString(3),
                    averageSignalDbm = cursor.getDouble(4),
                    lastSeenAt = cursor.getLong(5),
                    south = bounds[0],
                    west = bounds[1],
                    north = bounds[2],
                    east = bounds[3],
                )
            }
        }
        return result
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

    private fun updateTowerEstimate(
        cellKey: String,
        database: SQLiteDatabase = writableDatabase,
    ) {
        data class GridSample(
            val radio: String,
            val operator: String?,
            val identityText: String,
            val averageSignalDbm: Double,
            val strongestSignalDbm: Int,
            val latitude: Double,
            val longitude: Double,
            val sampleCount: Int,
            val lastSeenAt: Long,
        )

        val samples = mutableListOf<GridSample>()
        database.query(
            "cell_grid_stats",
            arrayOf(
                "radio",
                "operator",
                "identity_text",
                "signal_avg_dbm",
                "sample_count",
                "signal_max_dbm",
                "latitude",
                "longitude",
                "last_seen_at",
            ),
            "cell_key = ?",
            arrayOf(cellKey),
            null,
            null,
            "last_seen_at ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val sampleCount = cursor.getInt(4)
                samples += GridSample(
                    radio = cursor.getString(0),
                    operator = cursor.getString(1),
                    identityText = cursor.getString(2),
                    averageSignalDbm = cursor.getDouble(3),
                    sampleCount = sampleCount,
                    strongestSignalDbm = cursor.getInt(5),
                    latitude = cursor.getDouble(6),
                    longitude = cursor.getDouble(7),
                    lastSeenAt = cursor.getLong(8),
                )
            }
        }
        if (samples.isEmpty()) return

        val strongestDbm = samples.maxOf { it.averageSignalDbm }
        var weightSum = 0.0
        var latitudeSum = 0.0
        var longitudeSum = 0.0
        samples.forEach { sample ->
            val weight = 10.0.pow((sample.averageSignalDbm - strongestDbm) / 10.0)
            weightSum += weight
            latitudeSum += sample.latitude * weight
            longitudeSum += sample.longitude * weight
        }
        val latitude = latitudeSum / weightSum
        val longitude = longitudeSum / weightSum

        var radiusSquaredSum = 0.0
        samples.forEach { sample ->
            val weight = 10.0.pow((sample.averageSignalDbm - strongestDbm) / 10.0)
            val distance = distanceMeters(latitude, longitude, sample.latitude, sample.longitude)
            radiusSquaredSum += weight * distance * distance
        }

        val newest = samples.maxBy { it.lastSeenAt }
        val values = ContentValues().apply {
            put("cell_key", cellKey)
            put("radio", newest.radio)
            put("operator", newest.operator)
            put("identity_text", newest.identityText)
            put("latitude", latitude)
            put("longitude", longitude)
            put("radius_m", sqrt(radiusSquaredSum / weightSum))
            put("sample_count", samples.sumOf { it.sampleCount })
            put("last_seen_at", newest.lastSeenAt)
            put("updated_at", System.currentTimeMillis())
        }
        database.insertWithOnConflict(
            "tower_estimates",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun gridCell(latitude: Double, longitude: Double): GridCell {
        val earthRadiusMeters = 6_378_137.0
        val xMeters = earthRadiusMeters * Math.toRadians(longitude)
        val limitedLatitude = latitude.coerceIn(-85.05112878, 85.05112878)
        val yMeters = earthRadiusMeters *
            kotlin.math.ln(
                kotlin.math.tan(
                    Math.PI / 4.0 + Math.toRadians(limitedLatitude) / 2.0
                )
            )
        return GridCell(
            x = kotlin.math.floor(xMeters / GRID_SIZE_METERS).toLong(),
            y = kotlin.math.floor(yMeters / GRID_SIZE_METERS).toLong(),
        )
    }

    private fun migrateLegacyMeasurementsToGrid(db: SQLiteDatabase) {
        var lastScanAt = Long.MIN_VALUE
        db.query(
            "cell_measurements",
            arrayOf(
                "cell_key", "radio", "operator", "identity_text", "signal_dbm",
                "latitude", "longitude", "recorded_at",
            ),
            null, null, null, null,
            "recorded_at ASC, id ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val latitude = cursor.getDouble(5)
                val longitude = cursor.getDouble(6)
                val recordedAt = cursor.getLong(7)
                val grid = gridCell(latitude, longitude)

                if (recordedAt != lastScanAt) {
                    db.execSQL(
                        """
                        INSERT INTO cell_grid_scans (
                            grid_x, grid_y, latitude, longitude, scan_count, last_scan_at
                        ) VALUES (?, ?, ?, ?, 1, ?)
                        ON CONFLICT(grid_x, grid_y) DO UPDATE SET
                            scan_count = cell_grid_scans.scan_count + 1,
                            last_scan_at = MAX(cell_grid_scans.last_scan_at, excluded.last_scan_at)
                        """.trimIndent(),
                        arrayOf(grid.x, grid.y, latitude, longitude, recordedAt),
                    )
                    lastScanAt = recordedAt
                }

                val signalDbm = cursor.getInt(4)
                db.execSQL(
                    """
                    INSERT INTO cell_grid_stats (
                        cell_key, grid_x, grid_y, radio, operator, identity_text,
                        latitude, longitude, sample_count, signal_sum_dbm, signal_avg_dbm,
                        signal_min_dbm, signal_max_dbm, last_seen_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?)
                    ON CONFLICT(cell_key, grid_x, grid_y) DO UPDATE SET
                        sample_count = MIN(cell_grid_stats.sample_count + 1, $MAX_GRID_CONFIDENCE),
                        signal_sum_dbm = excluded.signal_sum_dbm,
                        signal_avg_dbm =
                            cell_grid_stats.signal_avg_dbm * (1.0 - $GRID_SIGNAL_EMA_ALPHA) +
                            excluded.signal_avg_dbm * $GRID_SIGNAL_EMA_ALPHA,
                        signal_min_dbm = MIN(cell_grid_stats.signal_min_dbm, excluded.signal_min_dbm),
                        signal_max_dbm = MAX(cell_grid_stats.signal_max_dbm, excluded.signal_max_dbm),
                        last_seen_at = MAX(cell_grid_stats.last_seen_at, excluded.last_seen_at)
                    """.trimIndent(),
                    arrayOf(
                        cursor.getString(0), grid.x, grid.y, cursor.getString(1),
                        cursor.getString(2), cursor.getString(3), latitude, longitude,
                        signalDbm, signalDbm.toDouble(), signalDbm, signalDbm, recordedAt,
                    ),
                )
            }
        }
    }

    private fun gridBounds(grid: GridCell): DoubleArray {
        val southWest = mercatorToLatLng(
            xMeters = grid.x * GRID_SIZE_METERS,
            yMeters = grid.y * GRID_SIZE_METERS,
        )
        val northEast = mercatorToLatLng(
            xMeters = (grid.x + 1) * GRID_SIZE_METERS,
            yMeters = (grid.y + 1) * GRID_SIZE_METERS,
        )
        return doubleArrayOf(
            southWest.first,
            southWest.second,
            northEast.first,
            northEast.second,
        )
    }

    private fun mercatorToLatLng(xMeters: Double, yMeters: Double): Pair<Double, Double> {
        val earthRadiusMeters = 6_378_137.0
        val longitude = Math.toDegrees(xMeters / earthRadiusMeters)
        val latitude = Math.toDegrees(
            2.0 * kotlin.math.atan(kotlin.math.exp(yMeters / earthRadiusMeters)) -
                Math.PI / 2.0
        )
        return latitude to longitude
    }

    private fun gridCenter(grid: GridCell): Pair<Double, Double> {
        return mercatorToLatLng(
            xMeters = (grid.x + 0.5) * GRID_SIZE_METERS,
            yMeters = (grid.y + 0.5) * GRID_SIZE_METERS,
        )
    }

    private fun normalizeGridCoordinates(db: SQLiteDatabase) {
        db.query(
            "cell_grid_scans",
            arrayOf("grid_x", "grid_y"),
            null, null, null, null, null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val grid = GridCell(cursor.getLong(0), cursor.getLong(1))
                val center = gridCenter(grid)
                val values = ContentValues().apply {
                    put("latitude", center.first)
                    put("longitude", center.second)
                }
                db.update(
                    "cell_grid_scans",
                    values,
                    "grid_x = ? AND grid_y = ?",
                    arrayOf(grid.x.toString(), grid.y.toString()),
                )
                db.update(
                    "cell_grid_stats",
                    values,
                    "grid_x = ? AND grid_y = ?",
                    arrayOf(grid.x.toString(), grid.y.toString()),
                )
            }
        }

        db.query(
            "cell_grid_stats",
            arrayOf("DISTINCT cell_key"),
            null, null, null, null, null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                updateTowerEstimate(cursor.getString(0), db)
            }
        }
    }

    private fun deleteInvalidIdentityRows(db: SQLiteDatabase) {
        val invalidWhere = """
            cell_key LIKE '%:null:%'
            OR cell_key LIKE '%:2147483647:%'
            OR cell_key LIKE '%:9223372036854775807%'
        """.trimIndent()
        if (tableExists(db, "cell_measurements")) {
            db.delete("cell_measurements", invalidWhere, null)
        }
        db.delete("tower_estimates", invalidWhere, null)
    }

    private fun tableExists(db: SQLiteDatabase, tableName: String): Boolean {
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
            arrayOf(tableName),
        ).use { cursor ->
            return cursor.moveToFirst()
        }
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
        const val DATABASE_VERSION = 7
        const val GRID_SIZE_METERS = 50.0
        const val MAX_GRID_CONFIDENCE = 20
        const val GRID_SIGNAL_EMA_ALPHA = 0.2
        const val MAX_SAMPLES_PER_CELL_GRID = 5
        const val MIN_VALID_DBM = -200
        const val MAX_VALID_DBM = -1
    }
}
