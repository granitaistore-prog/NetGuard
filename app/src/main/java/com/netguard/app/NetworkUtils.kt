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

    // ---------- Зовнішня IP-адреса й перевірка відкритих портів ----------

    /** Отримує публічну (зовнішню) IP-адресу через простий текстовий сервіс. Виконувати НЕ в головному потоці. */
    fun fetchPublicIp(): String? {
        for (urlStr in listOf("https://api.ipify.org", "https://ifconfig.me/ip")) {
            try {
                val conn = (java.net.URL(urlStr).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 3000
                    readTimeout = 3000
                    requestMethod = "GET"
                }
                conn.inputStream.bufferedReader().use { reader ->
                    val ip = reader.readText().trim()
                    conn.disconnect()
                    if (ip.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$"))) return ip
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    /**
     * Спроба достукатись до власної зовнішньої IP на вказаному порту (hairpin-перевірка).
     * Важливо: багато роутерів блокують "петлю" зсередини навіть коли порт відкритий назовні,
     * тому "недоступно" тут НЕ є гарантією безпеки — лише орієнтовний сигнал. Для точної
     * перевірки варто скористатись зовнішнім сервісом (кнопка в додатку відкриває такий сайт).
     */
    fun probeExternalPort(publicIp: String, port: Int, timeoutMs: Int = 1500): Boolean {
        return try {
            Socket().use { s ->
                s.connect(java.net.InetSocketAddress(publicIp, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    // ---------- ARP-таблиця і виробник за MAC (OUI) ----------

    /**
     * Найкраща спроба прочитати локальну ARP-таблицю (IP -> MAC) з /proc/net/arp.
     * На Android 10+ система зазвичай обмежує доступ для застосунків без прав — у такому
     * разі повертається порожня мапа, і додаток про це повідомляє користувачу.
     */
    fun readArpTable(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        try {
            java.io.File("/proc/net/arp").forEachLine { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 4 && parts[0] != "IP") {
                    val ip = parts[0]
                    val mac = parts[3]
                    if (mac.isNotBlank() && mac != "00:00:00:00:00:00") {
                        map[ip] = mac.uppercase()
                    }
                }
            }
        } catch (_: Exception) {
        }
        return map
    }

    /** Визначає ймовірного виробника пристрою за першими 3 байтами MAC-адреси (офлайн-база). */
    fun vendorFromMac(mac: String): String? {
        val prefix = mac.uppercase().replace("-", ":").split(":").take(3).joinToString("") 
        return OUI_DB[prefix]
    }

    private val OUI_DB: Map<String, String> = mapOf(
        "F02F74" to "Apple", "A4C361" to "Apple", "3C0754" to "Apple", "BC926B" to "Apple",
        "F0DBF8" to "Apple", "D8BB2C" to "Apple", "88E9FE" to "Apple", "A85C2C" to "Apple",
        "FCFC48" to "Apple", "6C4008" to "Apple", "0017F2" to "Apple", "001451" to "Apple",
        "9C8E99" to "Samsung", "5C0A5B" to "Samsung", "8847A1" to "Samsung", "D0176A" to "Samsung",
        "E89FF6" to "Samsung", "001377" to "Samsung", "ACC33A" to "Samsung", "CC07AB" to "Samsung",
        "286C07" to "Xiaomi", "64CC2E" to "Xiaomi", "F8A45F" to "Xiaomi", "7C1DD9" to "Xiaomi",
        "34CE00" to "Xiaomi", "9C99A0" to "Xiaomi",
        "F4F26D" to "Huawei", "00E0FC" to "Huawei", "20F3A3" to "Huawei", "5091E3" to "Huawei",
        "D86CE9" to "TP-Link", "F4F26A" to "TP-Link", "A42BB0" to "TP-Link", "50C7BF" to "TP-Link",
        "001E58" to "D-Link", "1CBDB9" to "D-Link", "C8D3A3" to "D-Link",
        "C05627" to "Google", "F4F5D8" to "Google", "4CE676" to "Google", "94EB2C" to "Google (Chromecast)",
        "FCA1B1" to "Amazon (Echo/Fire)", "44650D" to "Amazon (Echo/Fire)", "68371D" to "Amazon",
        "001AC2" to "Sony", "AC9B0A" to "Sony", "FCA621" to "Sony",
        "00056B" to "LG Electronics", "10683F" to "LG Electronics", "A09347" to "LG Electronics",
        "D8CF9C" to "Hewlett Packard", "3C4A92" to "Hewlett Packard",
        "001B63" to "Apple",
        "001E65" to "Netgear", "20E52A" to "Netgear", "A0404E" to "Netgear",
        "F0F61C" to "Ubiquiti", "24A43C" to "Ubiquiti", "788A20" to "Ubiquiti",
        "B827EB" to "Raspberry Pi Foundation", "DCA632" to "Raspberry Pi Foundation",
        "000C29" to "VMware (віртуальна машина)", "005056" to "VMware (віртуальна машина)"
    )
}
