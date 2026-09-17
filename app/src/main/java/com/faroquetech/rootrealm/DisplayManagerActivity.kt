package com.faroquetech.rootrealm

import com.faroquetech.theme.ThemeManager
import com.faroquetech.theme.RootRealmGlobalTheme

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.widget.*
import kotlin.math.roundToInt
import kotlin.math.sqrt

class DisplayManagerActivity : BaseActivity() {

    private val bg get() = ThemeManager.current(this).background
    private val card get() = ThemeManager.current(this).surface
    private val white get() = ThemeManager.current(this).text
    private val gray get() = ThemeManager.current(this).secondary
    private val green get() = ThemeManager.current(this).success

    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        build()
    }

    private fun build() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(18), dp(15), dp(18), dp(25))
        }

        title(root, "Display Manager")

        val scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        showDetails()
        RootRealmGlobalTheme.applyTheme(this)
    }

    private fun showDetails() {
        content.removeAllViews()
        val dm = resources.displayMetrics
        val d = display

        section("Display Overview")
        item("Resolution", "${dm.widthPixels} × ${dm.heightPixels}")
        item("Refresh Rate", refreshRate(d))
        item("Density", "${dm.densityDpi} dpi (${String.format("%.2fx", dm.density)})")
        item("Scaled Density", String.format("%.2f", dm.scaledDensity))
        item("Display ID", d.displayId.toString())
        item("Display Name", d.name ?: "Unknown")
        item("Orientation", orientation())

        section("Display Controls")
        addBrightnessControl()
        addAutoBrightnessControl()
        addTimeoutControl()
        addStayAwakeControl()
        addRefreshRateControl()

        section("High Brightness / HBM")
        addHbmControls()

        section("HDR & Color")
        item("HDR Support", hdrSupport(d))
        item("Wide Color Gamut", wideColor(d))
        item("HDR Headroom", hdrHeadroom(d))

        section("Refresh Rate")
        item("Current", refreshRate(d))
        item("Supported Modes", supportedModes(d))
        item("Active Mode", activeMode(d))

        section("Screen Geometry")
        item("Physical DPI X", String.format("%.1f dpi", dm.xdpi))
        item("Physical DPI Y", String.format("%.1f dpi", dm.ydpi))
        item("Estimated Size", screenSize())
        item("Scaled Resolution",
            "${(dm.widthPixels / dm.density).roundToInt()} × ${(dm.heightPixels / dm.density).roundToInt()} dp")

        section("Android Display")
        item("Android Version", Build.VERSION.RELEASE ?: "Unknown")
        item("API Level", Build.VERSION.SDK_INT.toString())
        item("Manufacturer", Build.MANUFACTURER)
        item("Model", Build.MODEL)
        item("Hardware", Build.HARDWARE)

        section("Display Capabilities")
        item("State", displayState(d))
        item("Rotation", rotation(d))
        item("Presentation Display", if (d.flags and Display.FLAG_PRESENTATION != 0) "Yes" else "No")
        item("Secure Display", if (d.flags and Display.FLAG_SECURE != 0) "Yes" else "No")

        val refresh = Button(this).apply {
            text = "Refresh Display"
            textSize = 15f
            setTextColor(white)
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(ThemeManager.current(this@DisplayManagerActivity).surface2, 18)
            setOnClickListener { showDetails() }
        }
        content.addView(refresh, LinearLayout.LayoutParams(-1, dp(60)).apply {
            topMargin = dp(12)
            bottomMargin = dp(8)
        })

        val note = TextView(this).apply {
            text = "System controls use Android settings. HBM/kernel controls are device-specific and only appear when a compatible sysfs control is detected."
            textSize = 13f
            setTextColor(gray)
            setPadding(dp(5), dp(4), dp(5), dp(12))
        }
        content.addView(note)
        RootRealmGlobalTheme.applyTheme(this)
    }

    private fun addBrightnessControl() {
        val current = currentBrightness()
        val box = controlBox()
        label(box, "Screen Brightness")
        val value = TextView(this).apply {
            text = "$current%"
            textSize = 15f
            setTextColor(white)
        }
        val seek = SeekBar(this).apply {
            max = 100
            progress = current
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    value.text = "$p%"
                    if (fromUser) setBrightness(p)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        box.addView(value)
        box.addView(seek)
        content.addView(box, controlParams())
    }

    private fun addAutoBrightnessControl() {
        val box = controlBox()
        label(box, "Automatic Brightness")
        val sw = Switch(this).apply {
            text = if (autoBrightnessEnabled()) "Enabled" else "Disabled"
            setTextColor(white)
            isChecked = autoBrightnessEnabled()
            setOnCheckedChangeListener { _, checked ->
                setAutoBrightness(checked)
                text = if (checked) "Enabled" else "Disabled"
            }
        }
        box.addView(sw)
        content.addView(box, controlParams())
    }

    private fun addTimeoutControl() {
        val box = controlBox()
        label(box, "Screen Timeout")
        val spinner = Spinner(this)
        val choices = arrayOf("30 seconds", "1 minute", "2 minutes", "5 minutes", "10 minutes", "30 minutes")
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, choices)
        val current = currentTimeoutSeconds()
        val values = intArrayOf(30, 60, 120, 300, 600, 1800)
        var selected = 0
        for (i in values.indices) if (kotlin.math.abs(values[i] - current) < 30) selected = i
        spinner.setSelection(selected)
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                if (pos in values.indices) setTimeout(values[pos])
            }
        }
        box.addView(spinner)
        content.addView(box, controlParams())
    }

    private fun addStayAwakeControl() {
        val box = controlBox()
        label(box, "Keep Screen Awake")
        val sw = Switch(this).apply {
            text = "While this app is open"
            setTextColor(white)
            isChecked = window.attributes.screenBrightness >= 0f
            setOnCheckedChangeListener { _, checked ->
                window.addFlags(if (checked) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0)
                if (!checked) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        box.addView(sw)
        content.addView(box, controlParams())
    }

    private fun addRefreshRateControl() {
        val box = controlBox()
        label(box, "Preferred Refresh Rate (this app)")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val modes = display.supportedModes
            val labels = modes.map {
                "${it.physicalWidth} × ${it.physicalHeight} @ ${String.format("%.0f", it.refreshRate)} Hz"
            }.toTypedArray()
            val spinner = Spinner(this)
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
            spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                    if (pos in modes.indices) setPreferredMode(modes[pos])
                }
            }
            box.addView(spinner)
        } else {
            label(box, "Not supported on this Android version")
        }
        val note = TextView(this).apply {
            text = "Android normally permits an app to request a preferred mode; changing the system-wide refresh policy requires OEM/root-specific controls."
            textSize = 12f
            setTextColor(gray)
        }
        box.addView(note)
        content.addView(box, controlParams())
    }

    private fun addHbmControls() {
        val hbm = findHbmNode()
        val box = controlBox()

        if (hbm == null) {
            label(box, "High Brightness Mode")
            label(box, "No compatible HBM/kernel control was detected on this device.")
            label(box, "Kernel support can differ by panel, vendor and ROM.")
        } else {
            label(box, "Detected HBM control")
            label(box, hbm)
            val status = TextView(this).apply {
                text = "Current: ${readNode(hbm)}"
                textSize = 14f
                setTextColor(green)
            }
            box.addView(status)

            val enable = Button(this).apply {
                text = "Enable High Brightness"
                setTextColor(white)
                background = rounded(ThemeManager.current(this@DisplayManagerActivity).surface2, 18)
                setOnClickListener {
                    val ok = writeRoot(hbm, "1")
                    status.text = if (ok) "Current: enabled" else "Current: unable to write (root required)"
                }
            }
            val disable = Button(this).apply {
                text = "Disable High Brightness"
                setTextColor(white)
                background = rounded(ThemeManager.current(this@DisplayManagerActivity).surface2, 18)
                setOnClickListener {
                    val ok = writeRoot(hbm, "0")
                    status.text = if (ok) "Current: disabled" else "Current: unable to write (root required)"
                }
            }
            box.addView(enable)
            box.addView(disable)

            val warning = TextView(this).apply {
                text = "HBM is device-specific. It may increase power use and heat. Root Realm only writes a detected, writable control and does not invent a panel path."
                textSize = 12f
                setTextColor(gray)
            }
            box.addView(warning)
        }
        content.addView(box, controlParams())
    }

    private fun currentBrightness(): Int = try {
        val v = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        ((v / 255f) * 100f).roundToInt().coerceIn(0, 100)
    } catch (_: Exception) { 0 }

    private fun setBrightness(percent: Int) {
        val value = (percent.coerceIn(0, 100) * 255 / 100)
        try {
            if (Settings.System.canWrite(this)) {
                Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
            } else {
                val lp = window.attributes
                lp.screenBrightness = value / 255f
                window.attributes = lp
            }
        } catch (_: Exception) {}
    }

    private fun autoBrightnessEnabled(): Boolean = try {
        Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) ==
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    } catch (_: Exception) { false }

    private fun setAutoBrightness(enabled: Boolean) {
        try {
            if (Settings.System.canWrite(this)) {
                Settings.System.putInt(
                    contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    if (enabled) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                    else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
            }
        } catch (_: Exception) {}
    }

    private fun currentTimeoutSeconds(): Long = try {
        Settings.System.getInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT).toLong() / 1000
    } catch (_: Exception) { 60 }

    private fun setTimeout(seconds: Int) {
        try {
            if (Settings.System.canWrite(this))
                Settings.System.putInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, seconds * 1000)
        } catch (_: Exception) {}
    }

    private fun setPreferredMode(mode: Display.Mode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val lp = window.attributes
                lp.preferredDisplayModeId = mode.modeId
                window.attributes = lp
            } catch (_: Exception) {}
        }
    }

    /*
     * HBM paths vary heavily between Samsung, Qualcomm, MediaTek and custom kernels.
     * Only an existing readable path is offered. Writing normally requires root.
     */
    private fun findHbmNode(): String? {
        val candidates = arrayOf(
            "/sys/class/backlight/panel0-backlight/hbm",
            "/sys/class/backlight/panel-backlight/hbm",
            "/sys/class/backlight/backlight/hbm",
            "/sys/class/drm/card0-DSI-1/hbm",
            "/sys/devices/platform/soc/soc:qcom,dsi-display/hbm",
            "/sys/devices/platform/soc/soc:qcom,dsi-display/disp_param"
        )
        for (p in candidates) {
            try {
                val f = java.io.File(p)
                if (f.exists() && f.canRead()) return p
            } catch (_: Exception) {}
        }
        return null
    }

    private fun readNode(path: String): String = try {
        java.io.File(path).readText().trim().ifEmpty { "unknown" }
    } catch (_: Exception) { "unreadable" }

    private fun writeRoot(path: String, value: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "printf '%s' '$value' > '$path'"))
            process.waitFor() == 0
        } catch (_: Exception) { false }
    }

    private fun refreshRate(d: Display): String = try {
        if (d.refreshRate > 0f) String.format("%.2f Hz", d.refreshRate) else "Unknown"
    } catch (_: Exception) { "Unknown" }

    private fun supportedModes(d: Display): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) try {
            d.supportedModes.joinToString("\n") {
                "${it.physicalWidth} × ${it.physicalHeight} @ ${String.format("%.2f", it.refreshRate)} Hz"
            }.ifEmpty { "Unknown" }
        } catch (_: Exception) { "Unknown" } else "Not available"

    private fun activeMode(d: Display): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) try {
            val m = d.mode
            "${m.physicalWidth} × ${m.physicalHeight} @ ${String.format("%.2f", m.refreshRate)} Hz"
        } catch (_: Exception) { "Unknown" } else "Not available"

    private fun hdrSupport(d: Display): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) try {
            d.hdrCapabilities.supportedHdrTypes.map { hdrName(it) }
                .ifEmpty { listOf("Not supported") }.joinToString(", ")
        } catch (_: Exception) { "Unknown" } else "Not available"

    private fun hdrName(type: Int): String = when (type) {
        1 -> "Dolby Vision"
        2 -> "HDR10"
        3 -> "HLG"
        4 -> "HDR10+"
        else -> "HDR ($type)"
    }

    private fun wideColor(d: Display): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) try {
            if (d.isWideColorGamut) "Supported" else "Not supported"
        } catch (_: Exception) { "Unknown" } else "Not available"

    private fun hdrHeadroom(d: Display): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) try {
            String.format("%.2fx", d.hdrSdrRatio)
        } catch (_: Exception) { "Unavailable" } else "Not available"

    private fun screenSize(): String = try {
        val dm = resources.displayMetrics
        if (dm.xdpi <= 0 || dm.ydpi <= 0) "Unavailable" else {
            val w = dm.widthPixels / dm.xdpi
            val h = dm.heightPixels / dm.ydpi
            String.format("%.2f inches (estimated)", sqrt(w * w + h * h))
        }
    } catch (_: Exception) { "Unavailable" }

    private fun orientation(): String = when (resources.configuration.orientation) {
        1 -> "Portrait"
        2 -> "Landscape"
        else -> "Undefined"
    }

    private fun rotation(d: Display): String = when (d.rotation) {
        0 -> "0°"
        1 -> "90°"
        2 -> "180°"
        3 -> "270°"
        else -> "Unknown"
    }

    private fun displayState(d: Display): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) when (d.state) {
            Display.STATE_ON -> "ON"
            Display.STATE_OFF -> "OFF"
            Display.STATE_DOZE -> "DOZE"
            Display.STATE_DOZE_SUSPEND -> "DOZE SUSPEND"
            Display.STATE_UNKNOWN -> "UNKNOWN"
            else -> "State ${d.state}"
        } else "Unknown"

    private fun section(text: String) {
        content.addView(TextView(this).apply {
            this.text = text
            textSize = 18f
            setTextColor(white)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(3), dp(14), 0, dp(9))
        })
    }

    private fun item(name: String, value: String) {
        val box = controlBox()
        label(box, name)
        label(box, value, 15f, white)
        content.addView(box, controlParams())
    }

    private fun controlBox() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = rounded(card, 18)
    }

    private fun label(parent: LinearLayout, text: String, size: Float = 14f, color: Int = gray) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (size >= 18f) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(2), 0, dp(3))
        })
    }

    private fun controlParams() = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = dp(4)
        bottomMargin = dp(4)
    }

    private fun title(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = 26f
            setTextColor(white)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(20))
        })
    }

    private fun rounded(color: Int, radius: Int) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density + 0.5f).toInt()
}
