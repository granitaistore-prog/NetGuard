package com.netguard.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : Activity() {

    private enum class Tab(val title: String) {
        INFO("Мережа"), DEVICES("Пристрої"), AIR("Ефір"), TIPS("Поради")
    }

    private val bg = Color.parseColor("#0F1419")
    private val card = Color.parseColor("#1C2630")
    private val accent = Color.parseColor("#1E88E5")
    private val good = Color.parseColor("#69F0AE")
    private val warn = Color.parseColor("#FFC107")
    private val bad = Color.parseColor("#FF5252")
    private val muted = Color.parseColor("#90A4AE")

    private lateinit var content: FrameLayout
    private val tabButtons = mutableMapOf<Tab, Button>()
    private var currentTab = Tab.INFO
    private val handler = Handler(Looper.getMainLooper())
    private var airReceiverRegistered = false

    private val airReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context?, i: android.content.Intent?) {
            if (currentTab == Tab.AIR) showAirResults()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildRoot())
        showTab(Tab.INFO)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (airReceiverRegistered) {
            try { unregisterReceiver(airReceiver) } catch (_: Exception) {}
        }
    }

    // ---------- Каркас ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        val title = TextView(this).apply {
            text = "Моя мережа"
            setTextColor(Color.WHITE)
            textSize = 20f
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }
        root.addView(title)

        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), 0, dp(8), dp(8))
        }
        for (t in Tab.values()) {
            val b = Button(this).apply {
                text = t.title
                isAllCaps = false
                textSize = 13f
                setTextColor(Color.WHITE)
                minHeight = dp(40)
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(card)
                }
                setOnClickListener { showTab(t) }
            }
            tabButtons[t] = b
            tabRow.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(3), 0, dp(3), 0)
            })
        }
        root.addView(tabRow)

        content = FrameLayout(this)
        val scroll = ScrollView(this).apply {
            addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun showTab(t: Tab) {
        currentTab = t
        for ((tab, b) in tabButtons) {
            (b.background as GradientDrawable).setColor(if (tab == t) accent else card)
        }
        content.removeAllViews()
        when (t) {
            Tab.INFO -> content.addView(buildInfoPanel())
            Tab.DEVICES -> content.addView(buildDevicesPanel())
            Tab.AIR -> content.addView(buildAirPanel())
            Tab.TIPS -> content.addView(buildTipsPanel())
        }
    }

    private fun sectionCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(card)
        }
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(12), dp(6), dp(12), dp(6))
        }
    }

    private fun rowLabelValue(label: String, value: String, valueColor: Int = Color.WHITE): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(5), 0, dp(5))
            addView(TextView(this@MainActivity).apply {
                text = label
                setTextColor(muted)
                textSize = 14f
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@MainActivity).apply {
                text = value
                setTextColor(valueColor)
                textSize = 14f
                gravity = Gravity.END
            })
        }
    }

    private fun heading(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 16f
        setPadding(0, 0, 0, dp(6))
    }

    private fun primaryButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(accent)
        }
        setOnClickListener { onClick() }
    }

    // ---------- Вкладка 1: Моя мережа ----------

    @SuppressLint("MissingPermission")
    private fun buildInfoPanel(): View {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val wm = NetworkUtils.wifiManager(this)
        val info = wm.connectionInfo

        val card1 = sectionCard()
        if (info == null || info.networkId == -1) {
            card1.addView(heading("Немає підключення до Wi-Fi"))
            card1.addView(TextView(this).apply {
                text = "Підключіться до своєї домашньої мережі, щоб побачити деталі."
                setTextColor(muted)
            })
        } else {
            var ssid = info.ssid ?: "?"
            if (ssid.startsWith("\"") && ssid.endsWith("\"")) ssid = ssid.substring(1, ssid.length - 1)
            if (ssid == "<unknown ssid>") ssid = "Невідомо (потрібен дозвіл на геолокацію)"

            card1.addView(heading("Підключено"))
            card1.addView(rowLabelValue("Мережа (SSID)", ssid))
            val (qualityLabel, _, qualityHex) = NetworkUtils.signalQuality(info.rssi)
            card1.addView(rowLabelValue("Сигнал", "${info.rssi} дБм · $qualityLabel", Color.parseColor(qualityHex)))
            card1.addView(rowLabelValue("Швидкість з'єднання", "${info.linkSpeed} Мбіт/с"))
            card1.addView(rowLabelValue("Діапазон", NetworkUtils.bandLabel(info.frequency)))
            val ch = NetworkUtils.channelFromFrequency(info.frequency)
            card1.addView(rowLabelValue("Канал", if (ch > 0) "$ch (${info.frequency} МГц)" else "${info.frequency} МГц"))
            card1.addView(rowLabelValue("IP-адреса телефону", NetworkUtils.intToIp(info.ipAddress)))

            val dhcp = wm.dhcpInfo
            if (dhcp != null) {
                card1.addView(rowLabelValue("Роутер (шлюз)", NetworkUtils.intToIp(dhcp.gateway)))
                card1.addView(rowLabelValue("DNS", "${NetworkUtils.intToIp(dhcp.dns1)}  ${NetworkUtils.intToIp(dhcp.dns2)}"))
            }
        }
        container.addView(card1)

        val card2 = sectionCard()
        card2.addView(heading("Що це означає"))
        card2.addView(TextView(this).apply {
            setTextColor(muted)
            textSize = 13f
            text = "Сигнал від −30 до −67 дБм — добре з'єднання. Слабший за −75 дБм " +
                    "означає, що варто перенести роутер ближче або додати репітер. " +
                    "Переповнений канал поруч із сусідніми мережами сповільнює Wi-Fi — " +
                    "перевірте вкладку «Ефір»."
        })
        container.addView(card2)

        return container
    }

    // ---------- Вкладка 2: Пристрої в мережі ----------

    private var devicesScanning = false

    private fun buildDevicesPanel(): View {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val card1 = sectionCard()
        card1.addView(heading("Пристрої у вашій мережі"))
        card1.addView(TextView(this).apply {
            setTextColor(muted)
            textSize = 13f
            text = "Сканує тільки вашу локальну підмережу (192.168.x.x), щоб показати, " +
                    "скільки пристроїв підключено — зручно перевірити, чи немає чужих."
        })

        val statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(0, dp(10), 0, dp(6))
            text = "Натисніть «Сканувати», щоб почати."
        }
        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        val resultsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val scanBtn = primaryButton("Сканувати мережу") {}

        scanBtn.setOnClickListener {
            if (devicesScanning) return@setOnClickListener
            val pair = NetworkUtils.localIpv4WithPrefix()
            val wm = NetworkUtils.wifiManager(this)
            if (pair == null || wm.connectionInfo == null || wm.connectionInfo.networkId == -1) {
                statusText.text = "Підключіться до Wi-Fi, щоб сканувати мережу."
                return@setOnClickListener
            }
            devicesScanning = true
            resultsBox.removeAllViews()
            val (myIp, prefix) = pair
            if (prefix < 20) {
                statusText.text = "Підмережа завелика для швидкого сканування."
                devicesScanning = false
                return@setOnClickListener
            }
            val parts = myIp.split(".")
            val base = "${parts[0]}.${parts[1]}.${parts[2]}."
            statusText.text = "Сканування $base 1–254…"
            progressBar.progress = 0

            val pool = Executors.newFixedThreadPool(32)
            val scanned = AtomicInteger(0)
            val foundCount = AtomicInteger(0)
            val gatewayIp = NetworkUtils.intToIp(wm.dhcpInfo?.gateway ?: 0)

            for (i in 1..254) {
                val ip = base + i
                pool.execute {
                    val alive = if (ip == myIp) true else NetworkUtils.probeHost(ip)
                    val done = scanned.incrementAndGet()
                    if (alive) foundCount.incrementAndGet()
                    handler.post {
                        progressBar.progress = (done * 100) / 254
                        statusText.text = "Перевірено $done / 254 · знайдено ${foundCount.get()}"
                        if (alive) {
                            val label = when (ip) {
                                myIp -> "$ip — цей телефон"
                                gatewayIp -> "$ip — роутер (шлюз)"
                                else -> ip
                            }
                            val row = TextView(this).apply {
                                text = "●  $label"
                                setTextColor(if (ip == gatewayIp || ip == myIp) accent else Color.WHITE)
                                textSize = 14f
                                setPadding(0, dp(3), 0, dp(3))
                            }
                            resultsBox.addView(row)
                        }
                        if (done == 254) {
                            devicesScanning = false
                            statusText.text = "Готово: у мережі знайдено ${foundCount.get()} пристрої(їв)."
                            pool.shutdown()
                        }
                    }
                }
            }
        }

        card1.addView(scanBtn)
        card1.addView(progressBar)
        card1.addView(statusText)
        card1.addView(resultsBox)
        container.addView(card1)

        val card2 = sectionCard()
        card2.addView(TextView(this).apply {
            setTextColor(muted)
            textSize = 12f
            text = "Якщо бачите пристрій, якого не впізнаєте, — змініть пароль Wi-Fi " +
                    "в налаштуваннях роутера і перепідключіть свої пристрої."
        })
        container.addView(card2)

        return container
    }

    // ---------- Вкладка 3: Ефір (сусідні мережі й канали) ----------

    private fun buildAirPanel(): View {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val card1 = sectionCard()
        card1.addView(heading("Мережі поруч і завантаженість каналів"))
        card1.addView(TextView(this).apply {
            setTextColor(muted)
            textSize = 13f
            text = "Показує Wi-Fi мережі навколо, щоб ви могли обрати для роутера канал " +
                    "з якомога меншим накладанням на сусідів. Потрібен дозвіл на геолокацію " +
                    "(вимога Android для сканування Wi-Fi), дані мережі нікуди не передаються."
        })
        val resultsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        val scanBtn = primaryButton("Сканувати ефір") { requestAirScan(resultsBox) }
        card1.addView(scanBtn)
        card1.addView(resultsBox)
        container.addView(card1)
        this.airResultsBox = resultsBox
        return container
    }

    private var airResultsBox: LinearLayout? = null

    private fun requestAirScan(resultsBox: LinearLayout) {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 100)
            resultsBox.removeAllViews()
            resultsBox.addView(TextView(this).apply {
                text = "Надайте дозвіл на геолокацію у вікні, що з'явилось, і натисніть «Сканувати ефір» ще раз."
                setTextColor(warn)
                textSize = 13f
            })
            return
        }
        if (!airReceiverRegistered) {
            registerReceiver(airReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
            airReceiverRegistered = true
        }
        resultsBox.removeAllViews()
        resultsBox.addView(TextView(this).apply {
            text = "Сканування…"
            setTextColor(muted)
        })
        val wm = NetworkUtils.wifiManager(this)
        val started = wm.startScan()
        if (!started) {
            handler.postDelayed({ showAirResults() }, 1200)
        }
    }

    @SuppressLint("MissingPermission")
    private fun showAirResults() {
        val box = airResultsBox ?: return
        val wm = NetworkUtils.wifiManager(this)
        val results = try { NetworkUtils.scanResultsSorted(wm.scanResults) } catch (_: SecurityException) { emptyList() }
        box.removeAllViews()
        if (results.isEmpty()) {
            box.addView(TextView(this).apply {
                text = "Мережі не знайдено або дозвіл ще не надано."
                setTextColor(muted)
            })
            return
        }
        val myFreq = wm.connectionInfo?.frequency ?: -1
        val myChannel = NetworkUtils.channelFromFrequency(myFreq)
        for (r in results.take(25)) {
            val ch = NetworkUtils.channelFromFrequency(r.frequency)
            val sameChannel = myChannel > 0 && ch == myChannel
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            row.addView(TextView(this).apply {
                text = (if (sameChannel) "⚠ " else "") + r.SSID
                setTextColor(if (sameChannel) warn else Color.WHITE)
                textSize = 14f
            })
            row.addView(TextView(this).apply {
                text = "Канал $ch · ${NetworkUtils.bandLabel(r.frequency)} · ${r.level} дБм · ${NetworkUtils.securityFromCapabilities(r.capabilities)}"
                setTextColor(muted)
                textSize = 12f
            })
            box.addView(row)
        }
        if (myChannel > 0) {
            box.addView(TextView(this).apply {
                text = "\nВаш канал зараз: $myChannel. Позначені ⚠ мережі використовують той самий канал — варто в налаштуваннях роутера обрати інший, вільніший."
                setTextColor(muted)
                textSize = 12f
                setPadding(0, dp(8), 0, 0)
            })
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && currentTab == Tab.AIR) {
            showTab(Tab.AIR)
        }
    }

    // ---------- Вкладка 4: Поради з безпеки ----------

    private fun buildTipsPanel(): View {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tips = listOf(
            "Увімкніть WPA2 або, якщо роутер підтримує, WPA3 — перевірте в налаштуваннях бездротової мережі." to true,
            "Поставте пароль Wi-Fi від 12 символів: літери, цифри, без очевидних слів." to true,
            "Змініть стандартний логін/пароль адмін-панелі роутера (admin/admin тощо)." to true,
            "Вимкніть WPS (кнопкове підключення) — це найслабша точка більшості роутерів." to true,
            "Перевірте оновлення прошивки роутера в адмін-панелі." to true,
            "Створіть окрему гостьову мережу для друзів і розумних пристроїв." to false,
            "Вимкніть віддалене керування роутером через інтернет, якщо не користуєтесь ним." to false,
            "Періодично перевіряйте список підключених пристроїв (вкладка «Пристрої»)." to false
        )
        val card1 = sectionCard()
        card1.addView(heading("Чек-лист безпеки домашньої мережі"))
        for ((text, important) in tips) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, dp(6))
            }
            row.addView(TextView(this).apply {
                this.text = if (important) "●" else "○"
                setTextColor(if (important) good else muted)
                setPadding(0, 0, dp(10), 0)
            })
            row.addView(TextView(this).apply {
                this.text = text
                setTextColor(Color.WHITE)
                textSize = 14f
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card1.addView(row)
        }
        container.addView(card1)

        val card2 = sectionCard()
        card2.addView(TextView(this).apply {
            setTextColor(muted)
            textSize = 12f
            text = "Цей додаток перевіряє лише вашу власну мережу: інформацію про підключення, " +
                    "пристрої у вашій підмережі та завантаженість Wi-Fi каналів поруч. " +
                    "Він не намагається підключатись до чужих мереж чи підбирати паролі."
        })
        container.addView(card2)
        return container
    }
}
