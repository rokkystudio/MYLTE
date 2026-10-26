package badfox.logic.mylte

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

/**
 * Periodically reads cellular network data while running as a foreground service.
 *
 * The service keeps an ongoing notification visible, updates it after every collection cycle,
 * and broadcasts collection events so the activity can refresh the currently displayed snapshot.
 */
class CellCollectorService : Service() {
    private lateinit var cellRepository: CellRepository
    private val handler = Handler(Looper.getMainLooper())
    private var collectionScheduled = false

    private val collectRunnable = object : Runnable {
        override fun run() {
            collectSnapshot()
            handler.postDelayed(this, COLLECTION_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        cellRepository = CellRepository(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCollector()
            return START_NOT_STICKY
        }

        setCollectorEnabled(true)
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.collector_notification_starting)))
        scheduleCollection()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(collectRunnable)
        collectionScheduled = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun scheduleCollection() {
        if (collectionScheduled) return
        collectionScheduled = true
        handler.removeCallbacks(collectRunnable)
        handler.post(collectRunnable)
    }

    private fun collectSnapshot() {
        val snapshot = cellRepository.readSnapshot()
        val notificationText = when (snapshot) {
            CellSnapshot.PermissionRequired -> getString(R.string.collector_notification_permissions)
            CellSnapshot.Unavailable -> getString(R.string.collector_notification_unavailable)
            is CellSnapshot.Available -> {
                val registered = snapshot.records.count { it.registered }
                val neighbors = snapshot.records.size - registered
                getString(R.string.collector_notification_cells, registered, neighbors)
            }
        }

        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(notificationText))

        sendBroadcast(
            Intent(ACTION_SNAPSHOT_COLLECTED)
                .setPackage(packageName)
        )
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

        private const val ACTION_START = "badfox.logic.mylte.action.START_COLLECTOR"
        private const val ACTION_STOP = "badfox.logic.mylte.action.STOP_COLLECTOR"
        private const val PREFERENCES_NAME = "cell_collector"
        private const val KEY_COLLECTOR_ENABLED = "enabled"
        private const val NOTIFICATION_CHANNEL_ID = "cell_collector"
        private const val NOTIFICATION_ID = 1001
        private const val COLLECTION_INTERVAL_MS = 15_000L

        /**
         * Starts the foreground cellular collector.
         */
        fun start(context: Context) {
            androidx.core.content.ContextCompat.startForegroundService(
                context,
                Intent(context, CellCollectorService::class.java)
                    .setAction(ACTION_START),
            )
        }

        /**
         * Stops the foreground cellular collector.
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
