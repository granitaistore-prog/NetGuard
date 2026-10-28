package com.netguard.app

import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.ScanResult
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Socket

object NetworkUtils {

    fun wifiManager(context: Context): WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    /** Переводить ціле число IP (little-endian, як у WifiInfo.ipAddress) у рядок "192.168.0.1". */
    fun intToIp(ip: Int): String {
        if (ip == 0) return "—"
        return "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
    }

    /** Номер Wi-Fi каналу з частоти в МГц (2.4 / 5 / 6 ГГц діапазони). */
    fun channelFromFrequency(freq: Int): Int = when {
        freq == 2484 -> 14
        freq in 2412..2472 -> (freq - 2412) / 5 + 1
        freq in 5160..5885 -> (freq - 5000) / 5
        freq in 5955..7115 -> (freq - 5950) / 5 + 1
        else -> -1
    }

    fun bandLabel(freq: Int): String = when {
        freq < 3000 -> "2.4 ГГц"
        freq < 5900 -> "5 ГГц"
        else -> "6 ГГц"
    }

    /** Визначає тип захисту мережі з поля capabilities результату сканування. */
    fun securityFromCapabilities(caps: String): String = when {
        caps.contains("WPA3") || caps.contains("SAE") -> "WPA3"
        caps.contains("WPA2") -> "WPA2"
        caps.contains("WPA") -> "WPA"
        caps.contains("WEP") -> "WEP (застаріле, небезпечно)"
        else -> "Відкрита (без пароля)"
    }

    fun signalQuality(rssi: Int): Triple<String, Int, String> = when {
        rssi >= -55 -> Triple("Відмінний", R_GOOD, "#69F0AE")
        rssi >= -67 -> Triple("Добрий", R_GOOD, "#69F0AE")
        rssi >= -75 -> Triple("Слабкий", R_WARN, "#FFC107")
        else -> Triple("Дуже слабкий", R_BAD, "#FF5252")
    }
    private const val R_GOOD = 0
    private const val R_WARN = 1
    private const val R_BAD = 2

    /** IPv4-адреса і довжина підмережі поточного Wi-Fi інтерфейсу, якщо є. */
    fun localIpv4WithPrefix(): Pair<String, Int>? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in intf.interfaceAddresses) {
                    val ip = addr.address
                    if (!ip.isLoopbackAddress && ip.hostAddress?.contains(":") == false) {
                        return ip.hostAddress!! to addr.networkPrefixLength.toInt()
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    /** Перевіряє, чи відповідає хост: ICMP-подібна перевірка + швидкий TCP-коннект на типові порти. */
    fun probeHost(ip: String, timeoutMs: Int = 350): Boolean {
        try {
            val addr = InetAddress.getByName(ip)
            if (addr.isReachable(timeoutMs)) return true
        } catch (_: Exception) {
        }
        for (port in intArrayOf(80, 443, 22, 445, 139, 8080, 62078)) {
            try {
                Socket().use { s ->
                    s.connect(java.net.InetSocketAddress(ip, port), 200)
                    return true
                }
            } catch (_: Exception) {
            }
        }
        return false
    }

    fun scanResultsSorted(results: List<ScanResult>): List<ScanResult> =
        results.filter { it.SSID.isNotBlank() }.sortedByDescending { it.level }
}
