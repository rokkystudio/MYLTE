package badfox.logic.mylte

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * Collects cellular signal measurements paired only with accepted precise GPS fixes.
 *
 * The service accepts fixes from GPS_PROVIDER with reported accuracy no worse than 20 meters
 * and no older than 30 seconds. Accepted signal + GPS samples are stored in SQLite and used
 * to refresh cached tower coordinate/radius estimates.
 */
class CellCollectorService : Service(), LocationListener {
    private lateinit var cellRepository: CellRepository
    private lateinit var cellDatabase: CellDatabase
    private lateinit var locationManager: LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private val databaseExecutor = Executors.newSingleThreadExecutor()
    private var collectionScheduled = false
    private var gpsUpdatesRegistered = false
    private var latestPreciseLocation: Location? = null

    private val collectRunnable = object : Runnable {
        override fun run() {
            collectSnapshot()
            handler.postDelayed(this, COLLECTION_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        cellRepository = CellRepository(applicationContext)
        cellDatabase = CellDatabase(applicationContext)
        locationManager = getSystemService(LocationManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCollector()
            return START_NOT_STICKY
        }

        setCollectorEnabled(true)
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.collector_notification_starting)))
        startGpsUpdates()
        scheduleCollection()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(collectRunnable)
        collectionScheduled = false
        if (gpsUpdatesRegistered) {
            locationManager.removeUpdates(this)
            gpsUpdatesRegistered = false
        }
        databaseExecutor.execute {
            cellDatabase.close()
        }
        databaseExecutor.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onLocationChanged(location: Location) {
        if (!isAcceptedPreciseGps(location)) return
        latestPreciseLocation = Location(location)
        GpsLocationStore.save(applicationContext, location)
        sendBroadcast(
            Intent(ACTION_LOCATION_UPDATED)
                .setPackage(packageName)
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
    }

    override fun onProviderEnabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) {
            latestPreciseLocation = null
        }
    }

    override fun onProviderDisabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) {
            latestPreciseLocation = null
        }
    }

    private fun scheduleCollection() {
        if (collectionScheduled) return
        collectionScheduled = true
        handler.removeCallbacks(collectRunnable)
        handler.post(collectRunnable)
    }

    private fun collectSnapshot() {
        val snapshot = cellRepository.readSnapshot()
        val location = latestPreciseLocation
            ?.takeIf(::isAcceptedPreciseGps)
            ?.takeIf { System.currentTimeMillis() - it.time <= MAX_GPS_FIX_AGE_MS }

        val notificationText = when {
            snapshot == CellSnapshot.PermissionRequired ->
                getString(R.string.collector_notification_permissions)

            snapshot == CellSnapshot.Unavailable ->
                getString(R.string.collector_notification_unavailable)

            snapshot is CellSnapshot.Available -> {
                val records = snapshot.records
                val acceptedLocation = location?.let(::Location)
                databaseExecutor.execute {
                    cellDatabase.touchObservedCells(records)
                    if (acceptedLocation != null) {
                        cellDatabase.insertMeasurements(records, acceptedLocation)
                    }
                    sendBroadcast(
                        Intent(ACTION_SNAPSHOT_COLLECTED)
                            .setPackage(packageName)
                    )
                }

                if (acceptedLocation == null) {
                    getString(
                        R.string.collector_notification_waiting_precise_gps,
                        MAX_GPS_ACCURACY_METERS.toInt(),
                    )
                } else {
                    val registered = records.count { it.registered }
                    val neighbors = records.size - registered
                    getString(
                        R.string.collector_notification_cells_with_gps,
                        registered,
                        neighbors,
                        acceptedLocation.accuracy.toInt(),
                    )
                }
            }

            else -> getString(R.string.collector_notification_unavailable)
        }

        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(notificationText))
    }

    private fun startGpsUpdates() {
        if (gpsUpdatesRegistered) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            latestPreciseLocation = null
        }
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            GPS_UPDATE_INTERVAL_MS,
            0f,
            this,
            Looper.getMainLooper(),
        )
        gpsUpdatesRegistered = true
        locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?.takeIf(::isAcceptedPreciseGps)
            ?.takeIf { System.currentTimeMillis() - it.time <= MAX_GPS_FIX_AGE_MS }
            ?.let {
                latestPreciseLocation = Location(it)
                GpsLocationStore.save(applicationContext, it)
            }
    }

    private fun isAcceptedPreciseGps(location: Location): Boolean {
        return location.provider == LocationManager.GPS_PROVIDER &&
            location.hasAccuracy() &&
            location.accuracy <= MAX_GPS_ACCURACY_METERS
    }

    private fun buildNotification(text: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, CellCollectorService::class.java)
                .setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.mylte_launcher)
            .setContentTitle(getString(R.string.collector_notification_title))
            .setContentText(text)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.collector_action_stop), stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.collector_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.collector_channel_description)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun stopCollector() {
        setCollectorEnabled(false)
        handler.removeCallbacks(collectRunnable)
        collectionScheduled = false
        if (gpsUpdatesRegistered) {
            locationManager.removeUpdates(this)
            gpsUpdatesRegistered = false
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        sendBroadcast(
            Intent(ACTION_STATE_CHANGED)
                .setPackage(packageName)
        )
    }

    private fun setCollectorEnabled(enabled: Boolean) {
        getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_COLLECTOR_ENABLED, enabled)
            .apply()

        sendBroadcast(
            Intent(ACTION_STATE_CHANGED)
                .setPackage(packageName)
        )
    }

    companion object {
        const val ACTION_SNAPSHOT_COLLECTED = "badfox.logic.mylte.action.SNAPSHOT_COLLECTED"
        const val ACTION_STATE_CHANGED = "badfox.logic.mylte.action.COLLECTOR_STATE_CHANGED"
        const val ACTION_LOCATION_UPDATED = "badfox.logic.mylte.action.LOCATION_UPDATED"

        private const val ACTION_START = "badfox.logic.mylte.action.START_COLLECTOR"
        private const val ACTION_STOP = "badfox.logic.mylte.action.STOP_COLLECTOR"
        private const val PREFERENCES_NAME = "cell_collector"
        private const val KEY_COLLECTOR_ENABLED = "enabled"
        private const val NOTIFICATION_CHANNEL_ID = "cell_collector"
        private const val NOTIFICATION_ID = 1001
        private const val COLLECTION_INTERVAL_MS = 15_000L
        private const val GPS_UPDATE_INTERVAL_MS = 1_000L
        private const val MAX_GPS_FIX_AGE_MS = 30_000L
        const val MAX_GPS_ACCURACY_METERS = 20f

        /**
         * Starts the foreground cellular collector.
         */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CellCollectorService::class.java)
                    .setAction(ACTION_START),
            )
        }

        /**
         * Disables the foreground cellular collector.
         */
        fun stop(context: Context) {
            context.startService(
                Intent(context, CellCollectorService::class.java)
                    .setAction(ACTION_STOP),
            )
        }

        /**
         * Returns whether the collector is enabled by the user.
         */
        fun isEnabled(context: Context): Boolean {
            return context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_COLLECTOR_ENABLED, false)
        }
    }
}
