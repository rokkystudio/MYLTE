package badfox.logic.mylte

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoTdscdma
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrength
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/**
 * Reads current operator and cell identity data from Android TelephonyManager.
 */
class CellRepository(private val context: Context) {
    private val telephonyManager: TelephonyManager?
        get() = context.getSystemService(TelephonyManager::class.java)

    fun hasRequiredPermissions(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val phone = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        return fine && phone
    }

    @SuppressLint("MissingPermission")
    fun readSnapshot(): CellSnapshot {
        val manager = telephonyManager ?: return CellSnapshot.Unavailable
        if (!hasRequiredPermissions()) return CellSnapshot.PermissionRequired
        val cells = manager.allCellInfo.orEmpty().mapNotNull(::toRecord)
        return CellSnapshot.Available(
            operatorName = manager.networkOperatorName.orEmpty(),
            operatorCode = manager.networkOperator.orEmpty(),
            simOperatorName = manager.simOperatorName.orEmpty(),
            simOperatorCode = manager.simOperator.orEmpty(),
            networkType = networkTypeName(manager.dataNetworkType),
            phoneType = phoneTypeName(manager.phoneType),
            records = cells,
        )
    }

    private fun toRecord(cellInfo: CellInfo): CellTowerRecord? {
        return when (cellInfo) {
            is CellInfoLte -> lteRecord(cellInfo)
            is CellInfoGsm -> gsmRecord(cellInfo)
            is CellInfoWcdma -> wcdmaRecord(cellInfo)
            is CellInfoCdma -> cdmaRecord(cellInfo)
            is CellInfoTdscdma -> tdscdmaRecord(cellInfo)
            is CellInfoNr -> nrRecord(cellInfo)
            else -> null
        }
    }

    private fun lteRecord(cellInfo: CellInfoLte): CellTowerRecord {
        val identity = cellInfo.cellIdentity
        val signal = cellInfo.cellSignalStrength
        return CellTowerRecord(
            cellKey = listOf("LTE", identity.mccString, identity.mncString, identity.tac, identity.ci).joinToString(":"),
            radio = "LTE",
            registered = cellInfo.isRegistered,
            operator = operator(identity.mccString, identity.mncString),
            identityLines = listOf(
                "CI: ${identity.ci}",
                "PCI: ${identity.pci}",
                "TAC: ${identity.tac}",
                "EARFCN: ${identity.earfcn}",
                "Bands: ${formatBands(identity.bands)}",
            ),
            signalLines = signalLines(signal) + listOf(
                "RSRP: ${signal.rsrp} dBm",
                "RSRQ: ${signal.rsrq} dB",
                "RSSNR: ${signal.rssnr}",
            ),
            signalDbm = signal.dbm,
            persistable = identity.mccString != null &&
                identity.mncString != null &&
                identity.tac != Int.MAX_VALUE &&
                identity.ci != Int.MAX_VALUE,
        )
    }

    private fun gsmRecord(cellInfo: CellInfoGsm): CellTowerRecord {
        val identity = cellInfo.cellIdentity
        val signal = cellInfo.cellSignalStrength
        return CellTowerRecord(
            cellKey = listOf("GSM", identity.mccString, identity.mncString, identity.lac, identity.cid).joinToString(":"),
            radio = "GSM",
            registered = cellInfo.isRegistered,
            operator = operator(identity.mccString, identity.mncString),
            identityLines = listOf(
                "CID: ${identity.cid}",
                "LAC: ${identity.lac}",
                "ARFCN: ${identity.arfcn}",
                "BSIC: ${identity.bsic}",
            ),
            signalLines = signalLines(signal),
            signalDbm = signal.dbm,
            persistable = identity.mccString != null &&
                identity.mncString != null &&
                identity.lac != Int.MAX_VALUE &&
                identity.cid != Int.MAX_VALUE,
        )
    }

    private fun wcdmaRecord(cellInfo: CellInfoWcdma): CellTowerRecord {
        val identity = cellInfo.cellIdentity
        val signal = cellInfo.cellSignalStrength
        return CellTowerRecord(
            cellKey = listOf("WCDMA", identity.mccString, identity.mncString, identity.lac, identity.cid).joinToString(":"),
            radio = "WCDMA",
            registered = cellInfo.isRegistered,
            operator = operator(identity.mccString, identity.mncString),
            identityLines = listOf(
                "CID: ${identity.cid}",
                "LAC: ${identity.lac}",
                "PSC: ${identity.psc}",
                "UARFCN: ${identity.uarfcn}",
            ),
            signalLines = signalLines(signal),
            signalDbm = signal.dbm,
            persistable = identity.mccString != null &&
                identity.mncString != null &&
                identity.lac != Int.MAX_VALUE &&
                identity.cid != Int.MAX_VALUE,
        )
    }

    private fun cdmaRecord(cellInfo: CellInfoCdma): CellTowerRecord {
        val identity = cellInfo.cellIdentity
        val signal = cellInfo.cellSignalStrength
        return CellTowerRecord(
            cellKey = listOf("CDMA", identity.systemId, identity.networkId, identity.basestationId).joinToString(":"),
            radio = "CDMA",
            registered = cellInfo.isRegistered,
            operator = null,
            identityLines = listOf(
                "Network ID: ${identity.networkId}",
                "System ID: ${identity.systemId}",
                "Basestation ID: ${identity.basestationId}",
                "Latitude: ${identity.latitude}",
                "Longitude: ${identity.longitude}",
            ),
            signalLines = signalLines(signal),
            signalDbm = signal.dbm,
            persistable = identity.systemId != Int.MAX_VALUE &&
                identity.networkId != Int.MAX_VALUE &&
                identity.basestationId != Int.MAX_VALUE,
        )
    }

    private fun tdscdmaRecord(cellInfo: CellInfoTdscdma): CellTowerRecord {
        val identity = cellInfo.cellIdentity
        val signal = cellInfo.cellSignalStrength
        return CellTowerRecord(
            cellKey = listOf("TD-SCDMA", identity.mccString, identity.mncString, identity.lac, identity.cid).joinToString(":"),
            radio = "TD-SCDMA",
            registered = cellInfo.isRegistered,
            operator = operator(identity.mccString, identity.mncString),
            identityLines = listOf(
                "CID: ${identity.cid}",
                "LAC: ${identity.lac}",
                "CPID: ${identity.cpid}",
                "UARFCN: ${identity.uarfcn}",
            ),
            signalLines = signalLines(signal),
            signalDbm = signal.dbm,
            persistable = identity.mccString != null &&
                identity.mncString != null &&
                identity.lac != Int.MAX_VALUE &&
                identity.cid != Int.MAX_VALUE,
        )
    }

    private fun nrRecord(cellInfo: CellInfoNr): CellTowerRecord {
        val identity = cellInfo.cellIdentity as? android.telephony.CellIdentityNr
        val signal = cellInfo.cellSignalStrength
        return CellTowerRecord(
            cellKey = listOf("NR", identity?.mccString, identity?.mncString, identity?.tac, identity?.nci).joinToString(":"),
            radio = "NR",
            registered = cellInfo.isRegistered,
            operator = operator(identity?.mccString, identity?.mncString),
            identityLines = listOf(
                "NCI: ${identity?.nci}",
                "PCI: ${identity?.pci}",
                "TAC: ${identity?.tac}",
                "NRARFCN: ${identity?.nrarfcn}",
                "Bands: ${formatBands(identity?.bands)}",
            ),
            signalLines = signalLines(signal),
            signalDbm = signal.dbm,
            persistable = identity?.mccString != null &&
                identity.mncString != null &&
                identity.tac != Int.MAX_VALUE &&
                identity.nci != Long.MAX_VALUE,
        )
    }

    private fun signalLines(signal: CellSignalStrength): List<String> {
        return listOf(
            "Level: ${signal.level}",
            "ASU: ${signal.asuLevel}",
            "dBm: ${signal.dbm}",
        )
    }

    private fun operator(mcc: String?, mnc: String?): String? {
        if (mcc.isNullOrBlank() && mnc.isNullOrBlank()) return null
        return listOfNotNull(mcc?.takeIf { it.isNotBlank() }, mnc?.takeIf { it.isNotBlank() }).joinToString("/")
    }

    private fun formatBands(bands: IntArray?): String {
        return bands?.takeIf { it.isNotEmpty() }?.joinToString() ?: "n/a"
    }

    private fun networkTypeName(type: Int): String {
        return when (type) {
            TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
            TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
            TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
            TelephonyManager.NETWORK_TYPE_CDMA -> "CDMA"
            TelephonyManager.NETWORK_TYPE_EVDO_0 -> "EVDO_0"
            TelephonyManager.NETWORK_TYPE_EVDO_A -> "EVDO_A"
            TelephonyManager.NETWORK_TYPE_1xRTT -> "1xRTT"
            TelephonyManager.NETWORK_TYPE_HSDPA -> "HSDPA"
            TelephonyManager.NETWORK_TYPE_HSUPA -> "HSUPA"
            TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
            TelephonyManager.NETWORK_TYPE_IDEN -> "IDEN"
            TelephonyManager.NETWORK_TYPE_EVDO_B -> "EVDO_B"
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
            TelephonyManager.NETWORK_TYPE_EHRPD -> "EHRPD"
            TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPAP"
            TelephonyManager.NETWORK_TYPE_GSM -> "GSM"
            TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "TD-SCDMA"
            TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
            TelephonyManager.NETWORK_TYPE_NR -> "NR"
            else -> "UNKNOWN($type)"
        }
    }

    private fun phoneTypeName(type: Int): String {
        return when (type) {
            TelephonyManager.PHONE_TYPE_GSM -> "GSM"
            TelephonyManager.PHONE_TYPE_CDMA -> "CDMA"
            TelephonyManager.PHONE_TYPE_SIP -> "SIP"
            TelephonyManager.PHONE_TYPE_NONE -> "NONE"
            else -> "UNKNOWN($type)"
        }
    }
}

sealed class CellSnapshot {
    data object PermissionRequired : CellSnapshot()
    data object Unavailable : CellSnapshot()

    data class Available(
        val operatorName: String,
        val operatorCode: String,
        val simOperatorName: String,
        val simOperatorCode: String,
        val networkType: String,
        val phoneType: String,
        val records: List<CellTowerRecord>,
    ) : CellSnapshot()
}