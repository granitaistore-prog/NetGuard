package com.buildlevel.app

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.atan2

class MainActivity : Activity(), SensorEventListener {

    private enum class Mode(val title: String) {
        SURFACE("Площина"),   // телефон лежить екраном догори
        WALL("Стіна"),        // телефон притиснутий до стіни (виска)
        TUBE("Лінійка")       // горизонталь по одній осі, екран вертикально
    }

    private lateinit var sensorManager: SensorManager
    private lateinit var prefs: SharedPreferences
    private lateinit var levelView: LevelView
    private lateinit var holdButton: Button
    private lateinit var unitButton: Button
    private val modeButtons = mutableMapOf<Mode, Button>()
    private var accel: Sensor? = null

    private var mode = Mode.SURFACE
    private var held = false
    private var percent = false

    private val gravity = FloatArray(3)
    private var hasData = false
    private var rawA = 0f
    private var rawB = 0f
    private var wasLevel = false
    private var lastBuzz = 0L

    private val bgColor = Color.parseColor("#0F1419")
    private val btnColor = Color.parseColor("#2A3440")
    private val btnActive = Color.parseColor("#1E88E5")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        prefs = getSharedPreferences("level", Context.MODE_PRIVATE)
        mode = try {
            Mode.valueOf(prefs.getString("mode", Mode.SURFACE.name) ?: Mode.SURFACE.name)
        } catch (e: IllegalArgumentException) {
            Mode.SURFACE
        }
        percent = prefs.getBoolean("percent", false)

        setContentView(buildUi())
        applyMode()

        if (accel == null) {
            Toast.makeText(this, "На цьому пристрої немає акселерометра", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        accel?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        hasData = false
    }

    // ---------- UI ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun paint(b: Button, color: Int) {
        b.background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(color)
        }
    }

    private fun makeButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            setTextColor(Color.WHITE)
            minHeight = dp(48)
            minimumHeight = dp(48)
            paint(this, btnColor)
            setOnClickListener { onClick() }
        }
    }

    private fun rowParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(4), dp(4), dp(4), dp(4))
        }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (m in Mode.values()) {
            val b = makeButton(m.title) { setMode(m) }
            modeButtons[m] = b
            modeRow.addView(b, rowParams())
        }
        root.addView(
            modeRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        levelView = LevelView(this)
        root.addView(levelView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bottomRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        holdButton = makeButton("Зафіксувати") { toggleHold() }
        val calibrateButton = makeButton("Калібрувати") { calibrate() }
        calibrateButton.setOnLongClickListener { resetCalibration(); true }
        unitButton = makeButton("") { togglePercent() }
        bottomRow.addView(holdButton, rowParams())
        bottomRow.addView(calibrateButton, rowParams())
        bottomRow.addView(unitButton, rowParams())
        root.addView(
            bottomRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        return root
    }

    private fun applyMode() {
        levelView.style = if (mode == Mode.TUBE) LevelView.Style.TUBE else LevelView.Style.CIRCLE
        levelView.percent = percent
        for ((m, b) in modeButtons) paint(b, if (m == mode) btnActive else btnColor)
        unitButton.text = if (percent) "Одиниці: %" else "Одиниці: °"
        holdButton.text = if (held) "Відпустити" else "Зафіксувати"
        wasLevel = false
    }

    private fun setMode(m: Mode) {
        mode = m
        prefs.edit().putString("mode", m.name).apply()
        applyMode()
    }

    private fun toggleHold() {
        held = !held
        applyMode()
    }

    private fun togglePercent() {
        percent = !percent
        prefs.edit().putBoolean("percent", percent).apply()
        applyMode()
    }

    // ---------- Калібрування ----------

    @Suppress("DEPRECATION")
    private fun rotation(): Int = windowManager.defaultDisplay.rotation

    private fun key(axis: String) = "${mode.name}_${rotation()}_$axis"

    private fun calibrate() {
        prefs.edit().putFloat(key("a"), rawA).putFloat(key("b"), rawB).apply()
        Toast.makeText(this, "Відкалібровано: поточне положення = 0°", Toast.LENGTH_SHORT).show()
    }

    private fun resetCalibration() {
        prefs.edit().remove(key("a")).remove(key("b")).apply()
        Toast.makeText(this, "Калібрування скинуто", Toast.LENGTH_SHORT).show()
    }

    // ---------- Датчик ----------

    private fun deg(r: Float): Float = Math.toDegrees(r.toDouble()).toFloat()

    override fun onSensorChanged(event: SensorEvent) {
        val alpha = 0.12f
        if (!hasData) {
            for (i in 0..2) gravity[i] = event.values[i]
            hasData = true
        } else {
            for (i in 0..2) gravity[i] += alpha * (event.values[i] - gravity[i])
        }

        // Переводимо осі датчика в осі екрана (щоб усе працювало і в альбомній орієнтації)
        val fx = gravity[0]
        val fy = gravity[1]
        val fz = gravity[2]
        val sx: Float
        val sy: Float
        when (rotation()) {
            Surface.ROTATION_90 -> { sx = -fy; sy = fx }
            Surface.ROTATION_180 -> { sx = -fx; sy = -fy }
            Surface.ROTATION_270 -> { sx = fy; sy = -fx }
            else -> { sx = fx; sy = fy }
        }

        when (mode) {
            Mode.SURFACE -> {
                rawA = deg(atan2(sx, abs(fz)))
                rawB = deg(atan2(sy, abs(fz)))
            }
            Mode.WALL -> {
                rawA = deg(atan2(sx, abs(sy)))
                rawB = deg(atan2(fz, abs(sy)))
            }
            Mode.TUBE -> {
                rawA = deg(atan2(sx, abs(sy)))
                rawB = 0f
            }
        }

        if (held) return

        val a = rawA - prefs.getFloat(key("a"), 0f)
        val b = rawB - prefs.getFloat(key("b"), 0f)
        levelView.update(a, b)

        val level = levelView.isLevel()
        val now = SystemClock.elapsedRealtime()
        if (level && !wasLevel && now - lastBuzz > 800) {
            buzz()
            lastBuzz = now
        }
        wasLevel = level
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    @Suppress("DEPRECATION")
    private fun buzz() {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (!v.hasVibrator()) return
        v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
