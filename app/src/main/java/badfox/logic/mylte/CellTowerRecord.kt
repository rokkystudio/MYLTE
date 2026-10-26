package badfox.logic.mylte

/**
 * Describes one cellular record exposed by Android Telephony APIs.
 */
data class CellTowerRecord(
    val cellKey: String,
    val radio: String,
    val registered: Boolean,
    val operator: String?,
    val identityLines: List<String>,
    val signalLines: List<String>,
    val signalDbm: Int,
    val persistable: Boolean,
) {
    fun title(): String {
        return buildString {
            append(radio)
            if (registered) append(" · connected") else append(" · neighbor")
            operator?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
        }
    }

    fun details(): String {
        return (identityLines + signalLines)
            .filter { it.isNotBlank() }
            .joinToString(separator = "\n")
    }
}
