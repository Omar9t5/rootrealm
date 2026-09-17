package com.faroquetech.rootrealm

import com.faroquetech.theme.ThemeManager

import android.app.Activity
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.StatFs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import kotlin.math.max

class DeviceInfoActivity : BaseActivity() {

    // ============================================================
    // All UI colors come from the global Theme Manager.
    private val backgroundColor get() = ThemeManager.current(this).background
    private val cardColor get() = ThemeManager.current(this).surface
    private val cardColor2 get() = ThemeManager.current(this).surface2
    private val borderColor get() = ThemeManager.borderColor(ThemeManager.current(this))
    private val white get() = ThemeManager.current(this).text
    private val gray get() = ThemeManager.current(this).secondary
    private val darkGray get() = ThemeManager.current(this).secondary
    private val accent get() = ThemeManager.current(this).accent
    private val green get() = ThemeManager.current(this).success
    private val orange get() = ThemeManager.current(this).accent
    private val red get() = ThemeManager.current(this).error

    private lateinit var rootLayout: LinearLayout
    private lateinit var contentLayout: LinearLayout

    private lateinit var deviceNameValue: TextView
    private lateinit var deviceNameInfoValue: TextView
    private lateinit var modelValue: TextView
    private lateinit var refreshIcon: TextView

    private lateinit var ramUsedValue: TextView

    private lateinit var storageUsedValue: TextView

    private lateinit var batteryTempValue: TextView

    private lateinit var zramValue: TextView
    private lateinit var swapValue: TextView
    private lateinit var expansionValue: TextView

    private lateinit var rootValue: TextView
    private lateinit var shizukuValue: TextView
    private lateinit var selinuxValue: TextView

    private val executor =
        Executors.newSingleThreadExecutor()

    // ============================================================
    // LIFECYCLE
    // ============================================================

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        window.statusBarColor =
            backgroundColor

        window.navigationBarColor =
            backgroundColor

        buildUi()
        refreshInfo()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    // ============================================================
    // MAIN UI
    // ============================================================

    private fun buildUi() {

        rootLayout =
            LinearLayout(this)

        rootLayout.orientation =
            LinearLayout.VERTICAL

        rootLayout.setBackgroundColor(
            backgroundColor
        )

        val scroll =
            ScrollView(this)

        scroll.isFillViewport = true

        contentLayout =
            LinearLayout(this)

        contentLayout.orientation =
            LinearLayout.VERTICAL

        contentLayout.setPadding(
            dp(16),
            dp(16),
            dp(16),
            dp(28)
        )

        scroll.addView(
            contentLayout,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        rootLayout.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(rootLayout)

        buildHero()
        buildQuickStats()
        buildDeviceSection()
        buildAndroidSection()
        buildLocaleSection()
        buildProcessorSection()
        buildMemorySection()
        buildStorageSection()
        buildBatterySection()
        buildSystemSection()
        buildSettingsSection()
        buildReportSection()
    }

    // ============================================================
    // HERO
    // ============================================================

    private fun buildHero() {

        val card =
            card()

        val title =
            text(
                "Root Realm",
                30f,
                white
            )

        title.setTypeface(
            Typeface.DEFAULT,
            Typeface.BOLD
        )

        val subtitle =
            text(
                "Device & System Information",
                14f,
                gray
            )

        deviceNameValue =
            text(
                "Detecting device…",
                18f,
                accent
            )

        deviceNameValue.setTypeface(
            Typeface.DEFAULT,
            Typeface.BOLD
        )

        val refresh =
            refreshButton()

        refresh.setOnClickListener {
            spinRefreshIcon()
            showToast("Refreshing device information…", Toast.LENGTH_SHORT)
            refreshInfo()
        }

        card.addView(
            title
        )

        card.addView(
            subtitle,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        card.addView(
            space(12)
        )

        card.addView(
            deviceNameValue
        )

        card.addView(
            space(14)
        )

        card.addView(
            refresh,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            )
        )

        contentLayout.addView(
            card
        )

        contentLayout.addView(
            space(12)
        )
    }

    // ============================================================
    // QUICK STATS
    // ============================================================

    private fun buildQuickStats() {

    val row = LinearLayout(this)

    row.orientation =
        LinearLayout.HORIZONTAL

    row.gravity =
        Gravity.CENTER

    val root =
        miniCard("Root", red)

    rootValue =
        root.second

    val shizuku =
        miniCard("Shizuku", accent)

    shizukuValue =
        shizuku.second

    row.addView(
        root.first,
        LinearLayout.LayoutParams(
            0,
            dp(96),
            1f
        )
    )

    row.addView(
        addHorizontalGap(8)
    )

    row.addView(
        shizuku.first,
        LinearLayout.LayoutParams(
            0,
            dp(96),
            1f
        )
    )

    contentLayout.addView(
        row,
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(96)
        )
    )

    contentLayout.addView(
        space(16)
    )
}

private fun miniCard(
    label: String,
    accentColor: Int
): Pair<View, TextView> {

    val card =
        LinearLayout(this)

    card.orientation =
        LinearLayout.VERTICAL

    card.gravity =
        Gravity.CENTER

    card.setPadding(
        dp(6),
        dp(10),
        dp(6),
        dp(10)
    )

    card.background =
        cardTexture(
            cardColor2,
            cardColor,
            colorWithAlpha(
                accentColor,
                90
            ),
            14
        )

    card.elevation =
        dp(1).toFloat()

    val labelRow =
        LinearLayout(this)

    labelRow.orientation =
        LinearLayout.HORIZONTAL

    labelRow.gravity =
        Gravity.CENTER

    val dot =
        View(this)

    val dotBackground =
        GradientDrawable()

    dotBackground.shape =
        GradientDrawable.OVAL

    dotBackground.setColor(
        accentColor
    )

    dot.background =
        dotBackground

    labelRow.addView(
        dot,
        LinearLayout.LayoutParams(
            dp(6),
            dp(6)
        ).apply {
            rightMargin = dp(5)
        }
    )

    val labelText =
        TextView(this)

    labelText.text =
        label

    labelText.textSize =
        11f

    labelText.setTextColor(
        gray
    )

    labelText.gravity =
        Gravity.CENTER

    labelText.letterSpacing =
        0.02f

    labelRow.addView(
        labelText
    )

    val value =
        TextView(this)

    value.text =
        "--"

    value.textSize =
        18f

    value.setTextColor(
        white
    )

    value.gravity =
        Gravity.CENTER

    value.setTypeface(
        Typeface.DEFAULT,
        Typeface.BOLD
    )

    card.addView(
        labelRow,
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    )

    card.addView(
        value,
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(6)
        }
    )

    return Pair(
        card,
        value
    )
}
        // ============================================================
    // DEVICE SECTION
    // ============================================================

    private fun buildDeviceSection() {

        val card =
            card()

        card.addView(
            sectionHeader(
                "Device"
            )
        )

        deviceNameInfoValue = addInfoRow(
            card,
            "Device name",
            "Detecting…",
            true
        )

        addInfoRow(
            card,
            "Manufacturer",
            safeText(
                Build.MANUFACTURER
            ),
            true
        )

        addInfoRow(
            card,
            "Model",
            safeText(
                Build.MODEL
            ),
            true
        )

        addInfoRow(
            card,
            "Device codename",
            safeText(
                Build.DEVICE
            ),
            true
        )

        addInfoRow(
            card,
            "Product",
            safeText(
                Build.PRODUCT
            ),
            true
        )

        addInfoRow(
            card,
            "Hardware",
            safeText(
                Build.HARDWARE
            ),
            true
        )

        contentLayout.addView(card)
        contentLayout.addView(space(12))
    }

    // ============================================================
    // ANDROID SECTION
    // ============================================================

    private fun buildAndroidSection() {

        val card =
            card()

        card.addView(
            sectionHeader(
                "Android"
            )
        )

        addInfoRow(
            card,
            "Android version",
            "Android ${Build.VERSION.RELEASE}",
            true
        )

        addInfoRow(
            card,
            "SDK",
            Build.VERSION.SDK_INT.toString(),
            true
        )

        addInfoRow(
            card,
            "Security patch",
            getSecurityPatch(),
            true
        )

        addInfoRow(
            card,
            "Build ID",
            safeText(
                Build.ID
            ),
            true
        )

        addInfoRow(
            card,
            "Build",
            getBuildVersion(),
            true
        )

        addInfoRow(
            card,
            "Fingerprint",
            safeText(
                Build.FINGERPRINT
            ),
            true
        )

        contentLayout.addView(card)
        contentLayout.addView(space(12))
    }

    // ============================================================
    // LOCALE & RUNTIME SECTION
    // ============================================================

    private fun buildLocaleSection() {

        val card =
            card()

        card.addView(
            sectionHeader(
                "Locale & Runtime"
            )
        )

        addInfoRow(
            card,
            "Codename",
            safeText(
                Build.VERSION.CODENAME
            ),
            true
        )

        addInfoRow(
            card,
            "Preview SDK",
            if (Build.VERSION.SDK_INT >= 23) {
                Build.VERSION.PREVIEW_SDK_INT.toString()
            } else {
                "0"
            },
            true
        )

        addInfoRow(
            card,
            "Boot time",
            getBootTime(),
            true
        )

        addInfoRow(
            card,
            "64-bit OS",
            if (is64Bit()) "Yes" else "No",
            true
        )

        addInfoRow(
            card,
            "Language",
            Locale.getDefault().displayLanguage,
            true
        )

        addInfoRow(
            card,
            "Country",
            Locale.getDefault().displayCountry,
            true
        )

        addInfoRow(
            card,
            "Locale",
            Locale.getDefault().toString(),
            true
        )

        addInfoRow(
            card,
            "Time zone",
            TimeZone.getDefault().id,
            true
        )

        addInfoRow(
            card,
            "Time zone offset",
            getTimeZoneOffset(),
            true
        )

        addInfoRow(
            card,
            "Java runtime",
            safeText(
                System.getProperty("java.runtime.version")
            ),
            true
        )

        addInfoRow(
            card,
            "Java VM",
            safeText(
                System.getProperty("java.vm.version")
            ),
            true
        )

        addInfoRow(
            card,
            "OS name",
            safeText(
                System.getProperty("os.name")
            ),
            true
        )

        addInfoRow(
            card,
            "OS version",
            safeText(
                System.getProperty("os.version")
            ),
            true
        )

        addInfoRow(
            card,
            "Architecture",
            safeText(
                System.getProperty("os.arch")
            ),
            true
        )

        contentLayout.addView(card)
        contentLayout.addView(space(12))
    }

    private fun getBootTime(): String {

        return try {

            val bootTime =
                System.currentTimeMillis() -
                    SystemClock.elapsedRealtime()

            val formatter =
                SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss",
                    Locale.US
                )

            formatter.format(
                Date(bootTime)
            )

        } catch (_: Exception) {
            "Unknown"
        }
    }

    private fun is64Bit(): Boolean {

        return try {

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.M
            ) {
                android.os.Process.is64Bit()
            } else {
                false
            }

        } catch (_: Exception) {
            false
        }
    }

    private fun getTimeZoneOffset(): String {

        return try {

            val zone =
                TimeZone.getDefault()

            val offset =
                zone.getOffset(
                    System.currentTimeMillis()
                )

            val hours =
                offset / 3600000

            val minutes =
                (kotlin.math.abs(offset) % 3600000) /
                    60000

            val sign =
                if (offset >= 0) "+" else "-"

            String.format(
                Locale.US,
                "UTC%s%02d:%02d",
                sign,
                kotlin.math.abs(hours),
                minutes
            )

        } catch (_: Exception) {
            "Unknown"
        }
    }

    // ============================================================
    // PROCESSOR SECTION
    // ============================================================

    private fun buildProcessorSection() {

        val card =
            card()

        card.addView(
            sectionHeader(
                "Processor"
            )
        )

        addInfoRow(
            card,
            "CPU",
            getCpuName(),
            true
        )

        addInfoRow(
            card,
            "ABI",
            Build.SUPPORTED_ABIS
                .joinToString(", "),
            true
        )

        addInfoRow(
            card,
            "Primary ABI",
            Build.SUPPORTED_ABIS
                .firstOrNull()
                ?: "Unknown",
            true
        )

        addInfoRow(
            card,
            "Kernel",
            getKernelVersion(),
            true
        )

        addInfoRow(
            card,
            "CPU cores",
            Runtime.getRuntime()
                .availableProcessors()
                .toString(),
            true
        )

        contentLayout.addView(card)
        contentLayout.addView(space(12))
    }

    // ============================================================
    // MEMORY SECTION
    // ============================================================

    private fun buildMemorySection() {

        val card =
            card()

        card.addView(
            sectionHeader(
                "Memory"
            )
        )

        addInfoRow(
            card,
            "Physical RAM",
            formatRam(
                getPhysicalRamBytes()
            ),
            true
        )

        addInfoRow(
            card,
            "Android usable RAM",
            formatRam(
                getUsableRamBytes()
            ),
            true
        )

        addInfoRow(
            card,
            "Available RAM",
            formatRam(
                getAvailableRamBytes()
            ),
            true
        ).also {
            ramUsedValue = it
        }

        addInfoRow(
            card,
            "RAM type",
            getRamType(),
            true
        )

        addInfoRow(
            card,
            "ZRAM",
            getZramSummary(),
            true
        ).also {
            zramValue = it
        }

        addInfoRow(
            card,
            "Swap",
            getSwapSummary(),
            true
        ).also {
            swapValue = it
        }

        addInfoRow(
            card,
            "RAM expansion",
            getRamExpansionSummary(),
            true
        ).also {
            expansionValue = it
        }

        val note =
            text(
                "Physical RAM and Android usable RAM are shown separately. Android may report less usable memory than the physical RAM installed.",
                12f,
                gray
            )

        note.setPadding(
            dp(4),
            dp(12),
            dp(4),
            0
        )

        card.addView(note)

        contentLayout.addView(card)
        contentLayout.addView(space(12))
    }

    // ============================================================
    // STORAGE SECTION
    // ============================================================

    private fun buildStorageSection() {

        val card =
            card()

        card.addView(
            sectionHeader(
                "Storage"
            )
        )

        addInfoRow(
            card,
            "Total",
            formatStorage(
                getStorageTotalBytes()
            ),
            true
        )

        addInfoRow(
            card,
            "Used",
            formatStorage(
                getStorageUsedBytes()
            ),
            true
        ).also {
            storageUsedValue = it
        }

        addInfoRow(
            card,
            "Free",
            formatStorage(
                getStorageFreeBytes()
            ),
            true
        )

        addInfoRow(
            card,
            "Usage",
            "${getStoragePercent()}%",
            true
        )

        val progress =
            ProgressBar(
                this,
                null,
                android.R.attr.progressBarStyleHorizontal
            )

        progress.max = 100
        progress.progress =
            getStoragePercent()

        progress.progressTintList =
            android.content.res.ColorStateList.valueOf(
                accent
            )

        progress.layoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(8)
            ).apply {
                topMargin = dp(10)
                bottomMargin = dp(6)
            }

        card.addView(progress)

        contentLayout.addView(card)
        contentLayout.addView(space(12))
    }

    // ============================================================
    // BATTERY SECTION
    // ============================================================

    private fun buildBatterySection() {

        val card =
            card()

        card.addView(
            sectionHeader(
                "Battery"
            )
        )

        addInfoRow(
            card,
            "Level",
            batteryPercentText(),
            true
        )
        
        addInfoRow(
            card,
            "Status",
            batteryStatus(),
            true
        )

        addInfoRow(
            card,
            "Health",
            batteryHealth(),
            true
        )

        addInfoRow(
            card,
            "Temperature",
            String.format(
                Locale.US,
                "%.1f °C",
                getBatteryTemperature()
            ),
            true
        ).also {
            batteryTempValue = it
        }

        addInfoRow(
            card,
            "Voltage",
            formatVoltage(),
            true
        )

        addInfoRow(
            card,
            "Current",
            formatCurrent(),
            true
        )

        addInfoRow(
            card,
            "Technology",
            getBatteryTechnology(),
            true
        )

        addInfoRow(
            card,
            "Power source",
            getPowerSource(),
            true
        )

        contentLayout.addView(card)
        contentLayout.addView(space(12))
    }
        // ============================================================
    // SYSTEM SECTION
    // ============================================================

    private fun buildSystemSection() {

        val card =
            card()

        card.addView(
            sectionHeader(
                "System"
            )
        )

        addInfoRow(
            card,
            "SELinux",
            getSelinuxStatus(),
            true
        ).also {
            selinuxValue = it
        }

        addInfoRow(
            card,
            "Root",
            if (hasRoot()) {
                "Active"
            } else {
                "Inactive"
            },
            true
        )

        addInfoRow(
            card,
            "Shizuku",
            if (hasShizuku()) {
                "Active"
            } else {
                "Inactive"
            },
            true
        )

        addInfoRow(
            card,
            "Uptime",
            getUptime(),
            true
        )

        addInfoRow(
            card,
            "Android ID",
            getAndroidId(),
            true
        )

        addInfoRow(
            card,
            "Bootloader state",
            propertyOrUnknown(
                "ro.boot.verifiedbootstate"
            ),
            true
        )

        addInfoRow(
            card,
            "Flash locked",
            propertyOrUnknown(
                "ro.boot.flash.locked"
            ),
            true
        )

        addInfoRow(
            card,
            "Device state",
            propertyOrUnknown(
                "ro.boot.vbmeta.device_state"
            ),
            true
        )

        contentLayout.addView(card)
        contentLayout.addView(space(12))
    }

    // ============================================================
    // SETTINGS SECTION
    // ============================================================

    private fun buildSettingsSection() {

        val card =
            card()

        card.addView(
            sectionHeader(
                "Android Settings"
            )
        )

        addInfoRow(
            card,
            "Developer options",
            if (
                developerOptionsEnabled()
            ) {
                "Enabled"
            } else {
                "Disabled"
            },
            true
        )

        addInfoRow(
            card,
            "USB debugging / ADB",
            if (adbEnabled()) {
                "Enabled"
            } else {
                "Disabled"
            },
            true
        )

        val buttons =
            LinearLayout(this)

        buttons.orientation =
            LinearLayout.HORIZONTAL

        val developer =
            actionButton(
                "Developer options"
            )

        developer.setOnClickListener {

            try {

                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
                    )
                )

            } catch (_: Exception) {

                showToast("Developer options unavailable", Toast.LENGTH_SHORT)
            }
        }

        val settings =
            actionButton(
                "System settings"
            )

        settings.setOnClickListener {

            try {

                startActivity(
                    Intent(
                        Settings.ACTION_SETTINGS
                    )
                )

            } catch (_: Exception) {
            }
        }

        buttons.addView(
            developer,
            LinearLayout.LayoutParams(
                0,
                dp(46),
                1f
            )
        )

        buttons.addView(
            addHorizontalGap(8)
        )

        buttons.addView(
            settings,
            LinearLayout.LayoutParams(
                0,
                dp(46),
                1f
            )
        )

        card.addView(
            space(8)
        )

        card.addView(
            buttons
        )

        contentLayout.addView(card)
        contentLayout.addView(space(12))
    }

    // ============================================================
    // REPORT SECTION
    // ============================================================

    private fun buildReportSection() {

        val card =
            card()

        card.addView(
            sectionHeader(
                "Device Report"
            )
        )

        val description =
            text(
                "Generate a complete text report containing the detected device, Android, processor, memory, storage, battery and system information.",
                13f,
                gray
            )

        card.addView(
            description
        )

        card.addView(
            space(12)
        )

        val share =
            actionButton(
                "Share device report"
            )

        share.setOnClickListener {
            shareDeviceReport()
        }

        card.addView(
            share,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            )
        )

        contentLayout.addView(card)
    }

    // ============================================================
    // REFRESH
    // ============================================================

    private fun refreshInfo() {

        if (isFinishing || isDestroyed) return

        executor.execute {

           val name =
            getDeviceName()

            val availableRam =
                getAvailableRamBytes()

            val root =
                if (hasRoot()) {
                    "Active"
                } else {
                    "Inactive"
                }

            val shizuku =
                if (hasShizuku()) {
                    "Active"
                } else {
                    "Inactive"
                }

            runOnUiThread {

                deviceNameValue.text =
                    name

                deviceNameInfoValue.text =
                    name

                ramUsedValue.text =
                    formatRam(
                        availableRam
                    )

                storageUsedValue.text =
                    formatStorage(
                        getStorageUsedBytes()
                    )

                rootValue.text =
                    root

                shizukuValue.text =
                    shizuku

                zramValue.text =
                    getZramSummary()

                swapValue.text =
                    getSwapSummary()

                expansionValue.text =
                    getRamExpansionSummary()

                batteryTempValue.text =
                    String.format(
                        Locale.US,
                        "%.1f °C",
                        getBatteryTemperature()
                    )

                selinuxValue.text =
                    getSelinuxStatus()
            }
        }
    }

    // ============================================================
    // SECTION HEADER
    // ============================================================

    private fun sectionHeader(
        title: String
    ): TextView {

        val view =
            TextView(this)

        view.text =
            title

        view.textSize =
            18f

        view.setTextColor(
            white
        )

        view.setTypeface(
            Typeface.DEFAULT,
            Typeface.BOLD
        )

        view.setPadding(
            0,
            0,
            0,
            dp(12)
        )

        return view
    }

    // ============================================================
    // INFO ROW
    // ============================================================

    private fun addInfoRow(
        parent: LinearLayout,
        label: String,
        value: String,
        copyable: Boolean
    ): TextView {

        val row =
            LinearLayout(this)

        row.orientation =
            LinearLayout.HORIZONTAL

        row.gravity =
            Gravity.CENTER_VERTICAL

        row.setPadding(
            dp(4),
            dp(10),
            dp(4),
            dp(10)
        )

        val texts =
            LinearLayout(this)

        texts.orientation =
            LinearLayout.VERTICAL

        val labelView =
            TextView(this)

        labelView.text =
            label

        labelView.textSize =
            11f

        labelView.setTextColor(
            gray
        )

        val valueView =
            TextView(this)

        valueView.text =
            value

        valueView.textSize =
            14f

        valueView.setTextColor(
            white
        )

        valueView.setTypeface(
            Typeface.DEFAULT,
            Typeface.BOLD
        )

        valueView.maxLines =
            4

        texts.addView(
            labelView
        )

        texts.addView(
            valueView
        )

        row.addView(
            texts,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        if (copyable) {

            val copy =
                TextView(this)

            copy.text =
                "COPY"

            copy.textSize =
                10f

            copy.setTextColor(
                accent
            )

            copy.gravity =
                Gravity.CENTER

            copy.setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD
            )

            copy.setPadding(
                dp(8),
                dp(8),
                dp(8),
                dp(8)
            )

            copy.setOnClickListener {
                copyText(
                    label,
                    valueView.text.toString()
                )
            }

            row.addView(
                copy,
                LinearLayout.LayoutParams(
                    dp(62),
                    dp(40)
                )
            )
        }

        parent.addView(row)

        return valueView
    }
        // ============================================================
    // BUTTONS / CARDS
    // ============================================================

    private fun refreshButton(): LinearLayout {

        val button =
            LinearLayout(this)

        button.orientation =
            LinearLayout.HORIZONTAL

        button.gravity =
            Gravity.CENTER

        button.isClickable =
            true

        button.isFocusable =
            true

        button.background =
            rippleBackground(
                roundedBackground(
                    cardColor2,
                    borderColor,
                    16
                ),
                accent
            )

        refreshIcon =
            TextView(this)

        refreshIcon.text =
            "↻"

        refreshIcon.textSize =
            18f

        refreshIcon.setTextColor(
            accent
        )

        refreshIcon.setTypeface(
            Typeface.DEFAULT,
            Typeface.BOLD
        )

        val label =
            TextView(this)

        label.text =
            "Refresh"

        label.textSize =
            13f

        label.setTextColor(
            white
        )

        label.setTypeface(
            Typeface.DEFAULT,
            Typeface.BOLD
        )

        button.addView(
            refreshIcon,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                rightMargin = dp(8)
            }
        )

        button.addView(
            label
        )

        return button
    }

    private fun spinRefreshIcon() {

        refreshIcon.animate()
            .cancel()

        refreshIcon.rotation =
            refreshIcon.rotation % 360f

        refreshIcon.animate()
            .rotationBy(360f)
            .setDuration(900)
            .setInterpolator(
                LinearInterpolator()
            )
            .withLayer()
            .start()
    }

    private fun rippleBackground(
        base: Drawable,
        tint: Int
    ): Drawable {

        return RippleDrawable(
            ColorStateList.valueOf(
                colorWithAlpha(
                    tint,
                    70
                )
            ),
            base,
            null
        )
    }

    private fun colorWithAlpha(
        color: Int,
        alpha: Int
    ): Int {

        return Color.argb(
            alpha,
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    private fun cardTexture(
        top: Int,
        bottom: Int,
        stroke: Int,
        radius: Int
    ): GradientDrawable {

        val drawable =
            GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    top,
                    bottom
                )
            )

        drawable.setStroke(
            dp(1),
            stroke
        )

        drawable.cornerRadius =
            dp(radius).toFloat()

        return drawable
    }

    private fun actionButton(
        title: String
    ): Button {

        val button =
            Button(this)

        button.text =
            title

        button.textSize =
            13f

        button.setTextColor(
            white
        )

        button.isAllCaps =
            false

        button.typeface =
            Typeface.DEFAULT_BOLD

        button.background =
            rippleBackground(
                roundedBackground(
                    cardColor2,
                    borderColor,
                    16
                ),
                accent
            )

        button.stateListAnimator =
            null

        return button
    }

    private fun card():
        LinearLayout {

        val view =
            LinearLayout(this)

        view.orientation =
            LinearLayout.VERTICAL

        view.setPadding(
            dp(14),
            dp(14),
            dp(14),
            dp(14)
        )

        view.background =
            cardTexture(
                cardColor2,
                cardColor,
                borderColor,
                20
            )

        view.elevation =
            dp(2).toFloat()

        return view
    }

    private fun text(
        value: String,
        size: Float,
        color: Int
    ): TextView {

        val view =
            TextView(this)

        view.text =
            value

        view.textSize =
            size

        view.setTextColor(
            color
        )

        return view
    }

    private fun space(
        height: Int
    ): View {

        return View(this).apply {

            layoutParams =
                LinearLayout.LayoutParams(
                    1,
                    dp(height)
                )
        }
    }

    private fun addHorizontalGap(
        width: Int
    ): View {

        return View(this).apply {

            layoutParams =
                LinearLayout.LayoutParams(
                    dp(width),
                    1
                )
        }
    }

    private fun roundedBackground(
        fill: Int,
        stroke: Int,
        radius: Int
    ): GradientDrawable {

        val drawable =
            GradientDrawable()

        drawable.shape =
            GradientDrawable.RECTANGLE

        drawable.setColor(
            fill
        )

        drawable.setStroke(
            dp(1),
            stroke
        )

        drawable.cornerRadius =
            dp(radius).toFloat()

        return drawable
    }

    // ============================================================
    // DEVICE NAME
    // ============================================================

    private fun getDeviceName(): String {

        val properties =
            arrayOf(
                "ro.product.marketname",
                "ro.product.vendor.marketname",
                "ro.product.odm.marketname",
                "ro.product.system.marketname",
                "ro.product.product.marketname",
                "ro.product.name",
                "ro.product.vendor.name",
                "ro.product.odm.product.name"
            )

        for (property in properties) {

            val value =
                getSystemProperty(
                    property
                )

            if (
                value.isNotBlank() &&
                !value.equals(
                    "unknown",
                    true
                )
            ) {
                return value.trim()
            }
        }

        val manufacturer =
            Build.MANUFACTURER
                .trim()
                .replaceFirstChar {
                    it.uppercase()
                }

        val model =
            Build.MODEL.trim()

        if (
            manufacturer.isNotBlank() &&
            model.isNotBlank()
        ) {

            if (
                model.startsWith(
                    manufacturer,
                    true
                )
            ) {
                return model
            }

            return "$manufacturer $model"
        }

        return model.ifBlank {
            "Unknown device"
        }
    }

    private fun getSystemProperty(
        name: String
    ): String {

        return try {

            val process =
                ProcessBuilder(
                    "getprop",
                    name
                )
                    .redirectErrorStream(true)
                    .start()

            val result =
                process.inputStream
                    .bufferedReader()
                    .use {
                        it.readText()
                    }
                    .trim()

            process.waitFor()

            result

        } catch (_: Exception) {
            ""
        }
    }

    // ============================================================
    // CPU
    // ============================================================

    private fun getCpuName(): String {

        return try {

            val file =
                File(
                    "/proc/cpuinfo"
                )

            if (!file.exists()) {
                return safeText(
                    Build.HARDWARE
                )
            }

            val keys =
                listOf(
                    "Hardware",
                    "model name",
                    "Processor",
                    "cpu model"
                )

            for (key in keys) {

                val line =
                    file.readLines()
                        .firstOrNull {
                            it.startsWith(
                                "$key:",
                                true
                            )
                        }

                if (line != null) {

                    val value =
                        line.substringAfter(
                            ":"
                        ).trim()

                    if (
                        value.isNotBlank()
                    ) {
                        return value
                    }
                }
            }

            safeText(
                Build.HARDWARE
            )

        } catch (_: Exception) {

            safeText(
                Build.HARDWARE
            )
        }
    }

    private fun getKernelVersion(): String {

        return try {

            val process =
                ProcessBuilder(
                    "uname",
                    "-r"
                )
                    .redirectErrorStream(true)
                    .start()

            val result =
                process.inputStream
                    .bufferedReader()
                    .use {
                        it.readText()
                    }
                    .trim()

            process.waitFor()

            result.ifBlank {

                System.getProperty(
                    "os.version"
                ) ?: "Unknown"
            }

        } catch (_: Exception) {

            System.getProperty(
                "os.version"
            ) ?: "Unknown"
        }
    }

    // ============================================================
    // MEMORY FILE PARSING
    // ============================================================

    private fun readProcMemInfo():
        Map<String, Long> {

        val result =
            mutableMapOf<String, Long>()

        try {

            val file =
                File(
                    "/proc/meminfo"
                )

            if (!file.exists()) {
                return result
            }

            file.forEachLine { line ->

                val parts =
                    line.split(
                        ":",
                        limit = 2
                    )

                if (parts.size != 2) {
                    return@forEachLine
                }

                val key =
                    parts[0].trim()

                val valuePart =
                    parts[1].trim()

                val match =
                    Regex(
                        """(\d+)"""
                    ).find(
                        valuePart
                    )

                val number =
                    match
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toLongOrNull()

                if (number != null) {

                    result[key] =
                        number * 1024L
                }
            }

        } catch (_: Exception) {
        }

        return result
    }

    private fun getPhysicalRamBytes():
        Long {

        val memInfo =
            readProcMemInfo()

        return memInfo[
            "MemTotal"
        ] ?: 0L
    }

    private fun getUsableRamBytes():
        Long {

        return try {

            val manager =
                getSystemService(
                    Context.ACTIVITY_SERVICE
                ) as ActivityManager

            val info =
                ActivityManager.MemoryInfo()

            manager.getMemoryInfo(
                info
            )

            info.totalMem

        } catch (_: Exception) {
            0L
        }
    }

    private fun getAvailableRamBytes():
        Long {

        return try {

            val manager =
                getSystemService(
                    Context.ACTIVITY_SERVICE
                ) as ActivityManager

            val info =
                ActivityManager.MemoryInfo()

            manager.getMemoryInfo(
                info
            )

            info.availMem

        } catch (_: Exception) {
            0L
        }
    }

    private fun getRamType(): String {

        val properties =
            arrayOf(
                "ro.boot.ddr",
                "ro.boot.ddr_type",
                "ro.vendor.ddr_type",
                "ro.vendor.ddr",
                "ro.hardware.ddr"
            )

        for (property in properties) {

            val value =
                getSystemProperty(
                    property
                )

            if (value.isNotBlank()) {

                return value
                    .trim()
                    .uppercase(
                        Locale.US
                    )
            }
        }

        return "Not exposed by device"
    }
        // ============================================================
    // ZRAM
    // ============================================================

    private fun getZramDevices():
        List<String> {

        return try {

            val directory =
                File("/sys/block")

            if (!directory.exists()) {
                return emptyList()
            }

            directory.listFiles()
                ?.filter {
                    it.name.startsWith(
                        "zram"
                    )
                }
                ?.map {
                    it.name
                }
                ?.sorted()
                ?: emptyList()

        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun getZramSizeBytes():
        Long {

        var total =
            0L

        for (
            device in getZramDevices()
        ) {

            total += readLongFile(
                File(
                    "/sys/block/$device/disksize"
                )
            )
        }

        return total
    }

    private fun getZramUsedBytes():
        Long {

        var total =
            0L

        for (
            device in getZramDevices()
        ) {

            val file =
                File(
                    "/sys/block/$device/mm_stat"
                )

            if (!file.exists()) {
                continue
            }

            try {

                val values =
                    file.readText()
                        .trim()
                        .split(
                            Regex("\\s+")
                        )

                var largest =
                    0L

                for (
                    index in 0 until
                        minOf(
                            2,
                            values.size
                        )
                ) {

                    val value =
                        values[index]
                            .toLongOrNull()
                            ?: 0L

                    largest =
                        max(
                            largest,
                            value
                        )
                }

                total += largest

            } catch (_: Exception) {
            }
        }

        return total
    }

    private fun getZramSummary():
        String {

        val devices =
            getZramDevices()

        if (devices.isEmpty()) {

            return "Unavailable on this device"
        }

        val size =
            getZramSizeBytes()

        val used =
            getZramUsedBytes()

        if (size <= 0L) {

            return "Detected • not active"
        }

        return if (used > 0L) {

            "${formatStorage(size)} • " +
                "${formatStorage(used)} used"

        } else {

            "${formatStorage(size)} • Enabled"
        }
    }

    // ============================================================
    // SWAP
    // ============================================================

    private fun getSwapInfo():
        Pair<Long, Long> {

        var total =
            0L

        var used =
            0L

        try {

            val file =
                File("/proc/swaps")

            if (!file.exists()) {
                return Pair(
                    0L,
                    0L
                )
            }

            val lines =
                file.readLines()

            for (
                line in lines.drop(1)
            ) {

                val parts =
                    line.trim()
                        .split(
                            Regex("\\s+")
                        )

                if (parts.size < 4) {
                    continue
                }

                val size =
                    parts[2]
                        .toLongOrNull()

                val usedValue =
                    parts[3]
                        .toLongOrNull()

                if (size != null) {

                    total +=
                        size * 1024L
                }

                if (
                    usedValue != null
                ) {

                    used +=
                        usedValue * 1024L
                }
            }

        } catch (_: Exception) {
        }

        return Pair(
            total,
            used
        )
    }

    private fun getSwapSummary():
        String {

        val swap =
            getSwapInfo()

        if (swap.first <= 0L) {

            return "Not detected"
        }

        return if (swap.second > 0L) {

            "${formatStorage(swap.first)} • " +
                "${formatStorage(swap.second)} used"

        } else {

            "${formatStorage(swap.first)} • Enabled"
        }
    }

    // ============================================================
    // RAM EXPANSION
    // ============================================================

    private fun getRamExpansionProperties():
        List<Pair<String, String>> {

        val properties =
            arrayOf(
                "ro.vendor.miui.support_ram_extension",
                "ro.config.zram",
                "ro.config.swap",
                "persist.sys.zram",
                "persist.vendor.zram",
                "ro.vendor.ram_expand",
                "ro.vendor.ram.expansion",
                "persist.sys.ram_expand",
                "persist.vendor.ram_expand",
                "ro.config.hw_ram",
                "ro.config.miui_ram_expand",
                "ro.vendor.miui.ram_expand",
                "ro.miui.cust_ram",
                "ro.oplus.memory.extension",
                "ro.vendor.oplus.memory.extension",
                "persist.sys.oplus.memory.extension",
                "ro.vivo.ram_expand",
                "persist.vivo.ram_expand"
            )

        val result =
            mutableListOf<Pair<String, String>>()

        for (
            property in properties
        ) {

            val value =
                getSystemProperty(
                    property
                )

            if (value.isNotBlank()) {

                result.add(
                    Pair(
                        property,
                        value
                    )
                )
            }
        }

        return result
    }

    private fun parseMemoryValue(
        raw: String
    ): Long {

        val value =
            raw.trim()
                .lowercase(
                    Locale.US
                )

        if (value.isBlank()) {
            return 0L
        }

        val match =
            Regex(
                """([0-9]+(?:\.[0-9]+)?)\s*(kb|mb|gb|kib|mib|gib)?"""
            ).find(
                value
            ) ?: return 0L

        val number =
            match.groupValues[1]
                .toDoubleOrNull()
                ?: return 0L

        val unit =
            match.groupValues
                .getOrNull(2)
                ?: ""

        val multiplier =
            when (unit) {

                "kb",
                "kib" ->
                    1024.0

                "mb",
                "mib" ->
                    1024.0 *
                        1024.0

                "gb",
                "gib" ->
                    1024.0 *
                        1024.0 *
                        1024.0

                else -> {

                    if (
                        number < 1024.0
                    ) {
                        1024.0 *
                            1024.0
                    } else {
                        1.0
                    }
                }
            }

        return (
            number * multiplier
        ).toLong()
    }

    private fun getExplicitExpansionBytes():
        Long {

        for (
            pair in getRamExpansionProperties()
        ) {

            val bytes =
                parseMemoryValue(
                    pair.second
                )

            if (bytes > 0L) {
                return bytes
            }
        }

        return 0L
    }

    private fun getRamExpansionStatus():
        String {

        val properties =
            getRamExpansionProperties()

        if (properties.isEmpty()) {

            return "Not detected"
        }

        for (
            pair in properties
        ) {

            val value =
                pair.second
                    .trim()
                    .lowercase(
                        Locale.US
                    )

            if (
                value == "1" ||
                value == "true" ||
                value == "on" ||
                value == "enabled"
            ) {

                return "Enabled"
            }
        }

        return "Detected • vendor property"
    }

    private fun getRamExpansionSummary():
        String {

        val bytes =
            getExplicitExpansionBytes()

        val status =
            getRamExpansionStatus()

        return when {

            bytes > 0L ->
                "${formatRam(bytes)} • $status"

            status == "Enabled" ->
                "Enabled • vendor managed"

            status.startsWith(
                "Detected"
            ) ->
                status

            else ->
                "Not detected"
        }
    }

    // ============================================================
    // FILE HELPER
    // ============================================================

    private fun readLongFile(
        file: File
    ): Long {

        return try {

            if (!file.exists()) {
                return 0L
            }

            file.readText()
                .trim()
                .toLongOrNull()
                ?: 0L

        } catch (_: Exception) {
            0L
        }
    }

    // ============================================================
    // STORAGE
    // ============================================================

    private fun getStorageTotalBytes():
        Long {

        return try {

            val stat =
                StatFs(
                    Environment
                        .getDataDirectory()
                        .absolutePath
                )

            stat.blockCountLong *
                stat.blockSizeLong

        } catch (_: Exception) {
            0L
        }
    }

    private fun getStorageFreeBytes():
        Long {

        return try {

            val stat =
                StatFs(
                    Environment
                        .getDataDirectory()
                        .absolutePath
                )

            stat.availableBlocksLong *
                stat.blockSizeLong

        } catch (_: Exception) {
            0L
        }
    }

    private fun getStorageUsedBytes():
        Long {

        val total =
            getStorageTotalBytes()

        val free =
            getStorageFreeBytes()

        return if (total > 0L) {

            max(
                0L,
                total - free
            )

        } else {
            0L
        }
    }

    private fun getStoragePercent():
        Int {

        val total =
            getStorageTotalBytes()

        if (total <= 0L) {
            return 0
        }

        return (
            getStorageUsedBytes()
                .toDouble() /
                total.toDouble() *
                100.0
            )
            .toInt()
            .coerceIn(
                0,
                100
            )
    }
        // ============================================================
    // BATTERY
    // ============================================================

    private fun getBatteryIntent():
        Intent? {

        return try {

            registerReceiver(
                null,
                IntentFilter(
                    Intent.ACTION_BATTERY_CHANGED
                )
            )

        } catch (_: Exception) {
            null
        }
    }

    private fun getBatteryPercent():
        Int {

        val intent =
            getBatteryIntent()
                ?: return -1

        val level =
            intent.getIntExtra(
                BatteryManager.EXTRA_LEVEL,
                -1
            )

        val scale =
            intent.getIntExtra(
                BatteryManager.EXTRA_SCALE,
                -1
            )

        if (
            level < 0 ||
            scale <= 0
        ) {
            return -1
        }

        return (
            level.toFloat() /
                scale.toFloat() *
                100f
            )
            .toInt()
            .coerceIn(
                0,
                100
            )
    }

    private fun batteryPercentText():
        String {

        val value =
            getBatteryPercent()

        return if (value >= 0) {
            "$value%"
        } else {
            "Unavailable"
        }
    }

    private fun getBatteryTemperature():
        Float {

        val intent =
            getBatteryIntent()
                ?: return 0f

        return intent.getIntExtra(
            BatteryManager.EXTRA_TEMPERATURE,
            0
        ) / 10f
    }

    private fun getBatteryVoltage():
        Int {

        val intent =
            getBatteryIntent()
                ?: return 0

        return intent.getIntExtra(
            BatteryManager.EXTRA_VOLTAGE,
            0
        )
    }

    private fun getBatteryCurrentMicroAmps():
        Int {

        return try {

            val manager =
                getSystemService(
                    Context.BATTERY_SERVICE
                ) as BatteryManager

            manager.getIntProperty(
                BatteryManager
                    .BATTERY_PROPERTY_CURRENT_NOW
            )

        } catch (_: Exception) {
            0
        }
    }

    private fun batteryStatus(
        intent: Intent? =
            getBatteryIntent()
    ): String {

        if (intent == null) {
            return "Unknown"
        }

        return when (
            intent.getIntExtra(
                BatteryManager.EXTRA_STATUS,
                -1
            )
        ) {

            BatteryManager
                .BATTERY_STATUS_CHARGING ->
                "Charging"

            BatteryManager
                .BATTERY_STATUS_DISCHARGING ->
                "Discharging"

            BatteryManager
                .BATTERY_STATUS_FULL ->
                "Full"

            BatteryManager
                .BATTERY_STATUS_NOT_CHARGING ->
                "Not charging"

            else ->
                "Unknown"
        }
    }

    private fun batteryHealth(
        intent: Intent? =
            getBatteryIntent()
    ): String {

        if (intent == null) {
            return "Unknown"
        }

        return when (
            intent.getIntExtra(
                BatteryManager.EXTRA_HEALTH,
                -1
            )
        ) {

            BatteryManager
                .BATTERY_HEALTH_GOOD ->
                "Good"

            BatteryManager
                .BATTERY_HEALTH_OVERHEAT ->
                "Overheat"

            BatteryManager
                .BATTERY_HEALTH_DEAD ->
                "Dead"

            BatteryManager
                .BATTERY_HEALTH_OVER_VOLTAGE ->
                "Over voltage"

            BatteryManager
                .BATTERY_HEALTH_UNSPECIFIED_FAILURE ->
                "Failure"

            BatteryManager
                .BATTERY_HEALTH_COLD ->
                "Cold"

            else ->
                "Unknown"
        }
    }

    private fun getPowerSource(
        intent: Intent? =
            getBatteryIntent()
    ): String {

        if (intent == null) {
            return "Unknown"
        }

        return when (
            intent.getIntExtra(
                BatteryManager.EXTRA_PLUGGED,
                0
            )
        ) {

            BatteryManager
                .BATTERY_PLUGGED_USB ->
                "USB"

            BatteryManager
                .BATTERY_PLUGGED_AC ->
                "AC charger"

            BatteryManager
                .BATTERY_PLUGGED_WIRELESS ->
                "Wireless"

            else -> {

                if (
                    batteryStatus(
                        intent
                    ) == "Discharging"
                ) {
                    "Battery"
                } else {
                    "Not connected"
                }
            }
        }
    }

    private fun getBatteryTechnology():
        String {

        val intent =
            getBatteryIntent()

        return intent?.getStringExtra(
            BatteryManager.EXTRA_TECHNOLOGY
        ) ?: "Unknown"
    }

    private fun formatVoltage():
        String {

        val voltage =
            getBatteryVoltage()

        return if (voltage > 0) {

            String.format(
                Locale.US,
                "%.2f V",
                voltage / 1000.0
            )

        } else {
            "Unavailable"
        }
    }

    private fun formatCurrent():
        String {

        val current =
            getBatteryCurrentMicroAmps()

        return if (current != 0) {

            "${current / 1000} mA"

        } else {
            "Unavailable"
        }
    }

    // ============================================================
    // SYSTEM
    // ============================================================

    private fun getSecurityPatch():
        String {

        return try {

            Build.VERSION
                .SECURITY_PATCH
                .ifBlank {
                    "Unknown"
                }

        } catch (_: Exception) {
            "Unknown"
        }
    }

    private fun getBuildVersion():
        String {

        return Build.DISPLAY
            .takeIf {
                it.isNotBlank()
            }
            ?: Build.ID
                .takeIf {
                    it.isNotBlank()
                }
            ?: "Unknown"
    }

    private fun getSelinuxStatus():
        String {

        return try {

            val process =
                ProcessBuilder(
                    "getenforce"
                )
                    .redirectErrorStream(true)
                    .start()

            val result =
                process.inputStream
                    .bufferedReader()
                    .use {
                        it.readText()
                    }
                    .trim()

            process.waitFor()

            result.ifBlank {
                "Unknown"
            }

        } catch (_: Exception) {
            "Unavailable"
        }
    }

    private fun hasRoot():
        Boolean {

        return try {

            val process =
                ProcessBuilder(
                    "su",
                    "-c",
                    "id"
                )
                    .redirectErrorStream(true)
                    .start()

            val output =
                process.inputStream
                    .bufferedReader()
                    .use {
                        it.readText()
                    }

            val exit =
                process.waitFor()

            exit == 0 &&
                output.contains(
                    "uid=0"
                )

        } catch (_: Exception) {
            false
        }
    }

    private fun isShizukuInstalled():
        Boolean {

        return try {

            packageManager.getPackageInfo(
                "moe.shizuku.privileged.api",
                0
            )

            true

        } catch (_: Exception) {
            false
        }
    }

    private fun hasShizuku():
        Boolean {

        return try {

            isShizukuInstalled() &&
                checkSelfPermission(
                    "moe.shizuku.manager.permission.API_V23"
                ) == PackageManager.PERMISSION_GRANTED

        } catch (_: Exception) {
            false
        }
    }

    // ============================================================
    // SETTINGS
    // ============================================================

    private fun developerOptionsEnabled():
        Boolean {

        return try {

            Settings.Global.getInt(
                contentResolver,
                Settings.Global
                    .DEVELOPMENT_SETTINGS_ENABLED,
                0
            ) == 1

        } catch (_: Exception) {
            false
        }
    }

    private fun adbEnabled():
        Boolean {

        return try {

            Settings.Global.getInt(
                contentResolver,
                Settings.Global.ADB_ENABLED,
                0
            ) == 1

        } catch (_: Exception) {
            false
        }
    }

    private fun getAndroidId():
        String {

        return try {

            Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ANDROID_ID
            ) ?: "Unavailable"

        } catch (_: Exception) {
            "Unavailable"
        }
    }

    private fun getUptime():
        String {

        val milliseconds =
            SystemClock
                .elapsedRealtime()

        val seconds =
            milliseconds / 1000L

        val days =
            seconds / 86400L

        val hours =
            (seconds % 86400L) /
                3600L

        val minutes =
            (seconds % 3600L) /
                60L

        val secs =
            seconds % 60L

        return when {

            days > 0L ->
                "${days}d ${hours}h ${minutes}m"

            hours > 0L ->
                "${hours}h ${minutes}m"

            minutes > 0L ->
                "${minutes}m ${secs}s"

            else ->
                "${secs}s"
        }
    }

    // ============================================================
    // FORMATTING
    // ============================================================

    private fun formatRam(
        bytes: Long
    ): String {

        if (bytes <= 0L) {
            return "Unavailable"
        }

        val gb =
            bytes.toDouble() /
                (
                    1024.0 *
                        1024.0 *
                        1024.0
                    )

        val rounded =
            kotlin.math.round(
                gb
            )

        return if (
            kotlin.math.abs(
                gb - rounded
            ) <= 0.35
        ) {

            "${rounded.toInt()} GB"

        } else {

            String.format(
                Locale.US,
                "%.1f GB",
                gb
            )
        }
    }

    private fun formatStorage(
        bytes: Long
    ): String {

        if (bytes <= 0L) {
            return "Unavailable"
        }

        val gb =
            bytes.toDouble() /
                (
                    1024.0 *
                        1024.0 *
                        1024.0
                    )

        if (gb >= 1024.0) {

            return String.format(
                Locale.US,
                "%.1f TB",
                gb / 1024.0
            )
        }

        if (gb >= 1.0) {

            return String.format(
                Locale.US,
                "%.1f GB",
                gb
            )
        }

        val mb =
            bytes.toDouble() /
                (
                    1024.0 *
                        1024.0
                    )

        return String.format(
            Locale.US,
            "%.0f MB",
            mb
        )
    }

    // ============================================================
    // REPORT
    // ============================================================

    private fun buildDeviceReport():
        String {

        val builder =
            StringBuilder()

        builder.appendLine(
            "ROOTREALM DEVICE REPORT"
        )

        builder.appendLine(
            "======================"
        )

        builder.appendLine()

        builder.appendLine(
            "DEVICE"
        )

        builder.appendLine(
            "Name: ${getDeviceName()}"
        )

        builder.appendLine(
            "Manufacturer: ${Build.MANUFACTURER}"
        )

        builder.appendLine(
            "Model: ${Build.MODEL}"
        )

        builder.appendLine(
            "Device: ${Build.DEVICE}"
        )

        builder.appendLine(
            "Product: ${Build.PRODUCT}"
        )

        builder.appendLine(
            "Hardware: ${Build.HARDWARE}"
        )

        builder.appendLine()

        builder.appendLine(
            "ANDROID"
        )

        builder.appendLine(
            "Version: Android ${Build.VERSION.RELEASE}"
        )

        builder.appendLine(
            "SDK: ${Build.VERSION.SDK_INT}"
        )

        builder.appendLine(
            "Security patch: ${getSecurityPatch()}"
        )

        builder.appendLine(
            "Build ID: ${Build.ID}"
        )

        builder.appendLine(
            "Fingerprint: ${Build.FINGERPRINT}"
        )

        builder.appendLine()

        builder.appendLine(
            "PROCESSOR"
        )

        builder.appendLine(
            "CPU: ${getCpuName()}"
        )

        builder.appendLine(
            "ABI: ${Build.SUPPORTED_ABIS.joinToString()}"
        )

        builder.appendLine(
            "Kernel: ${getKernelVersion()}"
        )

        builder.appendLine()

        builder.appendLine(
            "MEMORY"
        )

        builder.appendLine(
            "Physical RAM: ${
                formatRam(
                    getPhysicalRamBytes()
                )
            }"
        )

        builder.appendLine(
            "Android usable RAM: ${
                formatRam(
                    getUsableRamBytes()
                )
            }"
        )

        builder.appendLine(
            "Available RAM: ${
                formatRam(
                    getAvailableRamBytes()
                )
            }"
        )

        builder.appendLine(
            "RAM type: ${getRamType()}"
        )

        builder.appendLine(
            "ZRAM: ${getZramSummary()}"
        )

        builder.appendLine(
            "Swap: ${getSwapSummary()}"
        )

        builder.appendLine(
            "RAM expansion: ${
                getRamExpansionSummary()
            }"
        )

        builder.appendLine()

        builder.appendLine(
            "STORAGE"
        )

        builder.appendLine(
            "Total: ${
                formatStorage(
                    getStorageTotalBytes()
                )
            }"
        )

        builder.appendLine(
            "Used: ${
                formatStorage(
                    getStorageUsedBytes()
                )
            }"
        )

        builder.appendLine(
            "Free: ${
                formatStorage(
                    getStorageFreeBytes()
                )
            }"
        )

        builder.appendLine(
            "Usage: ${getStoragePercent()}%"
        )

        builder.appendLine()

        builder.appendLine(
            "BATTERY"
        )

        builder.appendLine(
            "Level: ${batteryPercentText()}"
        )

        builder.appendLine(
            "Status: ${batteryStatus()}"
        )

        builder.appendLine(
            "Health: ${batteryHealth()}"
        )

        builder.appendLine(
            "Temperature: ${
                String.format(
                    Locale.US,
                    "%.1f °C",
                    getBatteryTemperature()
                )
            }"
        )

        builder.appendLine(
            "Voltage: ${formatVoltage()}"
        )

        builder.appendLine(
            "Current: ${formatCurrent()}"
        )

        builder.appendLine(
            "Technology: ${
                getBatteryTechnology()
            }"
        )

        builder.appendLine(
            "Power source: ${
                getPowerSource()
            }"
        )

        builder.appendLine()

        builder.appendLine(
            "SYSTEM"
        )

        builder.appendLine(
            "SELinux: ${
                getSelinuxStatus()
            }"
        )

        builder.appendLine(
            "Root: ${
                if (hasRoot()) {
                    "Active"
                } else {
                    "Inactive"
                }
            }"
        )

        builder.appendLine(
            "Shizuku: ${
                if (hasShizuku()) {
                    "Active"
                } else {
                    "Inactive"
                }
            }"
        )

        builder.appendLine(
            "Developer options: ${
                if (
                    developerOptionsEnabled()
                ) {
                    "Enabled"
                } else {
                    "Disabled"
                }
            }"
        )

        builder.appendLine(
            "ADB: ${
                if (adbEnabled()) {
                    "Enabled"
                } else {
                    "Disabled"
                }
            }"
        )

        builder.appendLine(
            "Uptime: ${getUptime()}"
        )

        builder.appendLine(
            "Android ID: ${getAndroidId()}"
        )

        return builder.toString()
    }

    private fun shareDeviceReport() {

        val report =
            buildDeviceReport()

        val intent =
            Intent(
                Intent.ACTION_SEND
            ).apply {

                type =
                    "text/plain"

                putExtra(
                    Intent.EXTRA_SUBJECT,
                    "Root Realm Device Report"
                )

                putExtra(
                    Intent.EXTRA_TEXT,
                    report
                )
            }

        try {

            startActivity(
                Intent.createChooser(
                    intent,
                    "Share device report"
                )
            )

        } catch (_: Exception) {

            showToast("No compatible app found", Toast.LENGTH_SHORT)
        }
    }

    // ============================================================
    // COPY
    // ============================================================

    private fun copyText(
        value: String
    ) {

        copyText(
            "Root Realm",
            value
        )
    }

    private fun copyText(
        label: String,
        value: String
    ) {

        if (value.isBlank()) {
            return
        }

        try {

            val clipboard =
                getSystemService(
                    Context.CLIPBOARD_SERVICE
                ) as ClipboardManager

            clipboard.setPrimaryClip(
                ClipData.newPlainText(
                    label,
                    value
                )
            )

            showToast("$label copied", Toast.LENGTH_SHORT)

        } catch (_: Exception) {

            showToast("Unable to copy", Toast.LENGTH_SHORT)
        }
    }

    // ============================================================
    // SAFE TEXT
    // ============================================================

    private fun safeText(
        value: String?,
        fallback: String = "Unknown"
    ): String {

        return value
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }
            ?: fallback
    }

    private fun propertyOrUnknown(
        property: String
    ): String {

        return safeText(
            getSystemProperty(
                property
            )
        )
    }

    // ============================================================
    // DP
    // ============================================================

    private fun dp(
        value: Int
    ): Int {

        return (
            value *
                resources
                    .displayMetrics
                    .density
            ).toInt()
    }
}