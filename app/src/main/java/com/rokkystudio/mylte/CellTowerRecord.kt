package com.rokkystudio.mylte

/**
 * Describes one cellular record exposed by Android Telephony APIs.
 */
data class CellTowerRecord(
    val radio: String,
    val registered: Boolean,
    val operator: String?,
    val identityLines: List<String>,
    val signalLines: List<String>,
    val latitude: Double? = null,
    val longitude: Double? = null,
) {
    fun title(): String {
        return buildString {
            append(radio)
            if (registered) append(" В· connected") else append(" В· neighbor")
            operator?.takeIf { it.isNotBlank() }?.let { append(" В· ").append(it) }
        }
    }

    fun details(): String {
        return (identityLines + signalLines)
            .filter { it.isNotBlank() }
            .joinToString(separator = "\n")
    }

    fun hasCoordinates(): Boolean = latitude != null && longitude != null
}