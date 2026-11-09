package io.nekohasekai.sfa.bg

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoTdscdma
import android.telephony.CellInfoWcdma
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import io.nekohasekai.libbox.CellularState
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.compose.screen.smart.SmartSettings
import io.nekohasekai.sfa.ktx.hasPermission
import java.util.Locale
import java.util.TimeZone

internal object SmartPlatform {
    @SuppressLint("MissingPermission")
    fun readCellularState(): CellularState? = runCatching {
        // The monitor tracks the underlying default network; activeNetwork may be our VPN.
        val network = DefaultNetworkMonitor.defaultNetwork ?: return@runCatching null
        val capabilities = Application.connectivity.getNetworkCapabilities(network) ?: return@runCatching null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        ) {
            return@runCatching null
        }
        val state = CellularState()
        val telephony = dataTelephonyManager() ?: return@runCatching state
        val operator = runCatching { telephony.networkOperator }.getOrNull().orEmpty().validOperator()
        setOperator(state, operator)
        state.carrier = runCatching { telephony.networkOperatorName }.getOrNull().orEmpty()
        if (Application.application.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            runCatching {
                val cell = servingCell(telephony.allCellInfo.orEmpty(), operator) ?: return@runCatching
                setOperator(state, cellOperator(cell, operator))
                readCellIdentity(state, cell)
                @Suppress("DEPRECATION")
                val timestamp = cell.timeStamp
                state.observedAtUnixMillis = cellTimestampToUnixMillis(
                    timestamp,
                    SystemClock.elapsedRealtimeNanos(),
                    System.currentTimeMillis(),
                )
            }
        }
        if (state.radioType.isEmpty() && Application.application.hasPermission(Manifest.permission.READ_PHONE_STATE)) {
            state.radioType = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    radioType(telephony.dataNetworkType)
                } else {
                    @Suppress("DEPRECATION")
                    radioType(telephony.networkType)
                }
            }.getOrDefault("")
        }
        state
    }.getOrNull()

    @Synchronized
    fun systemCountryCode(): String {
        val stored = selectSmartCountry(
            countryOverride = runCatching { SmartSettings.countryOverride }.getOrNull(),
            automaticCountry = runCatching { SmartSettings.automaticCountry }.getOrNull(),
        )
        if (stored.isNotEmpty()) return stored
        val telephony = dataTelephonyManager() ?: telephonyManager()
        val country = selectSmartCountry(
            networkCountry = runCatching { telephony?.networkCountryIso }.getOrNull(),
            simCountry = runCatching { telephony?.simCountryIso }.getOrNull(),
            timezoneCountry = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    android.icu.util.TimeZone.getRegion(TimeZone.getDefault().id)
                } else {
                    // Legacy Android has no public timezone-to-country API; do not guess from offsets.
                    ""
                }
            }.getOrNull(),
            localeCountry = runCatching { Locale.getDefault().country }.getOrNull(),
        )
        if (country.isNotEmpty()) {
            // Persist the first automatic country separately so clearing an override still means auto.
            runCatching { SmartSettings.automaticCountry = country }
        }
        return country
    }

    fun isPowerSaveMode(): Boolean = runCatching {
        val manager = Application.application.getSystemService(Context.POWER_SERVICE) as? PowerManager
        manager?.isPowerSaveMode ?: false
    }.getOrDefault(false)

    fun systemTimezoneOffsetSeconds(): Int = runCatching {
        TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000
    }.getOrDefault(0)

    private fun telephonyManager(): TelephonyManager? = runCatching {
        Application.application.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    }.getOrNull()

    private fun dataTelephonyManager(): TelephonyManager? = runCatching {
        val manager = telephonyManager() ?: return@runCatching null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return@runCatching manager
        val subscription = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            SubscriptionManager.getActiveDataSubscriptionId()
        } else {
            SubscriptionManager.getDefaultDataSubscriptionId()
        }
        if (subscription < 0) return@runCatching null
        manager.createForSubscriptionId(subscription)
    }.getOrNull()

    private fun servingCell(cells: List<CellInfo>, operator: String): CellInfo? {
        var registered: CellInfo? = null
        var registeredCount = 0
        var primary: CellInfo? = null
        var primaryCount = 0
        for (cell in cells) {
            if (!cell.isRegistered) continue
            if (operator.isNotEmpty() && cellOperator(cell, operator) != operator) continue
            registered = cell
            registeredCount++
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                cell.cellConnectionStatus == CellInfo.CONNECTION_PRIMARY_SERVING
            ) {
                primary = cell
                primaryCount++
            }
        }
        // getAllCellInfo can contain both SIMs; ambiguous serving cells must not create a false tower key.
        return if (primaryCount == 1) {
            primary
        } else if (registeredCount == 1) {
            registered
        } else {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun cellOperator(cell: CellInfo, operator: String): String = when {
        cell is CellInfoLte -> cell.cellIdentity.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                it.mccString.orEmpty() + it.mncString.orEmpty()
            } else {
                legacyOperator(it.mcc, it.mnc, operator)
            }
        }
        cell is CellInfoGsm -> cell.cellIdentity.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                it.mccString.orEmpty() + it.mncString.orEmpty()
            } else {
                legacyOperator(it.mcc, it.mnc, operator)
            }
        }
        cell is CellInfoWcdma -> cell.cellIdentity.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                it.mccString.orEmpty() + it.mncString.orEmpty()
            } else {
                legacyOperator(it.mcc, it.mnc, operator)
            }
        }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cell is CellInfoNr -> {
            val identity = cell.cellIdentity as CellIdentityNr
            identity.mccString.orEmpty() + identity.mncString.orEmpty()
        }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cell is CellInfoTdscdma -> cell.cellIdentity.let {
            it.mccString.orEmpty() + it.mncString.orEmpty()
        }
        else -> ""
    }.validOperator()

    private fun legacyOperator(mcc: Int, mnc: Int, operator: String): String {
        // Integer MNCs lose leading zeroes and their width; only retain the known network spelling.
        if (operator.isEmpty()) return ""
        return operator.takeIf { it.take(3).toIntOrNull() == mcc && it.drop(3).toIntOrNull() == mnc }.orEmpty()
    }

    private fun setOperator(state: CellularState, operator: String) {
        if (operator.isEmpty()) return
        state.setMCC(operator.take(3))
        state.setMNC(operator.drop(3))
    }

    private fun readCellIdentity(state: CellularState, cell: CellInfo) {
        when {
            cell is CellInfoLte -> {
                state.radioType = "LTE"
                state.areaCode = knownCellValue(cell.cellIdentity.tac)
                state.setCellID(knownCellValue(cell.cellIdentity.ci))
            }
            cell is CellInfoGsm -> {
                state.radioType = "GSM"
                state.areaCode = knownCellValue(cell.cellIdentity.lac)
                state.setCellID(knownCellValue(cell.cellIdentity.cid))
            }
            cell is CellInfoWcdma -> {
                state.radioType = "UMTS"
                state.areaCode = knownCellValue(cell.cellIdentity.lac)
                state.setCellID(knownCellValue(cell.cellIdentity.cid))
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cell is CellInfoNr -> {
                val identity = cell.cellIdentity as CellIdentityNr
                state.radioType = "NR"
                state.areaCode = knownCellValue(identity.tac)
                state.setCellID(knownCellValue(identity.nci))
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cell is CellInfoTdscdma -> {
                state.radioType = "UMTS"
                state.areaCode = knownCellValue(cell.cellIdentity.lac)
                state.setCellID(knownCellValue(cell.cellIdentity.cid))
            }
        }
    }

    private fun radioType(networkType: Int): String = when (networkType) {
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_NR -> "NR"
        TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_HSPAP,
        -> "HSPA"
        TelephonyManager.NETWORK_TYPE_UMTS,
        TelephonyManager.NETWORK_TYPE_TD_SCDMA,
        -> "UMTS"
        TelephonyManager.NETWORK_TYPE_GPRS,
        TelephonyManager.NETWORK_TYPE_EDGE,
        TelephonyManager.NETWORK_TYPE_GSM,
        -> "GSM"
        else -> ""
    }

    private fun String.validOperator(): String = takeIf { length in 5..6 && all { it in '0'..'9' } }.orEmpty()

    private fun knownCellValue(value: Int): String = value.takeIf { it >= 0 && it != Int.MAX_VALUE }?.toString().orEmpty()

    private fun knownCellValue(value: Long): String = value.takeIf { it >= 0 && it != Long.MAX_VALUE }?.toString().orEmpty()
}

private val isoCountries = Locale.getISOCountries().toSet()

internal fun selectSmartCountry(
    countryOverride: String? = null,
    automaticCountry: String? = null,
    networkCountry: String? = null,
    simCountry: String? = null,
    timezoneCountry: String? = null,
    localeCountry: String? = null,
): String = normalizedCountry(countryOverride)
    ?: normalizedCountry(automaticCountry)
    ?: normalizedCountry(networkCountry)
    ?: normalizedCountry(simCountry)
    ?: normalizedCountry(timezoneCountry)
    ?: normalizedCountry(localeCountry)
    ?: ""

private fun normalizedCountry(value: String?): String? {
    val country = value?.trim() ?: return null
    if (country.length != 2 || country.any { it !in 'A'..'Z' && it !in 'a'..'z' }) return null
    return country.uppercase(Locale.ROOT).takeIf { it in isoCountries }
}

internal fun cellTimestampToUnixMillis(
    observedAtNanos: Long,
    elapsedRealtimeNanos: Long,
    nowUnixMillis: Long,
): Long {
    if (observedAtNanos <= 0 || observedAtNanos == Long.MAX_VALUE ||
        elapsedRealtimeNanos < observedAtNanos || nowUnixMillis <= 0
    ) {
        return 0
    }
    val ageMillis = (elapsedRealtimeNanos - observedAtNanos) / 1_000_000L
    return if (ageMillis < nowUnixMillis) nowUnixMillis - ageMillis else 0
}
