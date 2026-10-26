package badfox.logic.mylte

import android.content.Context
import android.location.Location

/**
 * Persists the latest accepted precise GPS fix for map recentering and UI display.
 */
object GpsLocationStore {
    private const val PREFERENCES_NAME = "gps_location"
    private const val KEY_LATITUDE = "latitude"
    private const val KEY_LONGITUDE = "longitude"
    private const val KEY_ACCURACY = "accuracy"
    private const val KEY_TIME = "time"
    private const val KEY_AVAILABLE = "available"

    fun save(context: Context, location: Location) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AVAILABLE, true)
            .putLong(KEY_LATITUDE, location.latitude.toBits())
            .putLong(KEY_LONGITUDE, location.longitude.toBits())
            .putFloat(KEY_ACCURACY, location.accuracy)
            .putLong(KEY_TIME, location.time)
            .apply()
    }

    fun read(context: Context): Location? {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        if (!preferences.getBoolean(KEY_AVAILABLE, false)) return null
        return Location("stored-gps").apply {
            latitude = Double.fromBits(preferences.getLong(KEY_LATITUDE, 0L))
            longitude = Double.fromBits(preferences.getLong(KEY_LONGITUDE, 0L))
            accuracy = preferences.getFloat(KEY_ACCURACY, Float.MAX_VALUE)
            time = preferences.getLong(KEY_TIME, 0L)
        }
    }
}
